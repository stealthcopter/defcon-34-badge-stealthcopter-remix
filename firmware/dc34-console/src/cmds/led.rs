// led.rs — LED preset control over USB-serial REPL, with PDDB persistence.
//
// Sends LedManagerOp::Force blocking-scalar messages to the LED_SERVER carrying
// a 16-byte padded `Haploid` phenotype. Successful presets are persisted to
// PDDB (DC34_DICT/led_preset) so the vault can restore them at boot.
//
// Subcommands:
//   led rainbow                 → full hue wheel, moving
//   led disco                   → multi-band rainbow, fast rotation (party mode)
//   led hue <base> <bound>      → arbitrary hue-range slice (both 0-255)
//   led solid   <RRGGBB>        → single hue, ~0.6s cycle brightness (looks flashy)
//   led flash   <RRGGBB>        → alias for `solid` (same phenotype)
//   led breathe <RRGGBB>        → single hue, ~7s slow fade in/out
//   led rotate  <RRGGBB>        → single hue, brightness peaks spinning around the ring
//   led revert                  → wipe persisted preset, restore rainbow default
//   led force <32-hex>          → raw 16-byte Haploid, advanced/debug
//
// Reply: "OK" on success, "ERR <reason>" on parse/transport failure.
//
// Notes on the animation model (see bio/lightgenes/main.c:do_lightgene):
//   Brightness is `127 * (1 + cos(spacetime))` where
//     space = 2π * cd_period * i / (count-1)     — spatial phase per LED
//     time  = 2π * indextime / tau(cd_rate)      — temporal phase, sawtooth
//     spacetime = space + time  (cd_dir > 128) or space - time (else)
//   So there is *always* time variation — a truly-static solid isn't possible
//   with this coprocessor. `solid`/`flash` is short-period pulse, `breathe`
//   is long-period, `rotate` combines both to spin a bright band.

use core::fmt::Write;
use std::io::Write as _;

use dc34_api::{DC34_DICT, DC34_LED_PRESET, Haploid, LED_SERVER, LedManagerOp};
use num_traits::ToPrimitive;
use pddb::Pddb;

use crate::{CommonEnv, ShellCmdApi};

pub struct Led {
    conn: Option<xous::CID>,
    pddb: Pddb,
}

impl Led {
    pub fn new() -> Self { Led { conn: None, pddb: Pddb::new() } }

    fn conn(&mut self, xns: &xous_names::XousNames) -> xous::CID {
        if let Some(c) = self.conn {
            c
        } else {
            let c = xns.request_connection_blocking(LED_SERVER).unwrap();
            self.conn = Some(c);
            c
        }
    }

    fn send_force(&mut self, phenotype: Haploid, xns: &xous_names::XousNames) -> Result<(), String> {
        let cid = self.conn(xns);
        let args = phenotype.serialize_u32();
        // Must be a BLOCKING scalar: the LED server's Force handler uses
        // `body.scalar_message_mut()`, which xous defines to return None for
        // Message::Scalar and Some only for Message::BlockingScalar. Sending a
        // non-blocking scalar makes the handler silently no-op.
        xous::send_message(
            cid,
            xous::Message::new_blocking_scalar(
                LedManagerOp::Force.to_usize().unwrap(),
                args[0] as usize,
                args[1] as usize,
                args[2] as usize,
                args[3] as usize,
            ),
        )
        .map_err(|e| format!("send failed: {:?}", e))?;
        Ok(())
    }

    /// Persist the last-applied preset so the vault can restore it at boot.
    /// Writes exactly 16 bytes (padded serialization of the Haploid).
    fn persist(&self, phenotype: &Haploid) {
        let args = phenotype.serialize_u32();
        let mut bytes = [0u8; 16];
        for (i, word) in args.iter().enumerate() {
            bytes[i * 4..(i + 1) * 4].copy_from_slice(&word.to_le_bytes());
        }
        if let Ok(mut key) =
            self.pddb.get(DC34_DICT, DC34_LED_PRESET, None, true, true, Some(16), None::<fn()>)
        {
            let _ = key.write_all(&bytes);
        }
    }

    fn delete_persist(&self) { let _ = self.pddb.delete_key(DC34_DICT, DC34_LED_PRESET, None); }

    /// Ship + save in one call.
    fn apply_and_persist(&mut self, p: Haploid, xns: &xous_names::XousNames) -> Result<(), String> {
        self.send_force(p, xns)?;
        self.persist(&p);
        Ok(())
    }
}

