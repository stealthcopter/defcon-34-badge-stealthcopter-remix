package com.stealthcopter.dc34flasher

import android.graphics.Bitmap
import android.util.Base64
import java.util.zip.CRC32

/**
 * Faithful Kotlin port of bunnie/dc34-image `send_image.py`.
 *
 * Wire chunk (70 bytes, before base64):
 *   [0:2]   u16 chunk index      (big-endian)
 *   [2:66]  u8*64 pixel data
 *   [66:70] u32 CRC-32 over [0:66] (big-endian)
 *
 * Serial line:  "image <base64>\n"   device replies OK | SUCCESS | ERR
 * Clear line:   "image clear\n"       device replies CLEAR
 */
object ImagePacker {

    const val EXPECTED_DIM = 128
    const val TOTAL_PIXELS = EXPECTED_DIM * EXPECTED_DIM // 16384
    const val TOTAL_BYTES = TOTAL_PIXELS / 8             // 2048
    const val CHUNK_DATA_SIZE = 64
    const val NUM_CHUNKS = TOTAL_BYTES / CHUNK_DATA_SIZE // 32
    const val CHUNK_WIRE_SIZE = 2 + CHUNK_DATA_SIZE + 4  // 70
    /** Firmware cap on frames per image slot (must match MAX_FRAMES in
     *  firmware/dc34-console/src/cmds/image.rs). Extra frames get dropped. */
    const val MAX_FRAMES = 32

    /** How a greyscale image is reduced to 1-bit. */
    enum class Mode { DITHER, THRESHOLD }

    /**
     * Conversion controls.
     *
     * [blackPoint] / [whitePoint] are a classic "levels" stage applied *before*
     * the 1-bit reduction: any pixel at or below [blackPoint] snaps to pure black
     * and any pixel at or above [whitePoint] snaps to pure white, with a linear
     * contrast stretch in between. This is what makes near-white or near-black
     * backgrounds come out *totally* white/black — clamped extremes carry zero
     * diffusion error, so a flat background stays clean instead of picking up
     * dither speckle.
     *
     * [threshold] is the 0..255 pivot separating black from white. [mode] chooses
     * Floyd–Steinberg dithering (good for photos/gradients) or a hard threshold
     * (perfectly flat fills, no speckle at all). [invert] swaps black and white.
     */
    data class ConvertOptions(
        val mode: Mode = Mode.DITHER,
        val threshold: Int = 128,
        val blackPoint: Int = 16,
        val whitePoint: Int = 239,
        val invert: Boolean = false,
    )

    /**
     * Resize [src] to 128x128 and reduce to 1-bit per [opts]. Returns a boolean
     * bitmap `black[y*128 + x]` where true == black pixel.
     *
     * Pipeline: scale → luminance → levels (black/white point + stretch) →
     * optional invert → threshold or Floyd–Steinberg dither.
     */
    fun toMonochrome(src: Bitmap, opts: ConvertOptions = ConvertOptions()): BooleanArray {
        val scaled = Bitmap.createScaledBitmap(src, EXPECTED_DIM, EXPECTED_DIM, true)
        val n = TOTAL_PIXELS
        // Grayscale luminance buffer (0..255) as float for error diffusion.
        val gray = FloatArray(n)
        val px = IntArray(n)
        scaled.getPixels(px, 0, EXPECTED_DIM, 0, 0, EXPECTED_DIM, EXPECTED_DIM)

        // Sanitise the levels window so blackPoint < whitePoint (avoid /0).
        val bp = opts.blackPoint.coerceIn(0, 254).toFloat()
        val wp = opts.whitePoint.coerceIn((bp + 1).toInt(), 255).toFloat()
        val span = wp - bp
        val thr = opts.threshold.toFloat()

        for (i in 0 until n) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            var r = (c ushr 16) and 0xFF
            var g = (c ushr 8) and 0xFF
            var b = c and 0xFF
            // Composite over white for transparent pixels (matches PIL RGB->L on white bg)
            if (a < 255) {
                val af = a / 255f
                r = (r * af + 255 * (1 - af)).toInt()
                g = (g * af + 255 * (1 - af)).toInt()
                b = (b * af + 255 * (1 - af)).toInt()
            }
            // ITU-R 601-2 luma transform, same weights PIL uses for "L".
            var v = (r * 299 + g * 587 + b * 114) / 1000f
            // Levels: clamp extremes to pure 0/255, linear stretch in between.
            v = when {
                v <= bp -> 0f
                v >= wp -> 255f
                else -> (v - bp) * 255f / span
            }
            if (opts.invert) v = 255f - v
            gray[i] = v
        }

        val black = BooleanArray(n)

        if (opts.mode == Mode.THRESHOLD) {
            // Hard threshold: no error diffusion, so flat fills stay perfectly clean.
            for (i in 0 until n) black[i] = gray[i] < thr
            return black
        }

