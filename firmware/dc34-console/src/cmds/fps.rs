// fps.rs — REPL command that sets the badge's animation frame rate.
//
// Usage:  fps <N>            where N is 1..=30
// Reply:  OK                 on success
//         ERR [reason]       on parse/clamp failure or transport failure
//
// The command forwards to the vault process via a scalar message on the
// SERVER_NAME_VAULT2 connection (opcode 1030 = VaultOp::SetAnimFps). The
// vault clamps the value, updates the shared ANIM_FRAME_MS atomic (used by
// both the redraw path and the pumper sleep interval), and persists to PDDB
// so the setting survives reboots.

use core::fmt::Write;

use crate::{CommonEnv, ShellCmdApi};

pub struct Fps;

impl Fps {
    pub fn new() -> Self { Fps }
}

impl<'a> ShellCmdApi<'a> for Fps {
    cmd_api!(fps);

    fn process(&mut self, args: String, env: &mut CommonEnv) -> Result<Option<String>, xous::Error> {
        let mut ret = String::new();
        let trimmed = args.trim();
        let n: u32 = match trimmed.parse() {
            Ok(v) => v,
            Err(_) => {
                write!(ret, "ERR usage: fps <1..30>").ok();
                return Ok(Some(ret));
            }
        };
        if !(1..=30).contains(&n) {
            write!(ret, "ERR fps out of range (1..30)").ok();
            return Ok(Some(ret));
        }
        let conn = match env.xns.request_connection_blocking("_Vault2_") {
            Ok(c) => c,
            Err(_) => {
                write!(ret, "ERR could not reach vault").ok();
                return Ok(Some(ret));
            }
        };
        // Opcode 1030 = VaultOp::SetAnimFps; arg1 carries the fps value.
        // Non-blocking scalar: handler is fire-and-forget, no reply needed.
        let sent = xous::send_message(
            conn,
            xous::Message::new_scalar(1030, n as usize, 0, 0, 0),
        );
        match sent {
            Ok(_) => write!(ret, "OK").ok(),
            Err(e) => write!(ret, "ERR send failed: {:?}", e).ok(),
        };
        Ok(Some(ret))
    }
}