/// Convert an sRGB triplet (0-255 per channel) to (hue, sat) in 0-255 units.
/// Hue matches Haploid's 0-255 hue-wheel convention (0=red, ~85=green, ~170=blue).
fn rgb_to_hue_sat(r: u8, g: u8, b: u8) -> (u8, u8) {
    let r = r as i32;
    let g = g as i32;
    let b = b as i32;
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let d = max - min;
    let sat = if max == 0 { 0 } else { ((255 * d) / max) as u8 };
    if d == 0 {
        return (0, sat);
    }
    let h_deg = if max == r {
        (60 * (g - b)) / d
    } else if max == g {
        (60 * (b - r)) / d + 120
    } else {
        (60 * (r - g)) / d + 240
    };
    let h_deg = ((h_deg % 360) + 360) % 360;
    let hue = ((h_deg * 255) / 360) as u8;
    (hue, sat)
}

fn parse_hex_u8(s: &str) -> Option<u8> { u8::from_str_radix(s, 16).ok() }

fn parse_rrggbb(s: &str) -> Option<(u8, u8, u8)> {
    let s = s.trim().trim_start_matches('#');
    if s.len() != 6 {
        return None;
    }
    Some((parse_hex_u8(&s[0..2])?, parse_hex_u8(&s[2..4])?, parse_hex_u8(&s[4..6])?))
}

pub fn rainbow_phenotype() -> Haploid {
    Haploid {
        cd_period: 1,
        cd_rate: 64,
        cd_dir: 128,
        sat: 255,
        hue_ratedir: 2,
        hue_base: 0,
        hue_bound: 255,
        chaser: 255,
        nonlin: 128,
    }
}

/// `led disco` — full hue wheel with rapid spatial period AND fast rotation.
/// Multiple color bands spinning aggressively for a "club/party" look.
fn disco_phenotype() -> Haploid {
    Haploid {
        cd_period: 4,      // 4 hue peaks around the ring for chunky bands
        cd_rate: 220,      // fast rotation
        cd_dir: 200,       // clockwise
        sat: 255,          // punchy saturation
        hue_ratedir: 10,   // fast hue cycling too
        hue_base: 0,
        hue_bound: 255,    // full spectrum
        chaser: 255,
        nonlin: 128,
    }
}

/// `led solid` — cd_period=0 + cd_rate=0 gives tau=60 (~0.6 s brightness cycle).
/// Called "flash" by the user; that's what it looks like on the ring.
fn flash_phenotype(hue: u8, sat: u8) -> Haploid {
    Haploid {
        cd_period: 0,
        cd_rate: 0,
        cd_dir: 128,
        sat,
        hue_ratedir: 0,
        hue_base: hue,
        hue_bound: hue,
        chaser: 255,
        nonlin: 128,
    }
}

/// `led breathe` — single hue, slow fade in/out.
///
/// With `cd_period = 0` every LED updates in lockstep and the whole ring
/// changes brightness by an integer step each frame, which reads as visible
/// stutter at low frame rates. Setting `cd_period = 1` gives each LED a small
/// phase offset around the ring (2π * i / count) so at any moment adjacent
/// LEDs are on slightly different brightness values — the eye integrates that
/// spatial gradient into a smoother breath. `cd_rate = 128` picks the middle
/// of the tau map (tau ≈ 380 → ~3.8 s period), which pairs well with the
/// phase-spread. Faster than the old 7 s but noticeably smoother.
fn breathe_phenotype(hue: u8, sat: u8) -> Haploid {
    Haploid {
        cd_period: 1,      // small spatial period = phase dither across ring
        cd_rate: 128,      // mid-range temporal rate
        cd_dir: 128,
        sat,
        hue_ratedir: 0,
        hue_base: hue,
        hue_bound: hue,
        chaser: 255,
        nonlin: 128,
    }
}

/// `led rotate` — cd_period > 0 gives spatial brightness peaks; cd_rate > 0
/// makes them rotate. Same hue everywhere.
fn rotate_phenotype(hue: u8, sat: u8) -> Haploid {
    Haploid {
        cd_period: 2,
        cd_rate: 96,
        cd_dir: 200,   // > 128 → clockwise
        sat,
        hue_ratedir: 0,
        hue_base: hue,
        hue_bound: hue,
        chaser: 255,
        nonlin: 128,
    }
}

fn hue_range_phenotype(base: u8, bound: u8) -> Haploid {
    Haploid {
        cd_period: 1,
        cd_rate: 64,
        cd_dir: 128,
        sat: 255,
        hue_ratedir: 2,
        hue_base: base.min(bound),
        hue_bound: base.max(bound),
        chaser: 255,
        nonlin: 128,
    }
}

