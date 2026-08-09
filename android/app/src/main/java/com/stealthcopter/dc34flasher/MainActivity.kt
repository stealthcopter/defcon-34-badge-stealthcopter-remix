package com.stealthcopter.dc34flasher

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.stealthcopter.dc34flasher.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.stealthcopter.dc34flasher.USB_PERMISSION"

        // Baochip Baosec-lite (DC34 badge) and Dabao variant.
        private const val BADGE_VID = 0x1d50
        private val BADGE_PIDS = intArrayOf(0x6198, 0x6197)

        // Mirror send_image.py timing.
        private const val SERIAL_TIMEOUT_MS = 4000
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRIES = 4
        private const val LINE_DELAY_MS = 200L

        // Terminal protocol tokens the badge replies with, per command. Every
        // other line on the CDC console (async logs, "[console]" echoes) is noise.
        private val UPLOAD_TOKENS = listOf("SUCCESS", "OK", "ERR")
        private val CLEAR_TOKENS = listOf("CLEAR")

        // Single-shot 'led …' preset commands reply OK (accepted) or ERR.
        private val LED_TOKENS = listOf("OK", "ERR")

        // Upload verbs: "image" = user slot, "imagedc" = DEF CON-logo slot.
        // Both share the identical 70-byte chunk wire format (see ImagePacker).
        private const val CMD_IMAGE_USER = "image"
        private const val CMD_IMAGE_DEFCON = "imagedc"

        // Where the downloaded firmware lands under shared Downloads (each
        // variant to its own subfolder, so the sets never get mixed up).
        private const val FW_EXPORT_ROOT = "dc34-firmware"

        // --- Firmware release + wizard assets ---------------------------------
        // The firmware images are NOT bundled in the app any more — they are
        // downloaded on demand and unzipped into local storage.
        //
        // The Official DEF CON 34 image comes straight from the Betrusted CI
        // "latest" build for the dc34-badge target.
        private const val OFFICIAL_FW_ZIP =
            "https://ci.betrusted.io/releases/latest/baochip/dc34-badge/latest.zip"

        // The Stealthcopter Remix comes from the latest GitHub release's
        // firmware.zip asset (always the newest published release).
        private const val FW_RELEASE =
            "https://github.com/stealthcopter/defcon-34-badge-stealthcopter-remix/releases/latest/download"

        // Flash-wizard illustrations (cached on first view).
        private const val IMG_UPDATE_MODE =
            "https://raw.githubusercontent.com/stealthcopter/defcon-34-badge-stealthcopter-remix/refs/heads/main/reset.png"
        private const val IMG_FLASHED =
            "https://raw.githubusercontent.com/stealthcopter/defcon-34-badge-stealthcopter-remix/refs/heads/main/flashed.png"

        private const val COLOR_GREEN = 0xFF3DDC84.toInt()
        private const val COLOR_AMBER = 0xFFE0A030.toInt()
        private const val COLOR_GREY = 0xFF708090.toInt()

        /**
         * The two firmware images the user can flash. Neither is bundled in the
         * APK — each downloads its own zip from [Firmware.zipUrl] on demand and is
         * unzipped into filesDir/firmware/<dir>/, then copied to Downloads for the
         * actual USB-drive flash.
         *
         *  - "original" is the genuine signed DEF CON 34 release from Betrusted CI
         *    ([OFFICIAL_FW_ZIP]) — recovery / stock.
         *  - "remix" is the Stealthcopter Remix: the same base plus the app's bonus
         *    features (LED colour control + a second on-screen logo slot). Flashing
         *    it is what unlocks the LEDs tab and the "DEF CON logo" upload slot
         *    ([Firmware.advanced]).
         */
        private val FIRMWARES = listOf(
            Firmware(
                dir = "original",
                title = "Official DEF CON 34 firmware",
                blurb = "The genuine signed release — the exact image DEF CON ships. " +
                    "Flash this to return to stock or to RECOVER a badge that will not " +
                    "boot. No extra app features.",
                zipUrl = OFFICIAL_FW_ZIP,
                advanced = false,
            ),
            Firmware(
                dir = "remix",
                title = "Stealthcopter Remix ★",
                blurb = "The official build PLUS this app's bonus features:\n" +
                    "  • LED colour control — rainbow / solid / hue over USB\n" +
                    "  • a second on-screen logo slot for your own image\n" +
                    "Flash this to unlock the LEDs tab and the “DEF CON logo” upload slot.",
                zipUrl = "$FW_RELEASE/firmware.zip",
                advanced = true,
            ),
        )
    }

    /** One selectable firmware variant (see [FIRMWARES]). */
    data class Firmware(
        val dir: String,
        val title: String,
        val blurb: String,
        val zipUrl: String,
        val advanced: Boolean,
    )

    /** The firmware the user has selected in the Firmware tab. Defaults to the
     *  Remix build because that's what unlocks the app's own features — users
     *  installing via this app almost always want the Remix, not the stock image. */
    private var selectedFirmware: Firmware =
        FIRMWARES.firstOrNull { it.dir == "remix" } ?: FIRMWARES.first()

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var serial: CdcAcmSerial? = null
    private var pendingDevice: UsbDevice? = null

    /** Which image slot the upload button targets: false = user "image",
     *  true = DEF CON-logo "imagedc". Driven by the slot radios in the Logo tab. */
    private var uploadDefconSlot = false

    /**
     * Whether the connected badge runs firmware with the app's bonus features
     * (the Stealthcopter Remix: 'led' preset commands + the 'imagedc' second
     * logo slot). `null` = not yet probed. Set by [checkLedCapability] after each
     * connect. On the stock DEF CON build these features aren't present, so this
     * stays `false`; the LED controls and the DEF CON-logo slot are dimmed and
     * tapping them explains that the Remix firmware is needed.
     */
    private var ledSupported: Boolean? = null

    /** Convenience mirror of `ledSupported == true`: are the Remix-only features
     *  usable right now? Gates the LED controls and the DEF CON-logo slot. */
    private var advancedEnabled = false

    /** Last decoded source image, kept so conversion can be re-run when the
     *  dither/threshold/invert controls change without re-picking the file. */
    private var sourceBitmap: Bitmap? = null

    /** Monochrome image buffer ready to pack, plus its 2048-byte payload. */
    private var monochrome: BooleanArray? = null

    /** Image-conversion settings, edited in the post-pick "Adjust image" dialog. */
    private var convThresholdMode = false
    private var convThreshold = 128
    private var convInvert = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // --- USB events ----------------------------------------------------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getDeviceExtra(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("USB permission result: granted=$granted device=${device?.deviceName}")
                    if (granted && device != null) {
                        connectToDevice(device)
                    } else {
                        log("Permission denied — cannot open the device")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getDeviceExtra(intent)
                    log("EVENT: USB_DEVICE_ATTACHED ${device?.let { "0x%04x/0x%04x".format(it.vendorId, it.productId) }}")
                    if (device != null && isBadge(device)) {
                        log("Attached device matches the badge — requesting permission")
                        requestOrConnect(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getDeviceExtra(intent)
                    log("EVENT: USB_DEVICE_DETACHED ${device?.deviceName}")
                    if (serial?.isOpen == true) {
                        disconnect()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    // --- lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showPanel(0)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // One button toggles connect / disconnect (never need both at once).
        binding.btnConnect.setOnClickListener { onConnectToggle() }
        // "+ Frame" launches the image picker; the dialog's "Add to queue"
        // appends the converted mono buffer to the current slot's local queue.
        binding.btnAddFrame.setOnClickListener { pickImage.launch("image/*") }
        // "+ Zip" launches a file picker for .zip archives; every image entry
        // is decoded, converted with the current dither/threshold settings,
        // and appended to the queue in alphabetical filename order.
        binding.btnAddZip.setOnClickListener { pickZip.launch("application/zip") }
        binding.btnUpload.setOnClickListener { onUploadClicked() }
        // Clear queue = discard the local frame list (badge unchanged).
        binding.btnClearQueue.setOnClickListener { clearQueueForCurrentSlot() }
        // Wipe badge slot = send `<cmd> clear` to the badge. Overflow menu still
        // works too.
        binding.btnClearBadge.setOnClickListener { onClearBadgeClicked() }

        // FPS: SeekBar updates the label + local preview cadence live; Set FPS
        // pushes the value to the badge via the `fps <N>` REPL command.
        binding.fpsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                val fps = value.coerceAtLeast(1)
                binding.fpsLabel.text = "FPS: $fps"
                animPreviewMs = ((1000L + fps / 2) / fps).coerceAtLeast(30L)
                // Restart the ticker so the change takes effect on the next tick
                // instead of after the current sleep interval.
                val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
                val list = queuedFrames[slot]
                if (list != null && list.size > 1) {
                    ui.removeCallbacks(animPreviewTick)
                    ui.postDelayed(animPreviewTick, animPreviewMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.btnSetFps.setOnClickListener { onSetFpsClicked() }

        // Image slot selector: user "image" vs DEF CON-logo "imagedc" (Remix-only).
        binding.slotChoice.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.slotDefcon && !advancedEnabled) {
                showRemixRequiredDialog("The second (DEF CON logo) image slot")
                binding.slotChoice.check(R.id.slotUser)
                return@setOnCheckedChangeListener
            }
            uploadDefconSlot = checkedId == R.id.slotDefcon
            binding.slotHint.text = if (uploadDefconSlot)
                "DEF CON-logo slot: replaces the built-in logo (falls back to it when empty). Persists across reboots."
            else
                "User image alternates with the DEF CON logo every 3 s on the idle screen."
            // Each slot has its own local queue; redraw the strip for the new one.
            refreshFramesStrip()
        }

        // Console passthrough (power users) — works on any firmware.
        binding.btnSend.setOnClickListener { onSendClicked() }
        binding.cmdInput.setOnEditorActionListener { _, _, _ -> onSendClicked(); true }

        // LED preset controls (Remix-only 'led …' command family). Each is gated:
        // on stock firmware the tap explains that the Remix build is required.
        binding.btnRainbow.setOnClickListener { ledAction { sendLedCommand("led rainbow") } }
        binding.btnDisco.setOnClickListener { ledAction { sendLedCommand("led disco") } }
        binding.btnRevert.setOnClickListener { ledAction { sendLedCommand("led revert") } }
        binding.btnSolid.setOnClickListener { ledAction { onSolidClicked() } }

        // Keep the preview swatch in sync as the hex is edited.
        binding.solidHex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hex = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                    binding.solidSwatch.setBackgroundColor(0xFF000000.toInt() or hex.toLong(16).toInt())
                }
            }
        })

        // Color swatches: tap = apply currently-selected effect at that hue.
        val swatches = listOf(
            binding.swRed to "ff0000",
            binding.swOrange to "ff8000",
            binding.swYellow to "ffff00",
            binding.swGreen to "00ff00",
            binding.swCyan to "00ffff",
            binding.swBlue to "0000ff",
            binding.swMagenta to "ff00ff",
            binding.swWhite to "ffffff",
        )
        for ((view, hex) in swatches) {
            view.setOnClickListener { ledAction { sendSolid(hex) } }
        }

        // Effect radio (Flash/Breathe/Rotate): changing the selection instantly
        // re-applies the current color with the new effect. Uses the hex field if
        // valid, otherwise the hint value (ff8000). Attached AFTER inflation so
        // the initial checked="true" on Flash in the layout doesn't fire it.
        binding.effectGroup.setOnCheckedChangeListener { _, _ ->
            ledAction {
                val raw = binding.solidHex.text.toString().trim()
                    .removePrefix("#")
                    .ifEmpty { binding.solidHex.hint?.toString().orEmpty() }
                    .removePrefix("#")
                if (raw.matches(Regex("[0-9a-fA-F]{6}"))) {
                    sendSolid(raw.lowercase())
                }
            }
        }

        // Prime swatches to the initial slider values.
        binding.hueSwatch.setBackgroundColor(hueToArgb(binding.hueBar.progress))
        binding.boundSwatch.setBackgroundColor(hueToArgb(binding.boundBar.progress))

        binding.hueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                binding.hueLabel.text = "base $value"
                binding.hueSwatch.setBackgroundColor(hueToArgb(value))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.boundBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                binding.boundLabel.text = "bound $value"
                binding.boundSwatch.setBackgroundColor(hueToArgb(value))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.btnHue.setOnClickListener {
            ledAction { sendLedCommand("led hue ${binding.hueBar.progress} ${binding.boundBar.progress}") }
        }

        // Firmware tab: one radio per variant + the guided flash wizard.
        populateFirmwareChoices()
        binding.btnFlashGuide.setOnClickListener { showFlashWizard() }
        binding.btnSaveFirmware.setOnClickListener { onDownloadOnlyClicked() }

        // Start dimmed until a badge is probed (no Remix features assumed).
        applyLedSupport(null, "LED support: connect the badge to check…")

        log("DC34 Flasher ready. Target badge VID=0x%04x PID=0x6198/0x6197 @ ${CdcAcmSerial.BAUD_RATE} baud".format(BADGE_VID))

        // If launched via USB_DEVICE_ATTACHED intent, grab the device.
        (getDeviceExtra(intent))?.let {
            log("Launched from USB attach intent: ${it.deviceName}")
            if (isBadge(it)) requestOrConnect(it)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_badge -> { onClearBadgeClicked(); true }
        R.id.action_clear_log -> { binding.logText.text = ""; log("Log cleared"); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Show one of the tab panels (0=Logo, 1=LEDs, 2=Firmware), hide the rest. */
    private fun showPanel(index: Int) {
        binding.panelLogo.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelLeds.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelFirmware.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(animPreviewTick)
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        worker.execute { serial?.close() }
        worker.shutdown()
        net.shutdown()
    }

    // --- image picking -------------------------------------------------------

    /** One queued frame: the mono buffer that gets shipped to the badge and a
     *  small preview bitmap for the on-screen thumbnail strip. */
    private data class QueueEntry(val mono: BooleanArray, val preview: Bitmap)

    /** Ticks the animated preview at ~4 fps (matches the badge's pumper rate,
     *  see firmware/src/totp.rs::pumper). Cancelled + rescheduled on every
     *  queue change so it always reflects what's currently queued. */
    private val animPreviewTick = object : Runnable {
        override fun run() {
            val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
            val list = queuedFrames[slot] ?: return
            if (list.isEmpty()) return
            animPreviewFrame = (animPreviewFrame + 1) % list.size
            binding.animPreview.setImageBitmap(list[animPreviewFrame].preview)
            ui.postDelayed(this, animPreviewMs)
        }
    }
    private var animPreviewFrame: Int = 0
    /** Preview cadence in ms/frame — kept in sync with the FPS SeekBar. Defaults
     *  to 10 fps (100 ms) to match the firmware's default `anim_fps`. */
    private var animPreviewMs: Long = 100L

    /**
     * The local frame queue per slot. `Add frame` appends to `queuedFrames[slot]`,
     * `Upload to badge` flashes the whole list as one multi-frame image, and
     * `Clear queue` empties it. Independent per slot so switching slots doesn't
     * discard what you were building.
     */
    private val queuedFrames: MutableMap<String, MutableList<QueueEntry>> = mutableMapOf(
        CMD_IMAGE_USER to mutableListOf(),
        CMD_IMAGE_DEFCON to mutableListOf(),
    )

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                log("Image pick cancelled")
                return@registerForActivityResult
            }
            val currentSlot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
            if ((queuedFrames[currentSlot]?.size ?: 0) >= ImagePacker.MAX_FRAMES) {
                log("Queue full (${ImagePacker.MAX_FRAMES} frames) — Upload or Clear first")
                toast("Frame cap reached (${ImagePacker.MAX_FRAMES})")
                return@registerForActivityResult
            }
            log("Picked image: $uri")
            worker.execute {
                try {
                    val src = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                    if (src == null) {
                        log("ERROR: could not decode image")
                        return@execute
                    }
                    sourceBitmap = src
                    log("Decoded ${src.width}x${src.height}")
                    ui.post { showImageDialog(src) }
                } catch (e: Exception) {
                    log("ERROR loading image: ${e.message}")
                }
            }
        }

    /**
     * Zip-of-images picker. All entries whose filename ends in a known image
     * extension are decoded and added to the current slot's frame queue, in
     * ALPHABETICAL order by entry name (so `frame_00.png … frame_11.png`
     * queue up in the obvious order). Conversion uses the currently-selected
     * dither/threshold/invert options — no per-image dialog for zips because
     * that would be tedious for a 20-frame animation.
     */
    private val pickZip =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                log("Zip pick cancelled")
                return@registerForActivityResult
            }
            log("Picked zip: $uri")
            worker.execute { addFramesFromZip(uri) }
        }

    private fun addFramesFromZip(uri: Uri) {
        val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
        val list = queuedFrames.getOrPut(slot) { mutableListOf() }
        val roomLeft = ImagePacker.MAX_FRAMES - list.size
        if (roomLeft <= 0) {
            log("Queue full (${ImagePacker.MAX_FRAMES} frames) — Upload or Clear first")
            ui.post { toast("Frame cap reached (${ImagePacker.MAX_FRAMES})") }
            return
        }

        // Read every image entry into memory first, then sort alphabetically.
        // ZipInputStream visits entries in file order, which for most zip tools
        // matches the on-disk order — we don't rely on that.
        data class NamedBytes(val name: String, val bytes: ByteArray)
        val entries = mutableListOf<NamedBytes>()
        try {
            contentResolver.openInputStream(uri).use { rawIn ->
                if (rawIn == null) {
                    log("ERROR: could not open zip")
                    return
                }
                java.util.zip.ZipInputStream(rawIn).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val lower = entry.name.lowercase()
                        val isImg = lower.endsWith(".png") || lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") || lower.endsWith(".bmp") ||
                            lower.endsWith(".webp") || lower.endsWith(".gif")
                        if (!isImg) { zip.closeEntry(); continue }
                        // Ignore macOS resource-fork noise like __MACOSX/._foo.png
                        if (entry.name.contains("__MACOSX") ||
                            java.io.File(entry.name).name.startsWith("._")) {
                            zip.closeEntry(); continue
                        }
                        val buf = zip.readBytes()
                        entries.add(NamedBytes(entry.name, buf))
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            log("ERROR reading zip: ${e.message}")
            return
        }

        if (entries.isEmpty()) {
            log("Zip contained no supported images (png/jpg/gif/bmp/webp)")
            ui.post { toast("No images found in zip") }
            return
        }

        entries.sortBy { it.name }
        val taken = entries.take(roomLeft)
        if (entries.size > roomLeft) {
            log("Zip has ${entries.size} images, ${roomLeft} slot(s) free — queuing first $roomLeft")
        } else {
            log("Zip has ${entries.size} image(s) — queuing all in alphabetical order")
        }

        val opts = currentOptions()
        var addedThisPass = 0
        for (e in taken) {
            try {
                val bmp = BitmapFactory.decodeByteArray(e.bytes, 0, e.bytes.size)
                if (bmp == null) {
                    log("   skip ${e.name} — could not decode")
                    continue
                }
                val mono = ImagePacker.toMonochrome(bmp, opts)
                val preview = ImagePacker.previewBitmap(mono)
                list.add(QueueEntry(mono, preview))
                addedThisPass++
                log("   + ${e.name}")
            } catch (ex: Exception) {
                log("   skip ${e.name} — ${ex.message}")
            }
        }
        log("Queued $addedThisPass frame(s) from zip (queue depth now ${list.size})")
        ui.post {
            refreshFramesStrip()
            markImageReady()
        }
    }

    /** Conversion options built from the stored dither/threshold/invert settings. */
    private fun currentOptions(): ImagePacker.ConvertOptions = ImagePacker.ConvertOptions(
        mode = if (convThresholdMode) ImagePacker.Mode.THRESHOLD else ImagePacker.Mode.DITHER,
        threshold = convThreshold,
        invert = convInvert,
    )

    /**
     * After an image is picked, show the "Adjust image" dialog with the
     * dither/threshold/invert controls and a live 128×128 preview. Applying
     * updates the main preview and the buffer that the upload button sends.
     */
    private fun showImageDialog(src: Bitmap) {
        val view = layoutInflater.inflate(R.layout.dialog_image, null)
        val dlgPreview = view.findViewById<ImageView>(R.id.dlgPreview)
        val modeDither = view.findViewById<RadioButton>(R.id.modeDither)
        val modeThreshold = view.findViewById<RadioButton>(R.id.modeThreshold)
        val invertCheck = view.findViewById<CheckBox>(R.id.invertCheck)
        val thresholdLabel = view.findViewById<TextView>(R.id.thresholdLabel)
        val thresholdBar = view.findViewById<SeekBar>(R.id.thresholdBar)

        modeThreshold.isChecked = convThresholdMode
        modeDither.isChecked = !convThresholdMode
        invertCheck.isChecked = convInvert
        thresholdBar.progress = convThreshold
        thresholdLabel.text = "Threshold $convThreshold"

        fun refresh() {
            convThresholdMode = modeThreshold.isChecked
            convInvert = invertCheck.isChecked
            convThreshold = thresholdBar.progress
            thresholdLabel.text = "Threshold $convThreshold"
            val mono = ImagePacker.toMonochrome(src, currentOptions())
            monochrome = mono
            val preview = ImagePacker.previewBitmap(mono)
            val black = mono.count { it }
            dlgPreview.setImageBitmap(preview)
            binding.preview.setImageBitmap(preview)
            binding.imageInfo.text = "128x128, $black black px — press Upload ▾"
            markImageReady()
        }

        modeDither.setOnCheckedChangeListener { _, _ -> refresh() }
        invertCheck.setOnCheckedChangeListener { _, _ -> refresh() }
        thresholdBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                thresholdLabel.text = "Threshold $value"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = refresh()
        })
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Adjust image")
            .setView(view)
            .setPositiveButton("Add to queue") { _, _ -> addCurrentFrameToQueue() }
            .setNeutralButton("Upload now") { _, _ ->
                addCurrentFrameToQueue()
                onUploadClicked()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Append the most recently-refreshed `monochrome` buffer to the queue for
     *  the currently-selected slot, and repaint the thumbnail strip. */
    private fun addCurrentFrameToQueue() {
        val mono = monochrome ?: run {
            log("No image ready to queue")
            return
        }
        val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
        val list = queuedFrames.getOrPut(slot) { mutableListOf() }
        if (list.size >= ImagePacker.MAX_FRAMES) {
            log("Queue full (${ImagePacker.MAX_FRAMES}) — Upload or Clear first")
            toast("Frame cap reached (${ImagePacker.MAX_FRAMES})")
            return
        }
        list.add(QueueEntry(mono.copyOf(), ImagePacker.previewBitmap(mono)))
        log("Queued frame ${list.size} for '$slot' (queue depth ${list.size})")
        refreshFramesStrip()
        markImageReady()   // enable Upload
    }

    /** Rebuild the horizontal thumbnail strip from the currently-selected slot's
     *  queue. Tap a thumbnail to remove it. Call on the UI thread. */
    private fun refreshFramesStrip() {
        val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
        val list = queuedFrames[slot] ?: mutableListOf()
        val strip = binding.framesStrip
        strip.removeAllViews()
        val ctx = strip.context
        val sizePx = (72 * resources.displayMetrics.density).toInt()
        val marginPx = (4 * resources.displayMetrics.density).toInt()
        for ((i, entry) in list.withIndex()) {
            val iv = ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).also {
                    it.marginEnd = marginPx
                }
                setBackgroundColor(0xFFFFFFFF.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(entry.preview)
                contentDescription = "Frame ${i + 1} — tap to remove"
                setOnClickListener { promptRemoveFrame(i) }
            }
            strip.addView(iv)
        }
        binding.imageInfo.text = when (list.size) {
            0 -> "Queue empty — tap + Frame or + Zip to start"
            1 -> "1 frame queued for ${slot.uppercase()} — Upload for a static image, or add more to animate"
            else -> "${list.size} frames queued for ${slot.uppercase()} — Upload to flash them (preview cycles at ~4 fps)"
        }
        // Upload enabled only when there's at least one frame queued.
        binding.btnUpload.isEnabled = list.isNotEmpty()
        binding.btnClearQueue.isEnabled = list.isNotEmpty()
        // If the last frame just got queued, cap the button so extras don't try.
        binding.btnAddFrame.isEnabled = list.size < ImagePacker.MAX_FRAMES

        // Refresh the animated preview:
        //  - Empty queue: blank the preview.
        //  - 1 frame:     show it static; no ticker needed.
        //  - N frames:    show frame 0 now, start the ticker for the rest.
        ui.removeCallbacks(animPreviewTick)
        animPreviewFrame = 0
        when {
            list.isEmpty() -> binding.animPreview.setImageDrawable(null)
            list.size == 1 -> binding.animPreview.setImageBitmap(list[0].preview)
            else -> {
                binding.animPreview.setImageBitmap(list[0].preview)
                ui.postDelayed(animPreviewTick, animPreviewMs)
            }
        }
    }

    private fun promptRemoveFrame(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove frame ${index + 1}?")
            .setMessage("This removes the frame from the local queue. The badge is not affected until you Upload.")
            .setPositiveButton("Remove") { _, _ ->
                val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
                queuedFrames[slot]?.removeAt(index)
                refreshFramesStrip()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Light up the upload button so the user knows the next step is to press it. */
    private fun markImageReady() {
        if (!binding.btnUpload.isEnabled) {
            binding.btnUpload.isEnabled = true
            pulse(binding.btnUpload)
        }
    }

    /** A short scale pulse to draw the eye to a freshly-enabled control. */
    private fun pulse(v: View) {
        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    // --- connect / disconnect ------------------------------------------------

    private fun onConnectToggle() {
        if (serial?.isOpen == true) disconnect() else onConnectClicked()
    }

    private fun onConnectClicked() {
        if (serial?.isOpen == true) {
            log("Already connected")
            return
        }
        val device = findBadge()
        if (device == null) {
            log("No badge found. Attached USB devices:")
            val devices = usbManager.deviceList
            if (devices.isEmpty()) {
                log("  (none — nothing is plugged into the USB host port)")
            } else {
                for (d in devices.values) {
                    log("  ${d.deviceName} VID=0x%04x PID=0x%04x class=${d.deviceClass}".format(d.vendorId, d.productId))
                }
            }
            return
        }
        requestOrConnect(device)
    }

    /** Find the badge by VID/PID, else fall back to the first CDC-class device. */
    private fun findBadge(): UsbDevice? {
        val devices = usbManager.deviceList.values
        devices.firstOrNull { isBadge(it) }?.let {
            log("Found badge: ${it.deviceName} 0x%04x/0x%04x".format(it.vendorId, it.productId))
            return it
        }
        // Fallback: any device exposing a CDC comm or data interface.
        val cdc = devices.firstOrNull { hasCdcInterface(it) }
        if (cdc != null) {
            log("No exact VID/PID match; falling back to CDC-class device ${cdc.deviceName} 0x%04x/0x%04x"
                .format(cdc.vendorId, cdc.productId))
        }
        return cdc
    }

    private fun isBadge(d: UsbDevice): Boolean =
        d.vendorId == BADGE_VID && BADGE_PIDS.contains(d.productId)

    private fun hasCdcInterface(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val c = d.getInterface(i).interfaceClass
            if (c == CdcAcmSerial.USB_CLASS_COMM || c == CdcAcmSerial.USB_CLASS_CDC_DATA) return true
        }
        return false
    }

    private fun requestOrConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            log("Requesting USB permission for ${device.deviceName} …")
            pendingDevice = device
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        worker.execute {
            log("── Connecting to ${device.deviceName} ──")
            val s = CdcAcmSerial(usbManager, device) { line -> log(line) }
            log(s.describeDevice())
            val ok = try {
                s.open()
            } catch (e: Exception) {
                log("ERROR during open(): ${e.message}")
                false
            }
            if (ok) {
                serial = s
                setStatus("connected: 0x%04x/0x%04x @ ${CdcAcmSerial.BAUD_RATE}".format(device.vendorId, device.productId))
                updateConnectUi(true)
                log("✓ Connected and configured. Ready to upload.")
                // Auto-detect whether this firmware has the Remix features so the
                // LED controls + DEF CON-logo slot reflect reality. Retry a few
                // times because the badge takes ~10s to fully boot and the first
                // probe often fires before the console REPL is listening.
                checkLedCapabilityWithRetries()
            } else {
                s.close()
                setStatus("disconnected (open failed)")
                updateConnectUi(false)
            }
        }
    }

    private fun disconnect() {
        worker.execute {
            serial?.close()
            serial = null
            setStatus("disconnected")
            log("Disconnected by user")
        }
        updateConnectUi(false)
        applyLedSupport(null, "LED support: connect the badge to check…")
    }

    /** Reflect the connection state on the single Connect/Disconnect button. */
    private fun updateConnectUi(connected: Boolean) = ui.post {
        binding.btnConnect.text = if (connected) "Disconnect badge" else "Connect badge"
    }

    // --- upload / clear ------------------------------------------------------

    private fun onUploadClicked() {
        val s = serial
        if (s == null || !s.isOpen) {
            log("Not connected — press Connect first")
            toast("Connect the badge first")
            return
        }
        if (uploadDefconSlot && !advancedEnabled) {
            showRemixRequiredDialog("The second (DEF CON logo) image slot")
            return
        }
        val cmd = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
        val queue = queuedFrames[cmd] ?: mutableListOf()
        if (queue.isEmpty()) {
            log("Frame queue is empty — tap + Add frame first")
            toast("Add at least one frame first")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            log("Busy — another transfer is in progress")
            return
        }
        val framesForUpload: List<BooleanArray> = queue.map { it.mono }

        worker.execute {
            try {
                val ok = uploadImage(s, framesForUpload, cmd)
                if (ok) {
                    log("Slot '$cmd' now shows ${framesForUpload.size} frame(s) on the badge")
                    // Keep the local queue intact so the user can re-upload,
                    // add another frame + upload, or Clear queue explicitly.
                }
            } finally {
                busy.set(false)
            }
        }
    }

    /** Wipe the local queue for the currently-selected slot. Badge unchanged. */
    private fun clearQueueForCurrentSlot() {
        val slot = if (uploadDefconSlot) CMD_IMAGE_DEFCON else CMD_IMAGE_USER
        queuedFrames[slot]?.clear()
        refreshFramesStrip()
    }

    private enum class ChunkResult { Continue, Success, Failed }

    /** Returns true iff the badge acknowledged the full transfer with SUCCESS. */
    private fun uploadImage(s: CdcAcmSerial, frames: List<BooleanArray>, cmd: String): Boolean {
        val slotName = if (cmd == CMD_IMAGE_DEFCON) "DEF CON-logo slot" else "user image slot"
        val n = frames.size
        log("── Building ${n * 2048}-byte payload ($n frame${if (n == 1) "" else "s"}) for $slotName ('$cmd') ──")

        val drained = s.drainInput()
        if (drained > 0) log("Drained $drained stale console byte(s) before upload")

        // For a multi-frame upload we tell the badge how many frames to expect
        // BEFORE sending any chunks (`<cmd> frames N\n`). Single-frame uploads
        // skip this handshake for backward compatibility with the pre-multi
        // firmware.
        if (n > 1) {
            val framesLine = "$cmd frames $n\n".toByteArray(Charsets.US_ASCII)
            s.write(framesLine)
            log("→ $cmd frames $n")
            val resp = s.awaitResponse(listOf("OK", "ERR"), SERIAL_TIMEOUT_MS) { skipped ->
                log("   · skip async: $skipped")
            }
            log("   RX: ${resp ?: "<timeout>"}")
            if (resp == null || resp == "ERR") {
                log("✗ '$cmd frames $n' rejected — is the badge running Remix firmware with multi-frame support?")
                setStatus("upload failed (frames rejected)")
                return false
            }
        }

        val allChunks: List<List<ByteArray>> = frames.map { mono ->
            ImagePacker.sliceChunks(ImagePacker.imageToBytes(mono))
        }
        val totalChunks = n * ImagePacker.NUM_CHUNKS
        log("Payload built: $totalChunks chunks of ${ImagePacker.CHUNK_DATA_SIZE} B")
        log("Sending $totalChunks '$cmd' chunks @ ${CdcAcmSerial.BAUD_RATE} baud")

        var sent = 0
        for (frameIdx in 0 until n) {
            val chunks = allChunks[frameIdx]
            for (chunkIdx in chunks.indices) {
                val wire = ImagePacker.makeChunk(chunkIdx, chunks[chunkIdx], frameIdx)
                val line = ImagePacker.encodeLine(wire, cmd)
                sent++
                when (sendOneChunk(s, line, sent, totalChunks, cmd, frameIdx, chunkIdx)) {
                    ChunkResult.Success -> return true
                    ChunkResult.Failed -> return false
                    ChunkResult.Continue -> {}
                }
                sleep(LINE_DELAY_MS)
            }
        }
        log("WARN: all chunks sent but SUCCESS was never received")
        setStatus("upload finished (no final SUCCESS)")
        return false
    }

    /** Send one prepared chunk line with retries. Returns true to keep going,
     *  Success ends the whole upload (SUCCESS ACK), Continue keeps going,
     *  Failed aborts.  */
    private fun sendOneChunk(
        s: CdcAcmSerial,
        line: ByteArray,
        sentIdx: Int,
        total: Int,
        cmd: String,
        frameIdx: Int,
        chunkIdx: Int,
    ): ChunkResult {
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            val written = s.write(line)
            log("→ frame $frameIdx chunk ${chunkIdx + 1}/${ImagePacker.NUM_CHUNKS} (overall $sentIdx/$total) TX ${written}B")
            val resp = s.awaitResponse(UPLOAD_TOKENS, SERIAL_TIMEOUT_MS) { skipped ->
                log("   · skip async: $skipped")
            }
            log("   RX: ${resp ?: "<timeout>"}")
            when (resp) {
                "SUCCESS" -> {
                    log("✓ SUCCESS — full transfer complete ($total chunks)")
                    setStatus("upload complete")
                    return ChunkResult.Success
                }
                "OK" -> return ChunkResult.Continue
                "ERR" -> {
                    if (attempt < MAX_RETRIES) {
                        log("   WARN ERR — retry ${attempt + 1}/$MAX_RETRIES")
                        sleep(RETRY_DELAY_MS)
                    } else {
                        log("✗ Chunk $sentIdx failed after $MAX_RETRIES retries — aborting")
                        setStatus("upload failed (ERR)")
                        return ChunkResult.Failed
                    }
                }
                else -> {
                    if (attempt < MAX_RETRIES) {
                        log("   WARN unexpected/timeout '$resp' — retry ${attempt + 1}/$MAX_RETRIES")
                        sleep(RETRY_DELAY_MS)
                    } else {
                        log("✗ Chunk $sentIdx — no valid response after retries — aborting")
                        setStatus("upload failed (no response)")
                        return ChunkResult.Failed
                    }
                }
            }
            attempt++
        }
        return ChunkResult.Failed
    }

    private fun onClearBadgeClicked() {
        val s = serial
        if (s == null || !s.isOpen) {
            log("Not connected — press Connect first")
            return
        }
        // Let the user pick which slot(s) to wipe. The DEF CON-logo slot only
        // exists on the Remix firmware.
        val items = if (advancedEnabled)
            arrayOf("User image", "DEF CON logo", "Both")
        else
            arrayOf("User image")
        AlertDialog.Builder(this)
            .setTitle("Clear badge image")
            .setItems(items) { _, which ->
                val cmds = when (which) {
                    0 -> listOf(CMD_IMAGE_USER)
                    1 -> listOf(CMD_IMAGE_DEFCON)
                    else -> listOf(CMD_IMAGE_USER, CMD_IMAGE_DEFCON)
                }
                clearSlots(s, cmds)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Send `<cmd> clear` for each requested slot, waiting for the CLEAR reply. */
    private fun clearSlots(s: CdcAcmSerial, cmds: List<String>) {
        if (!busy.compareAndSet(false, true)) {
            log("Busy — another transfer is in progress")
            return
        }
        worker.execute {
            try {
                for (cmd in cmds) {
                    log("── Sending '$cmd clear' ──")
                    val drained = s.drainInput()
                    if (drained > 0) log("Drained $drained stale console byte(s) before clear")
                    val line = "$cmd clear\n".toByteArray(Charsets.US_ASCII)
                    var attempt = 0
                    var ok = false
                    while (attempt <= MAX_RETRIES && !ok) {
                        val written = s.write(line)
                        log("   TX ${written}B: $cmd clear")
                        val resp = s.awaitResponse(CLEAR_TOKENS, SERIAL_TIMEOUT_MS) { skipped ->
                            log("   · skip async: $skipped")
                        }
                        log("   RX: ${resp ?: "<timeout>"}")
                        if (resp == "CLEAR") {
                            log("✓ '$cmd' slot cleared")
                            setStatus("cleared: $cmd")
                            // Also drop the local queue for this slot so the
                            // strip on screen matches what the badge shows.
                            queuedFrames[cmd]?.clear()
                            ui.post { refreshFramesStrip() }
                            ok = true
                        } else if (attempt < MAX_RETRIES) {
                            log("   WARN clear not confirmed — retry ${attempt + 1}/$MAX_RETRIES")
                            sleep(RETRY_DELAY_MS)
                        } else {
                            log("✗ '$cmd' clear failed after $MAX_RETRIES retries")
                        }
                        attempt++
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    // --- lights & console ----------------------------------------------------

    private fun onSendClicked() {
        val cmd = binding.cmdInput.text.toString().trim()
        if (cmd.isEmpty()) {
            log("Enter a console command first (e.g. 'ver xous', 'echo hi', 'led rainbow')")
            return
        }
        sendConsole(cmd)
    }

    /**
     * Probe whether this badge runs the Remix firmware (the 'led' preset commands
     * + 'imagedc' slot), set [ledSupported]/[advancedEnabled], and update the UI.
     *
     * It sends `ver` — an ungated verb every build answers — as a positive
     * control for the console link, then a harmless `led revert` and looks for
     * the protocol's `OK`. The Remix firmware answers `OK`; the stock DEF CON
     * build has no `led` verb, so it replies `ERR` (or usage text).
     *
     * @param verbose when true every RX line is logged; when false (auto-probe
     *   after connect) only a one-line summary is logged.
     */
    private fun checkLedCapability(verbose: Boolean, onDone: ((Boolean?) -> Unit)? = null) {
        val s = serial
        if (s == null || !s.isOpen) {
            if (verbose) log("Not connected — press Connect first")
            onDone?.invoke(null)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            if (verbose) log("Busy — another transfer is in progress")
            onDone?.invoke(null)
            return
        }
        worker.execute {
            val wasVerbose = s.verboseRx
            s.verboseRx = verbose
            var remixVerdict: Boolean? = null
            try {
                log(if (verbose) "── Probe: diagnosing console + Remix support ──"
                    else "── Auto-checking firmware features ──")

                // 1) Positive control: `ver` is ungated and always replies.
                s.drainInput()
                var w = s.write("ver\n".toByteArray(Charsets.US_ASCII))
                if (verbose) log("→ ver  (TX ${w}B)")
                val verRaw = s.captureRaw(windowMs = 2500, quietMs = 400)
                val verText = String(verRaw, Charsets.US_ASCII)
                if (verbose) {
                    log("   ver raw: ${verRaw.size}B" + if (verRaw.isEmpty()) " <nothing>" else "")
                    for (l in verText.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }) {
                        log("   RX: $l")
                    }
                }
                val consoleAlive = verRaw.isNotEmpty() && !verText.trim().equals("ERR", true)

                // 2) Remix support: a harmless `led revert` should reply OK.
                s.drainInput()
                w = s.write("led revert\n".toByteArray(Charsets.US_ASCII))
                if (verbose) log("→ led revert  (TX ${w}B)")
                val ledResp = s.awaitResponse(LED_TOKENS, SERIAL_TIMEOUT_MS) { skipped ->
                    if (verbose) log("   · skip: $skipped")
                }
                if (verbose) log("   led revert -> ${ledResp ?: "<timeout>"}")

                when {
                    !consoleAlive && ledResp == null -> {
                        applyLedSupport(null,
                            "LED support: unknown — console isn't returning output. Reconnect and try again.")
                        log("   ⇒ Verdict: console link not returning output. Feature replies can't be read.")
                        remixVerdict = null
                    }
                    ledResp == "OK" -> {
                        applyLedSupport(true,
                            "✓ Stealthcopter Remix firmware — LED control + DEF CON-logo slot enabled.")
                        log("   ⇒ Verdict: 'led revert' returned OK — this is the Remix firmware. Bonus features enabled.")
                        remixVerdict = true
                    }
                    else -> {
                        applyLedSupport(false,
                            "⚠ Stock DEF CON firmware — no LED control. Flash the Stealthcopter Remix " +
                            "from the Firmware tab to enable the LEDs + second logo slot.")
                        log("   ⇒ Verdict: 'led revert' -> ${ledResp ?: "no reply"} — stock firmware. " +
                            "Flash the Remix build to enable the bonus features.")
                        remixVerdict = false
                    }
                }
            } finally {
                s.verboseRx = wasVerbose
                busy.set(false)
                onDone?.invoke(remixVerdict)
            }
        }
    }

    /** Auto-probe wrapper: retries a negative verdict up to [maxAttempts] times
     *  with [delayMs] between tries. The badge takes ~10s to fully boot and the
     *  first probe often fires before the console REPL is listening, so a single
     *  probe can spuriously report "stock firmware" on a Remix badge that just
     *  came up. Stops as soon as Remix is detected. */
    private fun checkLedCapabilityWithRetries(
        maxAttempts: Int = 3,
        delayMs: Long = 3000L,
        attempt: Int = 1,
    ) {
        checkLedCapability(verbose = false) { verdict ->
            // Retry only on a *negative* Remix verdict. Verdict == null means the
            // console link didn't return anything at all — also worth another try.
            if (verdict != true && attempt < maxAttempts) {
                log("   (retrying Remix probe in ${delayMs / 1000}s — attempt ${attempt + 1}/$maxAttempts)")
                ui.postDelayed({
                    checkLedCapabilityWithRetries(maxAttempts, delayMs, attempt + 1)
                }, delayMs)
            }
        }
    }

    /**
     * Apply a detected feature verdict to the UI on the main thread: update
     * [ledSupported]/[advancedEnabled], dim (but keep tappable) the Remix-only
     * controls so tapping them can explain what's needed, and set the banner.
     */
    private fun applyLedSupport(supported: Boolean?, banner: String) = ui.post {
        ledSupported = supported
        advancedEnabled = supported == true
        val dim = if (advancedEnabled) 1f else 0.4f
        binding.ledControls.alpha = dim
        binding.slotDefcon.alpha = dim
        binding.ledBanner.text = banner
        binding.ledBanner.setTextColor(
            when (supported) {
                true -> COLOR_GREEN
                false -> COLOR_AMBER
                null -> COLOR_GREY
            }
        )
    }

    /** Run [block] only if the Remix features are available; otherwise pop the
     *  "needs Remix firmware" explainer. Wraps every LED control. */
    private fun ledAction(block: () -> Unit) {
        if (advancedEnabled) block() else showRemixRequiredDialog("LED colour control")
    }

    /** Explain that a tapped feature needs the Stealthcopter Remix firmware. */
    private fun showRemixRequiredDialog(feature: String) = ui.post {
        AlertDialog.Builder(this)
            .setTitle("Needs the Stealthcopter Remix firmware")
            .setMessage(
                "$feature only works on the Stealthcopter Remix firmware. This badge is " +
                "reporting no `led` command, so we assume it's running the stock DEF CON " +
                "build.\n\n" +
                "If the badge was just rebooted the probe can miss it — try Disconnect & " +
                "recheck below. Otherwise flash the Stealthcopter Remix from the Firmware " +
                "tab to unlock the LED color controls and the second on-screen logo slot."
            )
            .setPositiveButton("Go to Firmware") { _, _ ->
                binding.tabs.getTabAt(2)?.select()
            }
            // "Neutral" so it renders as a third button alongside Close/Go to Firmware.
            .setNeutralButton("Disconnect & recheck") { _, _ ->
                // Drop the current serial handle so the next connect starts fresh, then
                // trigger a reconnect. The subsequent open() flow calls checkLedCapability
                // which will re-probe and repaint the LED banner.
                disconnect()
                ui.postDelayed({ onConnectClicked() }, 400)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Send one console command line ("<cmd>\n") and log whatever the badge
     * replies. Drains stale async console noise first so the reply we collect
     * belongs to this command. Free-form: gather every line until quiet.
     */
    private fun sendConsole(cmd: String) {
        val s = serial
        if (s == null || !s.isOpen) {
            log("Not connected — press Connect first")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            log("Busy — another transfer is in progress")
            return
        }
        worker.execute {
            val wasVerbose = s.verboseRx
            s.verboseRx = true
            try {
                val drained = s.drainInput()
                if (drained > 0) log("   (drained $drained stale byte(s) first)")
                val line = "$cmd\n".toByteArray(Charsets.US_ASCII)
                val w = s.write(line)
                log("→ console: $cmd  (TX ${w}B)")
                val replies = s.readAvailableLines()
                if (replies.isEmpty()) {
                    log("   RX: <no response within ~5s>")
                    explainNoReply(cmd)
                } else {
                    for (r in replies) log("   RX: $r")
                }
            } finally {
                s.verboseRx = wasVerbose
                busy.set(false)
            }
        }
    }

    /** Note when a console command produced no reply — for 'led …' commands the
     *  likely cause is stock firmware. */
    private fun explainNoReply(cmd: String) {
        val c = cmd.trim()
        when {
            c.startsWith("led ") -> {
                log("   note: '$c' is a Remix 'led' command. If it stays silent, this badge")
                log("         probably runs the stock DEF CON firmware. Flash the Stealthcopter")
                log("         Remix (Firmware tab) to enable the LED commands.")
            }
            else -> {
                log("   note: no bytes came back. If other commands (e.g. 'ver') also stay")
                log("         silent, the console link isn't returning output — reconnect.")
            }
        }
    }

    // --- LED preset commands (led rainbow/solid/hue/force/revert) -------------

    /**
     * Send a single `led …` preset command and log the OK/ERR reply. Drains any
     * async console noise first. Guarded by the shared [busy] flag. Callers reach
     * this only through [ledAction], which has already checked Remix support.
     */
    /** Push the SeekBar's current FPS value to the badge via `fps <N>`. The
     *  badge clamps 1..30 and persists to PDDB so the setting sticks across
     *  reboots. Firmware-side default is 10 fps. */
    private fun onSetFpsClicked() {
        val s = serial
        if (s == null || !s.isOpen) {
            log("Not connected — press Connect first")
            toast("Connect the badge first")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            log("Busy — another operation is in progress")
            return
        }
        val fps = binding.fpsBar.progress.coerceIn(1, 30)
        worker.execute {
            try {
                s.drainInput()
                val line = "fps $fps\n".toByteArray(Charsets.US_ASCII)
                val w = s.write(line)
                log("→ fps $fps  (TX ${w}B)")
                val resp = s.awaitResponse(listOf("OK", "ERR"), SERIAL_TIMEOUT_MS) { skipped ->
                    log("   · skip async: $skipped")
                }
                when (resp) {
                    "OK" -> { log("✓ badge FPS set to $fps"); setStatus("fps: $fps") }
                    "ERR" -> log("✗ badge rejected fps $fps")
                    else -> log("   fps -> ${resp ?: "<no reply>"}")
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun sendLedCommand(cmd: String) {
        val s = serial
        if (s == null || !s.isOpen) {
            log("Not connected — press Connect first")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            log("Busy — another operation is in progress")
            return
        }
        worker.execute {
            try {
                val drained = s.drainInput()
                if (drained > 0) log("   (drained $drained stale byte(s) first)")
                val w = s.write("$cmd\n".toByteArray(Charsets.US_ASCII))
                log("→ LED: $cmd  (TX ${w}B)")
                val resp = s.awaitResponse(LED_TOKENS, SERIAL_TIMEOUT_MS) { skipped ->
                    log("   · skip async: $skipped")
                }
                when (resp) {
                    "OK" -> {
                        log("✓ $cmd accepted")
                        setStatus("LED: ${cmd.removePrefix("led ")}")
                    }
                    "ERR" -> log("✗ $cmd -> ERR (rejected by badge — check the argument)")
                    else -> {
                        log("   $cmd -> ${resp ?: "<no OK/ERR within timeout>"}")
                        if (resp == null) explainNoReply(cmd)
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    /** Validate the "Solid colour" hex field, then send with the selected effect. */
    private fun onSolidClicked() {
        val raw = binding.solidHex.text.toString().trim().removePrefix("#")
        if (!raw.matches(Regex("[0-9a-fA-F]{6}"))) {
            log("Solid color must be 6 hex digits (RRGGBB), e.g. ff8000")
            toast("Enter a 6-digit hex color, e.g. ff8000")
            return
        }
        sendSolid(raw.lowercase())
    }

    /** Send `led <effect> <RRGGBB>` for a valid 6-hex color, where <effect>
     *  is determined by the current radio selection (flash/breathe/rotate). */
    private fun sendSolid(hex: String) {
        binding.solidHex.setText(hex)
        val effect = selectedEffect()
        sendLedCommand("led $effect $hex")
    }

    /** Which solid-color effect is currently selected. Firmware understands
     *  `led solid|flash|breathe|rotate <RRGGBB>` — we send the picked verb. */
    private fun selectedEffect(): String = when (binding.effectGroup.checkedRadioButtonId) {
        R.id.effectBreathe -> "breathe"
        R.id.effectRotate -> "rotate"
        else -> "flash"   // default and R.id.effectFlash
    }

    /** Convert a badge hue byte (0-255, matching `hue_base`/`hue_bound` in the
     *  Haploid struct) to a fully-saturated fully-bright ARGB color. Used to tint
     *  the little swatches next to the hue-slice sliders so users can see roughly
     *  what color they're picking. */
    private fun hueToArgb(hue: Int): Int {
        val degrees = (hue.coerceIn(0, 255) * 360f) / 255f
        return android.graphics.Color.HSVToColor(floatArrayOf(degrees, 1f, 1f))
    }

    // --- firmware: choices, download, guided flash ---------------------------

    /** Build one radio button per firmware variant and track the selection. */
    private fun populateFirmwareChoices() {
        val group = binding.firmwareChoices
        group.removeAllViews()
        FIRMWARES.forEachIndexed { index, fw ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${fw.title}\n${fw.blurb}"
                setPadding(paddingLeft, 12, paddingRight, 12)
                isChecked = fw == selectedFirmware
                tag = index
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { g: RadioGroup, checkedId: Int ->
            val idx = g.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            selectedFirmware = FIRMWARES[idx]
            log("Selected firmware: ${selectedFirmware.title}")
        }
    }

    /** "Download firmware only" button: fetch + unzip + save to Downloads. */
    private fun onDownloadOnlyClicked() {
        val fw = selectedFirmware
        withStoragePermission {
            downloadAndInstall(fw,
                progress = { log("   $it") },
                done = { ok, where ->
                    if (ok) {
                        toast("Saved to $where")
                        showExportResult(where)
                    } else {
                        toast("Download failed: $where")
                    }
                })
        }
    }

    /** Local extraction dir for a firmware variant: filesDir/firmware/<dir>/. */
    private fun firmwareLocalDir(fw: Firmware): File = File(File(filesDir, "firmware"), fw.dir)

    /**
     * Download `<fw>.zip` from the GitHub release, unzip its .uf2 files into
     * [firmwareLocalDir], validate them, then copy the set into
     * Downloads/dc34-firmware/<dir>/ so it can be copied onto the badge's UF2
     * update drive. Runs on the [net] thread; [progress]/[done] fire on the UI
     * thread. Guarded by [busy].
     */
    private fun downloadAndInstall(
        fw: Firmware,
        progress: (String) -> Unit,
        done: (Boolean, String) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            ui.post { progress("Busy — another operation is in progress"); done(false, "busy") }
            return
        }
        net.execute {
            fun p(s: String) = ui.post { progress(s) }
            try {
                val dir = firmwareLocalDir(fw)
                log("── Downloading ${fw.title} from ${fw.zipUrl} ──")
                p("Downloading…")
                val zip = httpGet(fw.zipUrl)
                log("Downloaded ${zip.size} B")

                p("Unzipping ${zip.size / 1024} KB…")
                if (dir.exists()) dir.deleteRecursively()
                val files = unzipToDir(zip, dir)
                val uf2 = files.filter { it.endsWith(".uf2") }
                if (uf2.isEmpty()) throw IOException("archive contained no .uf2 files")
                for (n in uf2) validateUf2(File(dir, n))
                log("Extracted to ${dir.absolutePath}: ${files.joinToString()}")

                p("Saving to Downloads…")
                val exportRel = "$FW_EXPORT_ROOT/${fw.dir}"
                var w = 0
                for (n in files.sorted()) {
                    val bytes = File(dir, n).readBytes()
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        writeToDownloadsQ(exportRel, n, bytes)
                    else writeToDownloadsLegacy(exportRel, n, bytes)
                    if (ok) w++ else log("   ✗ $n — write failed")
                }
                val where = "Downloads/$exportRel"
                log("Saved $w/${files.size} file(s) to $where")
                ui.post { done(true, where) }
            } catch (e: Exception) {
                log("ERROR download/install: ${e.message}")
                ui.post { done(false, e.message ?: "failed") }
            } finally {
                busy.set(false)
            }
        }
    }

    /** GET a URL into memory, following GitHub's redirects. */
    private fun httpGet(urlStr: String): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "dc34-flasher")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $urlStr")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Unzip [zipBytes] into [destDir], flattening entries to their basename
     *  (which also neutralises any zip-slip path). Returns the file names written. */
    private fun unzipToDir(zipBytes: ByteArray, destDir: File): List<String> {
        destDir.mkdirs()
        val written = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = File(entry.name).name
                    if (name.isNotEmpty()) {
                        File(destDir, name).outputStream().use { zin.copyTo(it) }
                        written.add(name)
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return written
    }

    /** Reject a file that is not a UF2 image (first block magic 0x0A324655). */
    private fun validateUf2(f: File) {
        val head = ByteArray(4)
        f.inputStream().use { it.read(head) }
        val ok = head[0] == 0x55.toByte() && head[1] == 0x46.toByte() &&
            head[2] == 0x32.toByte() && head[3] == 0x0A.toByte()
        if (!ok) throw IOException("${f.name} is not a valid UF2 image")
    }

    /**
     * The guided flash wizard: a step-by-step walkthrough with cached
     * illustrations. Step 1 downloads the firmware; the rest explain update
     * mode, copying the files, waiting for the (large) swap.uf2 to flush, and
     * rebooting.
     */
    private fun showFlashWizard() {
        val fw = selectedFirmware
        val steps = buildWizardSteps(fw)

        val view = layoutInflater.inflate(R.layout.dialog_flash_wizard, null)
        val wizStep = view.findViewById<TextView>(R.id.wizStep)
        val wizTitle = view.findViewById<TextView>(R.id.wizTitle)
        val wizBody = view.findViewById<TextView>(R.id.wizBody)
        val wizImage = view.findViewById<ImageView>(R.id.wizImage)
        val wizImageSpinner = view.findViewById<ProgressBar>(R.id.wizImageSpinner)
        val wizAction = view.findViewById<Button>(R.id.wizAction)
        val wizProgress = view.findViewById<ProgressBar>(R.id.wizProgress)
        val wizActionStatus = view.findViewById<TextView>(R.id.wizActionStatus)
        val wizBack = view.findViewById<Button>(R.id.wizBack)
        val wizNext = view.findViewById<Button>(R.id.wizNext)
        val wizClose = view.findViewById<Button>(R.id.wizClose)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        var index = 0
        fun render(i: Int) {
            val st = steps[i]
            wizStep.text = "Step ${i + 1} of ${steps.size} — ${fw.title}"
            wizTitle.text = st.title
            wizBody.text = st.body
            wizBack.isEnabled = i > 0
            wizNext.text = if (i == steps.lastIndex) "Done" else "Next"

            // Illustration (cached; downloaded on first view).
            wizImage.visibility = View.GONE
            wizImageSpinner.visibility = View.GONE
            if (st.imageUrl != null) {
                wizImageSpinner.visibility = View.VISIBLE
                ensureImage(st.imageUrl) { f ->
                    wizImageSpinner.visibility = View.GONE
                    if (f != null) {
                        wizImage.setImageURI(null)
                        wizImage.setImageURI(Uri.fromFile(f))
                        wizImage.visibility = View.VISIBLE
                    }
                }
            }

            // Action button (download / open Downloads).
            wizProgress.visibility = View.GONE
            wizActionStatus.visibility = View.GONE
            when (st.action) {
                WizAction.NONE -> wizAction.visibility = View.GONE
                WizAction.DOWNLOAD -> {
                    wizAction.visibility = View.VISIBLE
                    wizAction.text = "Download & save firmware"
                    wizAction.setOnClickListener {
                        startWizardDownload(fw, wizAction, wizProgress, wizActionStatus)
                    }
                }
                WizAction.OPEN_DOWNLOADS -> {
                    wizAction.visibility = View.VISIBLE
                    wizAction.text = "Open Downloads"
                    wizAction.setOnClickListener { openDownloads() }
                }
            }
        }

        wizBack.setOnClickListener { if (index > 0) { index--; render(index) } }
        wizNext.setOnClickListener {
            if (index < steps.lastIndex) { index++; render(index) } else dialog.dismiss()
        }
        wizClose.setOnClickListener { dialog.dismiss() }

        render(0)
        dialog.show()
    }

    private enum class WizAction { NONE, DOWNLOAD, OPEN_DOWNLOADS }

    private data class WizStep(
        val title: String,
        val body: String,
        val imageUrl: String?,
        val action: WizAction,
    )

    private fun buildWizardSteps(fw: Firmware): List<WizStep> {
        val dest = "Downloads/$FW_EXPORT_ROOT/${fw.dir}"
        return listOf(
            WizStep(
                title = "1. Get the firmware",
                body = "You're installing: ${fw.title}.\n\n${fw.blurb}\n\n" +
                    "Tap the button below — the app downloads the signed firmware from " +
                    "GitHub, unpacks it, and saves loader.uf2, xous.uf2 and swap.uf2 into " +
                    "$dest ready to copy across.",
                imageUrl = null,
                action = WizAction.DOWNLOAD,
            ),
            WizStep(
                title = "2. Put the badge in update mode",
                body = "With the badge powered on:\n\n" +
                    "(1) Hold down any badge button and KEEP holding it.\n" +
                    "(2) While still holding, press the RESET button on the RIGHT side of the " +
                    "badge.\n" +
                    "(3) Now plug the badge into USB.\n\n" +
                    "The badge should appear on your phone/computer as a USB MASS STORAGE " +
                    "device (like a tiny flash drive). If it doesn't, unplug and repeat — " +
                    "the button must stay held from before the reset all the way through the " +
                    "USB plug-in.\n\n" +
                    "Still not showing up? Try the cold-boot fallback: pop a battery out, hold " +
                    "any badge button, put the battery back in WHILE STILL HOLDING the button, " +
                    "then plug in the USB cable (button still held). This forces the ROM " +
                    "bootloader before any firmware runs.\n\n" +
                    "You can't brick the badge this way — the ROM update mode always works.",
                imageUrl = IMG_UPDATE_MODE,
                action = WizAction.NONE,
            ),
            WizStep(
                title = "3. Copy the three files across",
                body = "Open the badge's USB drive in your Files app. Copy ALL THREE files " +
                    "— loader.uf2, xous.uf2 and swap.uf2 — from $dest onto the badge drive.\n\n" +
                    "Always copy the whole set from one folder; never mix files from " +
                    "different firmware or the badge will bootloop.",
                imageUrl = null,
                action = WizAction.OPEN_DOWNLOADS,
            ),
            WizStep(
                title = "4. Wait for the write to finish",
                body = "There's no on-badge progress bar — but if you look at the badge drive " +
                    "in your Files app you should see 3 files being written (loader.uf2, " +
                    "xous.uf2, swap.uf2). swap.uf2 is the big one (~2.3 MB).\n\n" +
                    "Watch the file sizes: while flashing they'll keep growing. Once ALL " +
                    "THREE file sizes stop increasing and stay stable, the write is done — " +
                    "then continue to the next step.\n\n" +
                    "If your Files app lets you EJECT / “safely remove” the drive, do that " +
                    "first — it flushes any cached bytes onto the badge and prevents a " +
                    "partial flash. (On Android Files this is usually not required, on " +
                    "desktops it is.)",
                imageUrl = IMG_FLASHED,
                action = WizAction.NONE,
            ),
            WizStep(
                title = "5. Reboot & enjoy",
                body = "Press any badge button (PROG / reset) to commit and reboot into the " +
                    "new firmware.\n\n" +
                    "If it doesn't boot, just re-enter update mode and flash the Official " +
                    "firmware — the ROM update mode can't be bricked.\n\n" +
                    "⚠ One-way note: the badge erases its factory security secrets the first " +
                    "time any developer image boots (the official image included). " +
                    "Re-flashing won't restore them.",
                imageUrl = null,
                action = WizAction.NONE,
            ),
        )
    }

    /** Wizard "Download & save" action: drive the shared install with the
     *  dialog's own progress bar + status line. */
    private fun startWizardDownload(
        fw: Firmware,
        action: Button,
        progressBar: ProgressBar,
        status: TextView,
    ) {
        withStoragePermission {
            action.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            status.visibility = View.VISIBLE
            status.text = "Starting…"
            downloadAndInstall(fw,
                progress = { status.text = it },
                done = { ok, where ->
                    progressBar.visibility = View.GONE
                    action.isEnabled = true
                    if (ok) {
                        action.text = "Downloaded ✓ (tap to re-download)"
                        status.text = "Saved to $where"
                        toast("Firmware saved to $where")
                    } else {
                        action.text = "Retry download"
                        status.text = "Failed: $where"
                    }
                })
        }
    }

    /** Ensure the storage permission on Android ≤9, then run [action]. On Android
     *  10+ the MediaStore Downloads API needs no runtime permission. */
    private var pendingStorageAction: (() -> Unit)? = null
    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted && action != null) {
                action()
            } else if (!granted) {
                log("Storage permission denied — cannot write to Downloads on this Android version")
                toast("Storage permission needed to save the firmware")
            }
        }

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageAction = action
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    /** Cache a remote image under cacheDir and hand back the file (or null on
     *  failure). Fetches on the [net] thread; [onReady] fires on the UI thread. */
    private fun ensureImage(url: String, onReady: (File?) -> Unit) {
        val target = File(cacheDir, "wiz_" + url.substringAfterLast('/').substringBefore('?'))
        if (target.exists() && target.length() > 0) {
            onReady(target)
            return
        }
        net.execute {
            try {
                val bytes = httpGet(url)
                target.outputStream().use { it.write(bytes) }
                ui.post { onReady(target) }
            } catch (e: Exception) {
                log("Could not cache illustration ($url): ${e.message}")
                ui.post { onReady(null) }
            }
        }
    }

    /** Android 10+ : insert into MediaStore Downloads under a subfolder. Deletes
     *  any prior copy of the same name in that folder so re-runs don't pile up. */
    private fun writeToDownloadsQ(exportDir: String, name: String, bytes: ByteArray): Boolean {
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = Environment.DIRECTORY_DOWNLOADS + "/" + exportDir

        try {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$relPath/", name),
            )
        } catch (_: Exception) {
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri).use { out ->
            if (out == null) return false
            out.write(bytes)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    /** Android ≤9 : write straight into the public Downloads directory. */
    private fun writeToDownloadsLegacy(exportDir: String, name: String, bytes: ByteArray): Boolean {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            exportDir,
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        File(dir, name).outputStream().use { it.write(bytes) }
        return true
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".uf2") -> "application/octet-stream"
        name.endsWith(".md") || name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    /** Post-download summary with a shortcut to open the Downloads UI. */
    private fun showExportResult(where: String) {
        val msg = "The firmware (loader.uf2, xous.uf2, swap.uf2) is saved to:\n$where\n\n" +
            "Next: put the badge in update mode (hold a button while plugging in), copy all " +
            "three files onto the badge drive, then EJECT the badge drive in Files (flushes " +
            "swap.uf2 — skipping this bootloops it) and press a badge button to reboot.\n\n" +
            "The “Flash firmware — guide me” button walks you through it with pictures."
        AlertDialog.Builder(this)
            .setTitle("Firmware saved")
            .setMessage(msg)
            .setPositiveButton("Open Downloads") { _, _ -> openDownloads() }
            .setNegativeButton("OK", null)
            .show()
    }

    /** Open the system Downloads UI so the user can grab the files to copy over. */
    private fun openDownloads() {
        try {
            startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            log("Could not open Downloads UI: ${e.message} — browse to Downloads/$FW_EXPORT_ROOT manually")
            toast("Open your Files app → Downloads/$FW_EXPORT_ROOT")
        }
    }

    private fun toast(text: String) = ui.post {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    // --- helpers -------------------------------------------------------------

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun hexPreview(b: ByteArray): String {
        val head = b.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        val tail = b.takeLast(4).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        return "$head … $tail"
    }

    private fun setStatus(text: String) = ui.post {
        binding.statusText.text = "Status: $text"
    }

    /** Append a timestamped line to the debug log, auto-scroll, and cap length. */
    private fun log(message: String) {
        val line = "${timeFmt.format(Date())}  $message\n"
        ui.post {
            binding.logText.append(line)
            val tv = binding.logText
            if (tv.length() > 60_000) {
                val txt = tv.text.toString()
                tv.text = txt.substring(txt.length - 40_000)
            }
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}
