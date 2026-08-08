// led.rs — Phase 3 LED preset control over USB-serial REPL.
//
// Sends LedManagerOp::Force scalar messages to the LED_SERVER with a computed
// Haploid phenotype (9 bytes: cd_period, cd_rate, cd_dir, sat, hue_ratedir,
// hue_base, hue_bound, chaser, nonlin). Presets computed host-side.
//
// Subcommands:
//   led rainbow                 → full hue wheel, rotating
//   led solid <RRGGBB>          → single color across the ring
//   led hue <base> <bound>      → arbitrary hue-range slice (both 0-255)
//   led force <32-hex>          → raw 16-byte Haploid, hex-encoded
//   led revert                  → re-express the stored gene (goes back to default,
//                                 which is rainbow in the current build)
//
// Replies: "OK" on success, "ERR <reason>" on parse/transport failure.
//
// Notes:
//   - Force is *volatile*. A QR-code gene mating (which triggers Syngamy on the
//     LED server) will re-express the stored gene and clobber the preset. In the
//     current build the default expression is also rainbow (Diploid::phenotype
//     override in dc34-api), so post-mating you'll snap back to rainbow. Send
//     the desired preset again to restore it.
//   - Force does NOT persist across reboot. The vault sends SetGene at boot,
//     which triggers express() → rainbow (current default). Send preset again
//     after reboot if desired.

use core::fmt::Write;

use dc34_api::{Haploid, LedManagerOp, LED_SERVER};
use num_traits::ToPrimitive;

use crate::{CommonEnv, ShellCmdApi};

pub struct Led {
    conn: Option<xous::CID>,
}

impl Led {
    pub fn new() -> Self { Led { conn: None } }

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
        xous::send_message(
            cid,
            xous::Message::new_scalar(
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
    // hue in degrees 0-360
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

fn rainbow_phenotype() -> Haploid {
    Haploid {
        cd_period: 1,      // one hue spread around the ring
        cd_rate: 64,       // moderate rotation
        cd_dir: 128,       // clockwise
        sat: 255,          // full saturation
        hue_ratedir: 2,    // slow hue cycle (mod 14)
        hue_base: 0,       // start of hue wheel
        hue_bound: 255,    // end of hue wheel
        chaser: 255,       // large = chaser disabled
        nonlin: 128,       // linear-ish brightness
    }
}

fn solid_phenotype(hue: u8, sat: u8) -> Haploid {
    Haploid {
        cd_period: 0,      // no spatial hue differential
        cd_rate: 0,        // static, no rotation
        cd_dir: 128,
        sat,
        hue_ratedir: 0,    // no hue cycling
        hue_base: hue,
        hue_bound: hue,    // single hue only
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

impl<'a> ShellCmdApi<'a> for Led {
    cmd_api!(led);

    fn process(&mut self, args: String, env: &mut CommonEnv) -> Result<Option<String>, xous::Error> {
        let mut ret = String::new();
        let mut parts = args.split_whitespace();
        let sub = match parts.next() {
            Some(s) => s,
            None => {
                write!(ret, "ERR usage: led {{rainbow|solid <RRGGBB>|hue <base> <bound>|force <32-hex>|revert}}")
                    .ok();
                return Ok(Some(ret));
            }
        };

        // Build a fresh xous_names handle (cheap) for the LED_SERVER lookup.
        let xns = xous_names::XousNames::new().unwrap();

        match sub {
            "rainbow" => match self.send_force(rainbow_phenotype(), &xns) {
                Ok(()) => write!(ret, "OK").ok(),
                Err(e) => write!(ret, "ERR {}", e).ok(),
            },
            "solid" => {
                let hex = match parts.next() {
                    Some(h) => h,
                    None => {
                        write!(ret, "ERR usage: led solid <RRGGBB>").ok();
                        return Ok(Some(ret));
                    }
                };
                let (r, g, b) = match parse_rrggbb(hex) {
                    Some(rgb) => rgb,
                    None => {
                        write!(ret, "ERR bad hex color").ok();
                        return Ok(Some(ret));
                    }
                };
                let (hue, sat) = rgb_to_hue_sat(r, g, b);
                match self.send_force(solid_phenotype(hue, sat), &xns) {
                    Ok(()) => write!(ret, "OK").ok(),
                    Err(e) => write!(ret, "ERR {}", e).ok(),
                }
            }
            "hue" => {
                let base = parts.next().and_then(|s| s.parse::<u8>().ok());
                let bound = parts.next().and_then(|s| s.parse::<u8>().ok());
                match (base, bound) {
                    (Some(b1), Some(b2)) => match self.send_force(hue_range_phenotype(b1, b2), &xns) {
                        Ok(()) => write!(ret, "OK").ok(),
                        Err(e) => write!(ret, "ERR {}", e).ok(),
                    },
                    _ => {
                        write!(ret, "ERR usage: led hue <base 0-255> <bound 0-255>").ok()
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
                let phenotype = match Haploid::deserialize(&bytes[..std::mem::size_of::<Haploid>()]) {
                    Some(p) => p,
                    None => {
                        write!(ret, "ERR could not deserialize Haploid").ok();
                        return Ok(Some(ret));
                    }
                };
                match self.send_force(phenotype, &xns) {
                    Ok(()) => write!(ret, "OK").ok(),
                    Err(e) => write!(ret, "ERR {}", e).ok(),
                }
            }
            "revert" => {
                // In the current build, "default" is rainbow (Diploid::phenotype
                // override), so revert == rainbow. When Phase 3 is expanded to give
                // presets first-class persistent status, this should re-express the
                // stored gene from the LED server instead.
                match self.send_force(rainbow_phenotype(), &xns) {
                    Ok(()) => write!(ret, "OK").ok(),
                    Err(e) => write!(ret, "ERR {}", e).ok(),
                }
            }
            other => {
                write!(ret, "ERR unknown subcommand: {}", other).ok()
            }
        };

        let _ = env;
        Ok(Some(ret))
    }
}
