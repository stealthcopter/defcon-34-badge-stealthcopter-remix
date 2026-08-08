package com.stealthcopter.dc34flasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Minimal USB CDC-ACM (virtual serial) transport built directly on the
 * Android USB Host API — no third-party serial library.
 *
 * The DC34 badge (Baochip Baosec-lite, VID 0x1d50 / PID 0x6198) enumerates as
 * a composite device whose CDC function has:
 *   - a Communications interface (class 0x02, subclass 0x02 ACM) with an
 *     interrupt-IN notification endpoint, and
 *   - a CDC-Data interface (class 0x0A) with bulk-IN and bulk-OUT endpoints.
 *
 * All log/state output is pushed through [log] so the UI can render it live.
 */
class CdcAcmSerial(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val log: (String) -> Unit
) {

    companion object {
        const val BAUD_RATE = 1_000_000

        // USB CDC class request constants (bmRequestType 0x21 = host->device | class | interface)
        private const val REQTYPE_HOST_TO_DEVICE_CLASS_INTERFACE = 0x21
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22

        const val USB_CLASS_COMM = 0x02
        const val USB_CLASS_CDC_DATA = 0x0A
    }

    private var connection: UsbDeviceConnection? = null
    private var commInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var controlIfaceIndex: Int = 0

    /** Leftover bytes read past a newline, carried to the next readLine(). */
    private val readCarry = StringBuilder()

    /**
     * When true, every raw RX burst read from the bulk-IN endpoint is dumped
     * (hex + printable ASCII) through [log]. This is the ground-truth diagnostic
     * for "why did a command get no response": it shows the *exact* bytes the
     * badge sent — including sub-line fragments, bare CRs, prompts, or nothing
     * at all — that the line-oriented readers would otherwise hide.
     */
    @Volatile
    var verboseRx: Boolean = false

    val isOpen: Boolean get() = connection != null

    /** Human-readable description used in logs. */
    fun describeDevice(): String = buildString {
        append("name=${device.deviceName} ")
        append("VID=0x%04x PID=0x%04x ".format(device.vendorId, device.productId))
        append("mfr=${device.manufacturerName ?: "?"} product=${device.productName ?: "?"} ")
        append("class=${device.deviceClass} interfaces=${device.interfaceCount}")
    }

    /** Enumerate and log every interface + endpoint on the device. */
    fun dumpTopology() {
        log("── Device topology ──")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            log("  iface[$i] id=${intf.id} class=0x%02x sub=0x%02x proto=0x%02x eps=${intf.endpointCount}"
                .format(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol))
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INT "
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                    else -> "?"
                }
                log("      ep[$e] addr=0x%02x $dir $type maxPkt=${ep.maxPacketSize}".format(ep.address))
            }
        }
    }

    /**
     * Open the connection, locate + claim the CDC data interface, configure the
     * line coding and raise DTR/RTS. Returns true on success.
     */
    fun open(): Boolean {
        dumpTopology()

        // Locate the CDC-Data interface (bulk endpoints) and the Comm interface.
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                USB_CLASS_CDC_DATA -> if (dataInterface == null) dataInterface = intf
                USB_CLASS_COMM -> if (commInterface == null) commInterface = intf
            }
        }
        // Fallback: some stacks report the data interface with a vendor/other class
        // but it is still the one carrying two bulk endpoints.
        if (dataInterface == null) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (findBulk(intf, UsbConstants.USB_DIR_IN) != null &&
                    findBulk(intf, UsbConstants.USB_DIR_OUT) != null
                ) {
                    dataInterface = intf
                    log("Using iface id=${intf.id} (class 0x%02x) as data interface (bulk pair found)"
                        .format(intf.interfaceClass))
                    break
                }
            }
        }

        val data = dataInterface
        if (data == null) {
            log("ERROR: no CDC-Data interface with a bulk endpoint pair was found")
            return false
        }

        bulkIn = findBulk(data, UsbConstants.USB_DIR_IN)
        bulkOut = findBulk(data, UsbConstants.USB_DIR_OUT)
        if (bulkIn == null || bulkOut == null) {
            log("ERROR: data interface is missing a bulk IN or OUT endpoint")
            return false
        }

        val conn = manager.openDevice(device)
        if (conn == null) {
            log("ERROR: UsbManager.openDevice() returned null (permission or in-use?)")
            return false
        }
        connection = conn
        log("Opened device connection (fd=${conn.fileDescriptor})")

        // Claim the comm interface (if present) then the data interface.
        commInterface?.let {
            val ok = conn.claimInterface(it, true)
            log("claimInterface(comm id=${it.id}) -> $ok")
            controlIfaceIndex = it.id
        }
        val dataClaimed = conn.claimInterface(data, true)
        log("claimInterface(data id=${data.id}) -> $dataClaimed")
        if (!dataClaimed) {
            log("ERROR: could not claim data interface")
            close()
            return false
        }

        // Configure the virtual UART: 1,000,000 baud, 8N1, then raise DTR|RTS.
        setLineCoding(BAUD_RATE)
        setControlLineState(dtr = true, rts = true)
        readCarry.setLength(0)
        return true
    }

    private fun findBulk(intf: UsbInterface, direction: Int): UsbEndpoint? {
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction) return ep
        }
        return null
    }

    /** CDC SET_LINE_CODING: dwDTERate, 1 stop bit, no parity, 8 data bits. */
    fun setLineCoding(baud: Int) {
        val conn = connection ?: return
        val data = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud ushr 8) and 0xFF).toByte(),
            ((baud ushr 16) and 0xFF).toByte(),
            ((baud ushr 24) and 0xFF).toByte(),
            0x00, // bCharFormat: 1 stop bit
            0x00, // bParityType: none
            0x08  // bDataBits: 8
        )
        val r = conn.controlTransfer(
            REQTYPE_HOST_TO_DEVICE_CLASS_INTERFACE, SET_LINE_CODING,
            0, controlIfaceIndex, data, data.size, 2000
        )
        log("SET_LINE_CODING baud=$baud 8N1 -> ret=$r  [${hex(data)}]")
    }

    /** CDC SET_CONTROL_LINE_STATE: bit0 DTR, bit1 RTS. */
    fun setControlLineState(dtr: Boolean, rts: Boolean) {
        val conn = connection ?: return
        val value = (if (dtr) 0x01 else 0) or (if (rts) 0x02 else 0)
        val r = conn.controlTransfer(
            REQTYPE_HOST_TO_DEVICE_CLASS_INTERFACE, SET_CONTROL_LINE_STATE,
            value, controlIfaceIndex, null, 0, 2000
        )
        log("SET_CONTROL_LINE_STATE dtr=$dtr rts=$rts (0x%02x) -> ret=$r".format(value))
    }

    /** Write raw bytes to the bulk-OUT endpoint. Returns bytes written or -1. */
    fun write(bytes: ByteArray, timeoutMs: Int = 2000): Int {
        val conn = connection ?: return -1
        val ep = bulkOut ?: return -1
        var offset = 0
        var total = 0
        while (offset < bytes.size) {
            val len = minOf(bytes.size - offset, 4096)
            val slice = if (offset == 0 && len == bytes.size) bytes else bytes.copyOfRange(offset, offset + len)
            val n = conn.bulkTransfer(ep, slice, len, timeoutMs)
            if (n < 0) {
                log("  bulkTransfer(OUT) FAILED at offset=$offset -> $n")
                return if (total > 0) total else -1
            }
            total += n
            offset += n
            if (n < len) break
        }
        return total
    }

    /**
     * Read a single '\n'-terminated line from the bulk-IN endpoint, honouring an
     * overall [timeoutMs] deadline. Returns the trimmed line, or null on timeout.
     * Any bytes read past the newline are carried over for the next call.
     */
    fun readLine(timeoutMs: Int): String? {
        val conn = connection ?: return null
        val ep = bulkIn ?: return null

        // First, satisfy from carry-over if it already contains a full line.
        extractLine()?.let { return it }

        val buf = ByteArray(maxOf(ep.maxPacketSize, 64))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val n = conn.bulkTransfer(ep, buf, buf.size, minOf(remaining, 500))
            if (n > 0) {
                if (verboseRx) dumpRx(buf, n)
                val s = String(buf, 0, n, Charsets.US_ASCII)
                readCarry.append(s)
                extractLine()?.let { return it }
            }
            // n <= 0 simply means "nothing this poll" — keep looping until deadline.
        }
        return null
    }

    private fun extractLine(): String? {
        val nl = readCarry.indexOf("\n")
        if (nl < 0) return null
        val line = readCarry.substring(0, nl).trim { it <= ' ' }
        readCarry.delete(0, nl + 1)
        return line
    }

    /**
     * Discard every buffered / in-flight input byte until the bulk-IN stream has
     * been quiet for [quietMs] (or the overall [capMs] budget is exhausted), then
     * reset the line carry.
     *
     * The badge's CDC console emits bursts of asynchronous log output (e.g. the
     * "pddb mount" flood the vault app produces while rendering an image). On a
     * long-lived connection that noise accumulates ahead of the next command's
     * real response and desyncs the line reader — so we flush it before every
     * command to guarantee the next line we read belongs to *this* command.
     * Returns the number of bytes discarded.
     */
    fun drainInput(quietMs: Int = 200, capMs: Int = 3000): Int {
        val conn = connection ?: return 0
        val ep = bulkIn ?: return 0
        readCarry.setLength(0)
        val buf = ByteArray(maxOf(ep.maxPacketSize, 512))
        var discarded = 0
        val overallDeadline = System.currentTimeMillis() + capMs
        var lastData = System.currentTimeMillis()
        while (System.currentTimeMillis() < overallDeadline) {
            val n = conn.bulkTransfer(ep, buf, buf.size, 50)
            if (n > 0) {
                if (verboseRx) dumpRx(buf, n, prefix = "drain")
                discarded += n
                lastData = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastData >= quietMs) {
                break
            }
        }
        return discarded
    }

    /**
     * Read lines until one of [tokens] is seen or [timeoutMs] elapses, skipping
     * the badge's asynchronous console noise (boot/pddb log lines, "[console]"
     * command echoes, blank lines) along the way. Each skipped line is reported
     * through [onSkip] so the debug UI can still show it.
     *
     * A line matches a token when it *equals* it or *ends with* it — the latter
     * absorbs the case where the badge glues the response onto the tail of its
     * echoed command (`[console] image <b64>OK`). Chunk base64 always ends in
     * "==", so a bare echo never ends in a protocol token: no false positives.
     */
    fun awaitResponse(tokens: List<String>, timeoutMs: Int, onSkip: (String) -> Unit): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) return null
            val line = readLine(remaining) ?: return null
            if (line.isEmpty()) continue
            val match = tokens.firstOrNull { line == it || line.endsWith(it) }
            if (match != null) return match
            onSkip(line)
        }
    }

    /**
     * Collect every reply line the badge prints for a free-form console command
     * (`ver`, `echo`, `test …`) that has no single fixed response token.
     *
     * Two separate timeouts, because they answer different questions:
     *  - [firstLineTimeoutMs] — how long to wait for the *first* reply line to
     *    even start arriving. The badge routes console output through its Xous
     *    log server and can be busy (boot/pddb flush, image render), so a reply
     *    may not begin for several hundred ms. This must be generous — matching
     *    the upload path's patience — or a perfectly good reply is missed and
     *    reported as "no response". (The old code used a single 300 ms window
     *    here and bailed on the first quiet gap, which was the real cause of
     *    console commands looking dead while image upload worked.)
     *  - [quietMs] — once lines are flowing, how long a gap ends the burst.
     * [capMs] bounds the whole call.
     */
    fun readAvailableLines(
        firstLineTimeoutMs: Int = 2500,
        quietMs: Int = 400,
        capMs: Int = 5000
    ): List<String> {
        val lines = ArrayList<String>()
        val deadline = System.currentTimeMillis() + capMs
        // Wait patiently for the first line to appear.
        val first = readLine(minOf(firstLineTimeoutMs, capMs)) ?: return lines
        if (first.isNotEmpty()) lines.add(first)
        // Then drain the rest of the burst until it goes quiet.
        while (System.currentTimeMillis() < deadline) {
            val budget = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val line = readLine(minOf(budget, quietMs)) ?: break // quiet gap -> done
            if (line.isNotEmpty()) lines.add(line)
        }
        return lines
    }

    /**
     * Diagnostic: read raw bytes for up to [windowMs], stopping early once the
     * stream has been quiet for [quietMs], and return everything received as a
     * single byte array (empty if the badge sent nothing at all).
     *
     * Unlike [readAvailableLines] this does NOT require newlines — it captures
     * partial lines, prompts, and no-newline output too, so it can answer the
     * flat question "did the badge emit *any* bytes in response to that command?"
     * The line carry is reset first so only fresh bytes are captured.
     */
    fun captureRaw(windowMs: Int = 1500, quietMs: Int = 350): ByteArray {
        val conn = connection ?: return ByteArray(0)
        val ep = bulkIn ?: return ByteArray(0)
        readCarry.setLength(0)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(maxOf(ep.maxPacketSize, 512))
        val deadline = System.currentTimeMillis() + windowMs
        var lastData = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val n = conn.bulkTransfer(ep, buf, buf.size, 50)
            if (n > 0) {
                out.write(buf, 0, n)
                lastData = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastData >= quietMs) {
                break
            }
        }
        return out.toByteArray()
    }

    /** Dump a raw RX burst as hex plus a printable-ASCII gutter, via [log]. */
    private fun dumpRx(buf: ByteArray, n: Int, prefix: String = "rx") {
        val cap = minOf(n, 64)
        val hex = StringBuilder()
        val asc = StringBuilder()
        for (i in 0 until cap) {
            val v = buf[i].toInt() and 0xFF
            hex.append("%02x ".format(v))
            asc.append(if (v in 0x20..0x7e) v.toChar() else '.')
        }
        val more = if (n > cap) " …(+${n - cap}B)" else ""
        log("      RAW $prefix ${n}B: ${hex.toString().trim()}$more  |$asc|")
    }

    fun close() {
        val conn = connection
        if (conn != null) {
            try {
                setControlLineState(dtr = false, rts = false)
            } catch (_: Exception) {
            }
            commInterface?.let { conn.releaseInterface(it) }
            dataInterface?.let { conn.releaseInterface(it) }
            conn.close()
            log("Connection closed, interfaces released")
        }
        connection = null
        bulkIn = null
        bulkOut = null
        readCarry.setLength(0)
    }

    private fun hex(b: ByteArray): String =
        b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