/// Small helper: read a hex color from the arg iterator and produce a phenotype
/// using the given constructor.
fn parse_color_effect<F>(
    parts: &mut core::str::SplitWhitespace,
    ret: &mut String,
    build: F,
) -> Option<Haploid>
where
    F: Fn(u8, u8) -> Haploid,
{
    let hex = match parts.next() {
        Some(h) => h,
        None => {
            let _ = write!(ret, "ERR missing <RRGGBB>");
            return None;
        }
    };
    let (r, g, b) = match parse_rrggbb(hex) {
        Some(rgb) => rgb,
        None => {
            let _ = write!(ret, "ERR bad hex color");
            return None;
        }
    };
    let (hue, sat) = rgb_to_hue_sat(r, g, b);
    Some(build(hue, sat))
}

impl<'a> ShellCmdApi<'a> for Led {
    cmd_api!(led);

    fn process(&mut self, args: String, env: &mut CommonEnv) -> Result<Option<String>, xous::Error> {
        let mut ret = String::new();
        let mut parts = args.split_whitespace();
        let sub = match parts.next() {
            Some(s) => s,
            None => {
                write!(
                    ret,
                    "ERR usage: led {{rainbow|disco|hue <base> <bound>|solid <RRGGBB>|flash <RRGGBB>|breathe <RRGGBB>|rotate <RRGGBB>|force <32-hex>|revert}}"
                )
                .ok();
                return Ok(Some(ret));
            }
        };

        let xns = xous_names::XousNames::new().unwrap();

        let result: Result<(), String> = match sub {
            "rainbow" => self.apply_and_persist(rainbow_phenotype(), &xns),
            "disco" => self.apply_and_persist(disco_phenotype(), &xns),
            "solid" | "flash" => match parse_color_effect(&mut parts, &mut ret, flash_phenotype) {
                Some(p) => self.apply_and_persist(p, &xns),
                None => return Ok(Some(ret)),
            },
            "breathe" => match parse_color_effect(&mut parts, &mut ret, breathe_phenotype) {
                Some(p) => self.apply_and_persist(p, &xns),
                None => return Ok(Some(ret)),
            },
            "rotate" => match parse_color_effect(&mut parts, &mut ret, rotate_phenotype) {
                Some(p) => self.apply_and_persist(p, &xns),
                None => return Ok(Some(ret)),
            },
            "hue" => {
                let base = parts.next().and_then(|s| s.parse::<u8>().ok());
                let bound = parts.next().and_then(|s| s.parse::<u8>().ok());
                match (base, bound) {
                    (Some(b1), Some(b2)) => self.apply_and_persist(hue_range_phenotype(b1, b2), &xns),
                    _ => {
                        write!(ret, "ERR usage: led hue <base 0-255> <bound 0-255>").ok();
                        return Ok(Some(ret));
                    }
                }
            }
            "force" => {
                let hex = match parts.next() {
                    Some(h) => h,
                    None => {
                        write!(ret, "ERR usage: led force <32-hex-chars>").ok();
                        return Ok(Some(ret));
                    }
                };
                if hex.len() != 32 {
                    write!(ret, "ERR force expects exactly 32 hex chars (16 bytes)").ok();
                    return Ok(Some(ret));
                }
                let mut bytes = [0u8; 16];
                for i in 0..16 {
                    match parse_hex_u8(&hex[i * 2..i * 2 + 2]) {
                        Some(b) => bytes[i] = b,
                        None => {
                            write!(ret, "ERR bad hex at byte {}", i).ok();
                            return Ok(Some(ret));
                        }
                    }
                }
                match Haploid::deserialize(&bytes[..std::mem::size_of::<Haploid>()]) {
                    Some(p) => self.apply_and_persist(p, &xns),
                    None => {
                        write!(ret, "ERR could not deserialize Haploid").ok();
                        return Ok(Some(ret));
                    }
                }
            }
            "revert" => {
                // Wipe persisted preset so next boot uses default, and force
                // rainbow immediately so the ring changes right now.
                self.delete_persist();
                self.send_force(rainbow_phenotype(), &xns)
            }
            other => {
                write!(ret, "ERR unknown subcommand: {}", other).ok();
                return Ok(Some(ret));
            }
        };

        match result {
            Ok(()) => write!(ret, "OK").ok(),
            Err(e) => write!(ret, "ERR {}", e).ok(),
        };
        let _ = env;
        Ok(Some(ret))
    }
}