        // Floyd–Steinberg dithering to 1-bit. Clamped extremes emit zero error,
        // so backgrounds that hit pure 0/255 above stay speckle-free.
        for (y in 0 until EXPECTED_DIM) {
            for (x in 0 until EXPECTED_DIM) {
                val idx = y * EXPECTED_DIM + x
                val old = gray[idx]
                val newVal = if (old < thr) 0f else 255f
                black[idx] = newVal == 0f // black where quantised to 0
                val err = old - newVal
                if (x + 1 < EXPECTED_DIM) gray[idx + 1] += err * 7f / 16f
                if (y + 1 < EXPECTED_DIM) {
                    if (x > 0) gray[idx + EXPECTED_DIM - 1] += err * 3f / 16f
                    gray[idx + EXPECTED_DIM] += err * 5f / 16f
                    if (x + 1 < EXPECTED_DIM) gray[idx + EXPECTED_DIM + 1] += err * 1f / 16f
                }
            }
        }
        return black
    }

    /** Build a 128x128 preview Bitmap (black/white) from the monochrome buffer. */
    fun previewBitmap(black: BooleanArray): Bitmap {
        val out = IntArray(TOTAL_PIXELS)
        for (i in 0 until TOTAL_PIXELS) {
            out[i] = if (black[i]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bmp = Bitmap.createBitmap(EXPECTED_DIM, EXPECTED_DIM, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, EXPECTED_DIM, 0, 0, EXPECTED_DIM, EXPECTED_DIM)
        return bmp
    }

    /**
     * Produce the 2048-byte payload that matches the reference .rs output:
     *   1. Flip horizontally
     *   2. black -> 1, white -> 0
     *   3. Pack MSB-first into 512 u32s
     *   4. Reverse each group-of-4 words
     *   5. Serialize as big-endian u32 bytes
     */
    fun imageToBytes(black: BooleanArray): ByteArray {
        val packed = IntArray(512)
        var wordIdx = 0
        var current = 0
        var count = 0
        // Iterate flipped pixels in row-major order (y*128 + x), reading x mirrored.
        for (y in 0 until EXPECTED_DIM) {
            for (x in 0 until EXPECTED_DIM) {
                val srcX = EXPECTED_DIM - 1 - x
                val bit = if (black[y * EXPECTED_DIM + srcX]) 1 else 0
                current = current or (bit shl (31 - count))
                count++
                if (count == 32) {
                    packed[wordIdx++] = current
                    current = 0
                    count = 0
                }
            }
        }

        // Per-group-of-4 reversal, then big-endian u32 serialization.
        val out = ByteArray(TOTAL_BYTES)
        var o = 0
        for (i in 0 until 512 / 4) {
            for (w in intArrayOf(packed[i * 4 + 3], packed[i * 4 + 2], packed[i * 4 + 1], packed[i * 4 + 0])) {
                out[o++] = ((w ushr 24) and 0xFF).toByte()
                out[o++] = ((w ushr 16) and 0xFF).toByte()
                out[o++] = ((w ushr 8) and 0xFF).toByte()
                out[o++] = (w and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * Build a 70-byte chunk. Wire layout:
     *   [0]      u8   frame index inside a multi-frame image (0..N-1)
     *   [1]      u8   chunk index inside the frame (0..31)
     *   [2..66]  u8*64 pixel data
     *   [66..70] u32  CRC-32 over [0..66] (big-endian)
     *
     * The two index bytes used to be a big-endian u16 chunk index whose high
     * byte was always 0 in legacy single-frame uploads — reinterpreting the
     * high byte as `frameIdx` is 100% backward-compatible with the old
     * firmware (which reads them as `u16 BE chunk_idx` = `frameIdx * 256 + chunkIdx`,
     * and since we cap frameIdx at 0 for single-frame use the value matches).
     */
    fun makeChunk(chunkIdx: Int, data: ByteArray, frameIdx: Int = 0): ByteArray {
        require(data.size == CHUNK_DATA_SIZE)
        require(chunkIdx in 0..255) { "chunkIdx must fit in a byte" }
        require(frameIdx in 0..255) { "frameIdx must fit in a byte" }
        val payload = ByteArray(2 + CHUNK_DATA_SIZE)
        payload[0] = (frameIdx and 0xFF).toByte()
        payload[1] = (chunkIdx and 0xFF).toByte()
        System.arraycopy(data, 0, payload, 2, CHUNK_DATA_SIZE)
        val crc = CRC32()
        crc.update(payload)
        val c = crc.value // unsigned 32-bit in a long
        val out = ByteArray(CHUNK_WIRE_SIZE)
        System.arraycopy(payload, 0, out, 0, payload.size)
        out[66] = ((c ushr 24) and 0xFF).toByte()
        out[67] = ((c ushr 16) and 0xFF).toByte()
        out[68] = ((c ushr 8) and 0xFF).toByte()
        out[69] = (c and 0xFF).toByte()
        return out
    }

    /** Split the 2048-byte bitmap into 32 x 64-byte data slices. */
    fun sliceChunks(bitmap: ByteArray): List<ByteArray> {
        require(bitmap.size == TOTAL_BYTES)
        return (0 until TOTAL_BYTES step CHUNK_DATA_SIZE).map {
            bitmap.copyOfRange(it, it + CHUNK_DATA_SIZE)
        }
    }

    /**
     * Encode a chunk into the ASCII serial line "<cmd> <base64>\n". [cmd] is the
     * upload verb — "image" for the user slot or "imagedc" for the DEF CON-logo
     * replacement slot (both share this exact wire format, per the protocol spec).
     */
    fun encodeLine(chunkWire: ByteArray, cmd: String = "image"): ByteArray {
        val b64 = Base64.encodeToString(chunkWire, Base64.NO_WRAP)
        return "$cmd $b64\n".toByteArray(Charsets.US_ASCII)
    }
}
