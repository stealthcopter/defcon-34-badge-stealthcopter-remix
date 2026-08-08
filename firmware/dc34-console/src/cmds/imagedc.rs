// imagedc.rs — Phase 2 mirror of `image` for the Defcon-logo replacement slot.
//
// Same wire format as `image`: 70-byte chunks (idx u16 BE, 64 B pixels, CRC32 u32 BE),
// base64-encoded, 32 chunks total = 128×128×1bpp = 2048 B.
//
// Writes to PDDB dict DC34_DICT, key DC34_IMAGE_DEFCON. Signals _Vault2_ with
// opcode 1028 (VaultOp::ImageDefconLoad).
//
// Subcommand:
//   imagedc clear         → wipe stored defcon replacement, fall back to built-in dc_logo
//   imagedc <base64>      → send chunk (repeat 32×)

use core::fmt::Write;
use std::io::Write as FsWrite;

use base64::{Engine as _, engine::general_purpose::STANDARD as B64};
use dc34_api::{DC34_DICT, DC34_IMAGE_DEFCON};
use pddb::Pddb;

use crate::{CommonEnv, ShellCmdApi};

const CHUNK_DATA_SIZE: usize = 64;
const CHUNK_INDEX_BYTES: usize = 2;
const CHUNK_CRC_BYTES: usize = 4;
const CHUNK_WIRE_SIZE: usize = CHUNK_INDEX_BYTES + CHUNK_DATA_SIZE + CHUNK_CRC_BYTES; // 70
const NUM_CHUNKS: usize = 32;
const BITMAP_WORDS: usize = 512;

pub struct ImageDc {
    chunks: Vec<Option<[u8; CHUNK_DATA_SIZE]>>,
    received_count: usize,
    pddb: Pddb,
}

impl ImageDc {
    pub fn new() -> Self {
        ImageDc { chunks: vec![None; NUM_CHUNKS], received_count: 0, pddb: Pddb::new() }
    }

    pub fn is_complete(&self) -> bool { self.received_count == NUM_CHUNKS }

    pub fn to_bitmap(&self) -> [u32; BITMAP_WORDS] {
        assert!(self.is_complete(), "bitmap not yet complete");
        let mut bitmap = [0u32; BITMAP_WORDS];
        for (chunk_idx, slot) in self.chunks.iter().enumerate() {
            let data = slot.as_ref().unwrap();
            let word_base = chunk_idx * (CHUNK_DATA_SIZE / 4);
            for w in 0..(CHUNK_DATA_SIZE / 4) {
                let o = w * 4;
                bitmap[word_base + w] =
                    u32::from_be_bytes([data[o], data[o + 1], data[o + 2], data[o + 3]]);
            }
        }
        bitmap
    }

    pub fn clear(&mut self) {
        for slot in self.chunks.iter_mut() {
            *slot = None;
        }
        self.received_count = 0;
    }
}

impl<'a> ShellCmdApi<'a> for ImageDc {
    cmd_api!(imagedc);

    fn process(&mut self, args: String, _env: &mut CommonEnv) -> Result<Option<String>, xous::Error> {
        let mut ret = String::new();
        if args == "clear" {
            self.clear();
            self.pddb.delete_key(DC34_DICT, DC34_IMAGE_DEFCON, None).ok();
            let conn = _env.xns.request_connection_blocking("_Vault2_").unwrap();
            xous::send_message(conn, xous::Message::new_scalar(1028, 0, 0, 0, 0)).ok();
            write!(ret, "CLEAR").unwrap();
            return Ok(Some(ret));
        }

        let b64 = args.trim();
        if b64.is_empty() {
            write!(ret, "ERR").unwrap();
            return Ok(Some(ret));
        }

        let decoded = match B64.decode(b64) {
            Ok(d) => d,
            Err(_) => {
                write!(ret, "ERR").unwrap();
                return Ok(Some(ret));
            }
        };

        if decoded.len() != CHUNK_WIRE_SIZE {
            write!(ret, "ERR").unwrap();
            return Ok(Some(ret));
        }

        let index = u16::from_be_bytes([decoded[0], decoded[1]]) as usize;
        let received_crc = u32::from_be_bytes([decoded[66], decoded[67], decoded[68], decoded[69]]);

        let computed_crc = crc32fast::hash(&decoded[..CHUNK_INDEX_BYTES + CHUNK_DATA_SIZE]);
        if computed_crc != received_crc {
            write!(ret, "ERR").unwrap();
            return Ok(Some(ret));
        }

        if index >= NUM_CHUNKS {
            write!(ret, "ERR").unwrap();
            return Ok(Some(ret));
        }

        let mut data_arr = [0u8; CHUNK_DATA_SIZE];
        data_arr.copy_from_slice(&decoded[CHUNK_INDEX_BYTES..CHUNK_INDEX_BYTES + CHUNK_DATA_SIZE]);

        let was_empty = self.chunks[index].is_none();
        self.chunks[index] = Some(data_arr);
        if was_empty {
            self.received_count += 1;
        }

        if self.is_complete() {
            {
                let mut image_key = self
                    .pddb
                    .get(DC34_DICT, DC34_IMAGE_DEFCON, None, true, true, Some(2048), None::<fn()>)
                    .expect("couldn't get PDDB defcon key");
                let words = self.to_bitmap();
                let bytes: &[u8] = bytemuck::cast_slice(&words);
                image_key.write_all(bytes).ok();
            }
            self.clear();
            let conn = _env.xns.request_connection_blocking("_Vault2_").unwrap();
            xous::send_message(conn, xous::Message::new_scalar(1028, 1, 0, 0, 0)).ok();
            write!(ret, "SUCCESS").unwrap();
        } else {
            write!(ret, "OK").unwrap();
        }

        Ok(Some(ret))
    }
}
