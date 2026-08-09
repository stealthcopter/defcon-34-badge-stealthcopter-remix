// imagedc.rs — mirror of `image` for the Defcon-logo replacement slot. Same
// multi-frame wire format and semantics; the only difference is the PDDB key
// (DC34_IMAGE_DEFCON) and the opcode used to signal the vault (1028).
//
// See image.rs for the wire-format doc.

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
const FRAME_BYTES: usize = 2048;
const MAX_FRAMES: usize = 32;

pub struct ImageDc {
    frames: Vec<Vec<Option<[u8; CHUNK_DATA_SIZE]>>>,
    received_count: usize,
    frame_count: usize,
    pddb: Pddb,
}

impl ImageDc {
    pub fn new() -> Self {
        let mut s =
            ImageDc { frames: Vec::new(), received_count: 0, frame_count: 1, pddb: Pddb::new() };
        s.reset_frames();
        s
    }

    fn reset_frames(&mut self) {
        self.frames = (0..self.frame_count).map(|_| vec![None; NUM_CHUNKS]).collect();
        self.received_count = 0;
    }

    fn is_complete(&self) -> bool { self.received_count == NUM_CHUNKS * self.frame_count }

    fn to_blob(&self) -> Vec<u8> {
        // Wire format packs pixels as big-endian u32 groups; the display reads
        // stored bytes as little-endian u32s. Byte-swap each 4-byte group so
        // the stored bytes match what the display expects. See image.rs for
        // the full rationale.
        let mut out = vec![0u8; self.frame_count * FRAME_BYTES];
        for (fi, frame) in self.frames.iter().enumerate() {
            for (ci, slot) in frame.iter().enumerate() {
                if let Some(bytes) = slot {
                    let base = fi * FRAME_BYTES + ci * CHUNK_DATA_SIZE;
                    let mut i = 0;
                    while i < CHUNK_DATA_SIZE {
                        out[base + i]     = bytes[i + 3];
                        out[base + i + 1] = bytes[i + 2];
                        out[base + i + 2] = bytes[i + 1];
                        out[base + i + 3] = bytes[i];
                        i += 4;
                    }
                }
            }
        }
        out
    }
}

impl<'a> ShellCmdApi<'a> for ImageDc {
    cmd_api!(imagedc);

    fn process(&mut self, args: String, _env: &mut CommonEnv) -> Result<Option<String>, xous::Error> {
        let mut ret = String::new();
        let trimmed = args.trim();

        if trimmed == "clear" {
            self.pddb.delete_key(DC34_DICT, DC34_IMAGE_DEFCON, None).ok();
            self.frame_count = 1;
            self.reset_frames();
            let conn = _env.xns.request_connection_blocking("_Vault2_").unwrap();
            xous::send_message(conn, xous::Message::new_scalar(1028, 0, 0, 0, 0)).ok();
            write!(ret, "CLEAR").unwrap();
            return Ok(Some(ret));
        }

        if let Some(rest) = trimmed.strip_prefix("frames ") {
            match rest.trim().parse::<usize>() {
                Ok(n) if (1..=MAX_FRAMES).contains(&n) => {
                    self.frame_count = n;
                    self.reset_frames();
                    let _ = n;
                    write!(ret, "OK").unwrap();
                }
                _ => {
                    write!(ret, "ERR frames must be 1..{}", MAX_FRAMES).unwrap();
                }
            }
            return Ok(Some(ret));
        }

        let b64 = trimmed;
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

        let frame_idx = decoded[0] as usize;
        let chunk_idx = decoded[1] as usize;
        let received_crc = u32::from_be_bytes([decoded[66], decoded[67], decoded[68], decoded[69]]);
        let computed_crc = crc32fast::hash(&decoded[..CHUNK_INDEX_BYTES + CHUNK_DATA_SIZE]);
        if computed_crc != received_crc {
            write!(ret, "ERR").unwrap();
            return Ok(Some(ret));
        }
        if chunk_idx >= NUM_CHUNKS {
            write!(ret, "ERR bad_chunk").unwrap();
            return Ok(Some(ret));
        }
        if frame_idx >= self.frame_count {
            write!(ret, "ERR bad_frame (send `imagedc frames {}` first)", frame_idx + 1).unwrap();
            return Ok(Some(ret));
        }

        let mut data_arr = [0u8; CHUNK_DATA_SIZE];
        data_arr.copy_from_slice(&decoded[CHUNK_INDEX_BYTES..CHUNK_INDEX_BYTES + CHUNK_DATA_SIZE]);
        let was_empty = self.frames[frame_idx][chunk_idx].is_none();
        self.frames[frame_idx][chunk_idx] = Some(data_arr);
        if was_empty {
            self.received_count += 1;
        }

        if self.is_complete() {
            let blob = self.to_blob();
            self.pddb.delete_key(DC34_DICT, DC34_IMAGE_DEFCON, None).ok();
            if let Ok(mut k) = self.pddb.get(
                DC34_DICT,
                DC34_IMAGE_DEFCON,
                None,
                true,
                true,
                Some(blob.len()),
                None::<fn()>,
            ) {
                let _ = k.write_all(&blob);
            }
            let _stored = self.frame_count;
            self.frame_count = 1;
            self.reset_frames();
            let conn = _env.xns.request_connection_blocking("_Vault2_").unwrap();
            xous::send_message(conn, xous::Message::new_scalar(1028, 1, 0, 0, 0)).ok();
            // Reply MUST be exactly "SUCCESS" (see image.rs for rationale).
            write!(ret, "SUCCESS").unwrap();
        } else {
            write!(ret, "OK").unwrap();
        }

        Ok(Some(ret))
    }
}
