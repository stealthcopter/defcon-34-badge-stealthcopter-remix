// image.rs — REPL command that receives one or more 128×128 mono frames over
// serial. Multi-frame images animate on the badge (~4 fps, one frame per
// vault pumper tick).
//
// Wire format per chunk (70 bytes, before base64):
//   [0]      u8   frame index (0..N-1)
//   [1]      u8   chunk index inside the frame (0..31)
//   [2..66]  u8*64 pixel data
//   [66..70] u32  CRC-32 of bytes [0..66] (big-endian)
//
// The first two bytes used to be a big-endian u16 chunk index whose high byte
// was always 0. Reinterpreting the high byte as `frame_idx` is 100%
// backward-compatible: legacy single-frame clients keep working as frame 0.
//
// Subcommands:
//   image clear             — wipe stored image (any frame count)
//   image frames <N>        — declare next upload has N frames (1..8), resets
//                             the receive buffer
//   image <base64-chunk>    — 70-byte chunk (frame_idx byte at position 0)
//
// Replies:
//   OK                — chunk accepted, not yet complete
//   ERR [reason]      — bad base64, wrong length, CRC mismatch, or bad indices
//   CLEAR             — clear accepted
//   SUCCESS frames=N  — all chunks across all frames received, PDDB updated

use core::fmt::Write;
use std::io::Write as FsWrite;

use base64::{Engine as _, engine::general_purpose::STANDARD as B64};
use dc34_api::{DC34_DICT, DC34_IMAGE};
use pddb::Pddb;

use crate::{CommonEnv, ShellCmdApi};

const CHUNK_DATA_SIZE: usize = 64;
const CHUNK_INDEX_BYTES: usize = 2;
const CHUNK_CRC_BYTES: usize = 4;
const CHUNK_WIRE_SIZE: usize = CHUNK_INDEX_BYTES + CHUNK_DATA_SIZE + CHUNK_CRC_BYTES; // 70
const NUM_CHUNKS: usize = 32;
const FRAME_BYTES: usize = 2048;
pub(crate) const MAX_FRAMES: usize = 32;

pub struct Image {
    /// One vector per frame; each frame is [None; 32] until chunks arrive.
    frames: Vec<Vec<Option<[u8; CHUNK_DATA_SIZE]>>>,
    received_count: usize,
    frame_count: usize,
    pddb: Pddb,
}

impl Image {
    pub fn new() -> Self {
        let mut s = Image { frames: Vec::new(), received_count: 0, frame_count: 1, pddb: Pddb::new() };
        s.reset_frames();
        s
    }

    fn reset_frames(&mut self) {
        self.frames = (0..self.frame_count).map(|_| vec![None; NUM_CHUNKS]).collect();
        self.received_count = 0;
    }

    fn is_complete(&self) -> bool { self.received_count == NUM_CHUNKS * self.frame_count }

    /// Pack all received chunks into one `frame_count * 2048` byte blob for PDDB.
    ///
    /// The wire format packs pixels as big-endian u32 groups (see the sending
    /// end in `dc34-image` / the Android `ImagePacker`). The display driver
    /// consumes the stored bytes as little-endian u32s via `bytemuck::cast_slice`.
    /// So we byte-swap each 4-byte group as we pack — this matches what the
    /// original single-frame code did implicitly (u32::from_be_bytes → write
    /// bytemuck-native u32).
    fn to_blob(&self) -> Vec<u8> {
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

impl<'a> ShellCmdApi<'a> for Image {
    cmd_api!(image);

    fn process(&mut self, args: String, _env: &mut CommonEnv) -> Result<Option<String>, xous::Error> {
        let mut ret = String::new();
        let trimmed = args.trim();

        // --- `image clear` ---------------------------------------------------
        if trimmed == "clear" {
            self.pddb.delete_key(DC34_DICT, DC34_IMAGE, None).ok();
            self.frame_count = 1;
            self.reset_frames();
            let conn = _env.xns.request_connection_blocking("_Vault2_").unwrap();
            xous::send_message(conn, xous::Message::new_scalar(1024, 0, 0, 0, 0)).ok();
            write!(ret, "CLEAR").unwrap();
            return Ok(Some(ret));
        }

        // --- `image frames <N>` ----------------------------------------------
        if let Some(rest) = trimmed.strip_prefix("frames ") {
            match rest.trim().parse::<usize>() {
                Ok(n) if (1..=MAX_FRAMES).contains(&n) => {
                    self.frame_count = n;
                    self.reset_frames();
                    // Bare "OK" so the Android client's line matcher accepts it.
                    let _ = n;
                    write!(ret, "OK").unwrap();
                }
                _ => {
                    write!(ret, "ERR frames must be 1..{}", MAX_FRAMES).unwrap();
                }
            }
            return Ok(Some(ret));
        }

        // --- Otherwise, treat as a base64-encoded chunk ----------------------
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
            write!(ret, "ERR bad_frame (send `image frames {}` first)", frame_idx + 1).unwrap();
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
            // Delete + recreate so the key gets sized correctly for a possibly
            // different frame count than what's currently stored.
            self.pddb.delete_key(DC34_DICT, DC34_IMAGE, None).ok();
            if let Ok(mut k) = self.pddb.get(
                DC34_DICT,
                DC34_IMAGE,
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
            xous::send_message(conn, xous::Message::new_scalar(1024, 1, 0, 0, 0)).ok();
            // Reply MUST be exactly "SUCCESS" (with no suffix). The Android
            // client's serial reader matches with `line == "SUCCESS" ||
            // line.endsWith("SUCCESS")` — adding "frames=N" fails both.
            write!(ret, "SUCCESS").unwrap();
        } else {
            write!(ret, "OK").unwrap();
        }

        Ok(Some(ret))
    }
}
