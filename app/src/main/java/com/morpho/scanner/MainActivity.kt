package com.morpho.scanner

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.morpho.morphosmart.sdk.ErrorCodes
import com.morpho.morphosmart.sdk.MorphoDevice
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MorphoScanner"
        private const val ACTION_USB_PERMISSION = "com.morpho.scanner.USB_PERMISSION"
        const val MORPHO_VENDOR_ID = 1947 // 0x079B Safran Morpho

        // Brand Theme Palette (Obsidian Black, Deep Crimson, Blood Red, Soft White, Muted Grey)
        val COLOR_BLOOD_RED = Color.parseColor("#C1121F")
        val COLOR_DEEP_CRIMSON = Color.parseColor("#8B0000")
        val COLOR_MUTED_GREY = Color.parseColor("#85858D")
        val COLOR_SUCCESS_GREEN = Color.parseColor("#10B981")
        val COLOR_WARNING_AMBER = Color.parseColor("#F59E0B")
    }

    private lateinit var usbManager: UsbManager
    private var targetDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var activeMorphoDevice: MorphoDevice? = null

    private lateinit var fingerprintImage: ImageView
    private lateinit var placeholderIcon: ImageView
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button
    private lateinit var btnDemoScan: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnCopyError: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvImageInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var statusIndicator: View
    private lateinit var cardErrorBanner: View
    private lateinit var tvErrorTitle: TextView
    private lateinit var tvErrorText: TextView

    private val fullLogHistory = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            appendLog("Broadcast received: $action")

            when (action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = getDeviceFromIntent(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        appendLog("USB Permission GRANTED for ${device.deviceName}")
                        targetDevice = device
                        startDeviceConnection(device)
                    } else {
                        appendLog("USB Permission was DENIED by the user.")
                        updateStatus("Permission Denied", false, COLOR_BLOOD_RED)
                        showErrorBanner(
                            "USB Access Denied",
                            "Permission to access the Morpho scanner was denied. Please re-plug the USB OTG cable and tap 'Allow' when the system prompt appears."
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = getDeviceFromIntent(intent)
                    if (device != null && device.vendorId == MORPHO_VENDOR_ID) {
                        appendLog("Morpho device attached: ${device.deviceName} (VID: ${device.vendorId})")
                        targetDevice = device
                        handleDeviceFound(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = getDeviceFromIntent(intent)
                    if (device != null && (targetDevice == null || device.deviceId == targetDevice?.deviceId)) {
                        appendLog("Morpho device detached. Cleaning up session.")
                        releaseDeviceSession()
                        updateStatus("Status: Scanner Disconnected", false, COLOR_MUTED_GREY)
                        tvDeviceInfo.text = "Morpho MSO 1300 E2 (Disconnected)"
                        hideErrorBanner()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Global crash guard to capture and persist any uncaught errors
        setupCrashHandler()

        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        bindViews()
        setupListeners()
        registerUsbReceiver()

        // Check if there was a recorded crash from the previous execution
        checkPreviousCrashLog()

        // Early preload of Morpho native drivers with diagnostics logging
        MorphoSdkHelper.checkSdkAvailability(this) { msg ->
            appendLog(msg)
        }

        // Handle intent if launched directly via USB attachment
        handleInitialIntent(intent)

        // Check for already connected devices
        checkAndDetectDevice()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        appendLog("Activity received new intent: ${intent?.action}")
        handleInitialIntent(intent)
    }

    private fun handleInitialIntent(intent: Intent?) {
        if (intent == null) return
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device: UsbDevice? = getDeviceFromIntent(intent)
            if (device != null && device.vendorId == MORPHO_VENDOR_ID) {
                appendLog("Launched via USB_DEVICE_ATTACHED for ${device.deviceName}")
                targetDevice = device
                handleDeviceFound(device)
            }
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            try {
                val crashFile = java.io.File(filesDir, "last_crash.log")
                crashFile.writeText("Crash on thread [${thread.name}]:\n${throwable.message}\n${throwable.stackTraceToString()}")
            } catch (_: Throwable) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun checkPreviousCrashLog() {
        try {
            val crashFile = java.io.File(filesDir, "last_crash.log")
            if (crashFile.exists()) {
                val crashText = crashFile.readText()
                crashFile.delete()
                appendLog("⚠️ Previous session crash report:\n$crashText")
            }
        } catch (_: Throwable) {}
    }

    private fun bindViews() {
        fingerprintImage = findViewById(R.id.fingerprintImage)
        placeholderIcon = findViewById(R.id.placeholderIcon)
        btnConnect = findViewById(R.id.btnConnect)
        btnScan = findViewById(R.id.btnScan)
        btnDemoScan = findViewById(R.id.btnDemoScan)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCopyError = findViewById(R.id.btnCopyError)
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvImageInfo = findViewById(R.id.tvImageInfo)
        tvLog = findViewById(R.id.tvLog)
        statusIndicator = findViewById(R.id.statusIndicator)
        cardErrorBanner = findViewById(R.id.cardErrorBanner)
        tvErrorTitle = findViewById(R.id.tvErrorTitle)
        tvErrorText = findViewById(R.id.tvErrorText)

        updateStatus("Status: Scanner Disconnected", false, COLOR_MUTED_GREY)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            appendLog("Manual connect clicked. Enumerating USB devices...")
            checkAndDetectDevice(forcePrompt = true)
        }

        btnScan.setOnClickListener {
            captureFingerprint()
        }

        btnDemoScan.setOnClickListener {
            simulateDemoCapture()
        }

        btnCopyLogs.setOnClickListener {
            val contentToCopy = if (fullLogHistory.isNotBlank()) {
                fullLogHistory.toString().trim()
            } else {
                tvLog.text.toString().trim()
            }
            copyToClipboard("Morpho Scanner Diagnostics", contentToCopy)
            btnCopyLogs.text = "✓ Copied!"
            mainHandler.postDelayed({ btnCopyLogs.text = "📋 Copy Logs" }, 1500)
            Toast.makeText(this, "Diagnostics log copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            fullLogHistory.clear()
            tvLog.text = "Logs cleared."
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        btnCopyError.setOnClickListener {
            val noticeText = "${tvErrorTitle.text}\n\n${tvErrorText.text}"
            copyToClipboard("Morpho Scanner Driver Notice", noticeText)
            btnCopyError.text = "✓ Copied!"
            mainHandler.postDelayed({ btnCopyError.text = "📋 Copy Notice" }, 1500)
            Toast.makeText(this, "Driver notice copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerUsbReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // Must use RECEIVER_EXPORTED for ACTION_USB_PERMISSION from system service on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun checkAndDetectDevice(forcePrompt: Boolean = false) {
        val deviceList = usbManager.deviceList
        appendLog("USB scan: ${deviceList.size} device(s) found.")

        var morphoFound: UsbDevice? = null
        for (device in deviceList.values) {
            appendLog("Device: ${device.deviceName} (VID: ${device.vendorId}, PID: ${device.productId})")
            if (device.vendorId == MORPHO_VENDOR_ID) {
                morphoFound = device
                break
            }
        }

        if (morphoFound != null) {
            targetDevice = morphoFound
            appendLog("Safran Morpho MSO 1300 E2 identified (VID: 1947).")
            handleDeviceFound(morphoFound)
        } else {
            targetDevice = null
            tvDeviceInfo.text = "No scanner detected. Connect Morpho USB OTG or use Simulate."
            updateStatus("Scanner Not Found", false, COLOR_WARNING_AMBER)
            if (forcePrompt) {
                Toast.makeText(this, "No Morpho scanner detected on USB OTG", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleDeviceFound(device: UsbDevice) {
        runOnUiThread {
            tvDeviceInfo.text = "Morpho MSO 1300 E2 (VID: ${device.vendorId}, PID: ${device.productId})"
        }

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
        } else {
            appendLog("USB permission already active for ${device.deviceName}")
            startDeviceConnection(device)
        }
    }

    /**
     * CRITICAL: Uses explicit Intent with setPackage to prevent Android 14 IllegalArgumentException crash.
     */
    private fun requestUsbPermission(device: UsbDevice) {
        try {
            appendLog("Requesting USB permission for ${device.deviceName}...")
            updateStatus("Requesting Permission...", false, COLOR_BLOOD_RED)

            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName) // Mandatory on Android 14+ for FLAG_MUTABLE
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed requesting USB permission", e)
            appendLog("Permission request failed: ${e.message}")
            showErrorBanner("Permission Error", "Unable to trigger USB permission: ${e.message}")
        }
    }

    /**
     * Initiates asynchronous hardware connection on a worker thread with concurrency deduplication.
     */
    private fun startDeviceConnection(device: UsbDevice) {
        if (!isConnecting.compareAndSet(false, true)) {
            appendLog("Connection attempt already in progress, skipping duplicate event.")
            return
        }

        updateStatus("Connecting to Morpho Scanner...", false, COLOR_BLOOD_RED)

        thread(name = "MorphoConnectThread") {
            try {
                performDeviceConnection(device)
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled error during device connection", t)
                mainHandler.post {
                    appendLog("Connection exception: ${t.message}")
                    showErrorBanner("Connection Failure", t.message ?: "Unknown error")
                    updateStatus("Connection Failed", false, COLOR_BLOOD_RED)
                }
            } finally {
                isConnecting.set(false)
            }
        }
    }

    /**
     * Connects to the Morpho hardware.
     * CRITICAL: Interface 0 must NOT be claimed in Java via connection.claimInterface(),
     * because libusb inside the native Morpho driver requires exclusive rights to claim the interface.
     */
    private fun performDeviceConnection(device: UsbDevice) {
        // Step 1: Clean up any prior session
        releaseDeviceSession()

        // Step 2: Open UsbDeviceConnection from Android UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            mainHandler.post {
                appendLog("Failed to open UsbDeviceConnection.")
                updateStatus("USB Busy / Inaccessible", false, COLOR_BLOOD_RED)
                showErrorBanner(
                    "USB Connection Blocked",
                    "Cannot open Morpho scanner USB port. If an official UIDAI/Morpho RD Service is installed on this phone, please force-stop it so this tool can access the hardware."
                )
            }
            return
        }
        usbConnection = connection

        // Step 3: Inspect hardware interface & endpoints WITHOUT claiming them
        inspectUsbHardware(device)

        // Step 4: Extract bus, address, fd for Morpho SDK
        val fd = connection.fileDescriptor
        val (bus, address) = parseBusAndAddress(device.deviceName)

        mainHandler.post {
            appendLog("Opening Morpho SDK with FD: bus=$bus, addr=$address, fd=$fd")
        }

        // Step 5: Check native library availability
        val (sdkAvailable, sdkReason) = MorphoSdkHelper.checkSdkAvailability(this) { msg ->
            mainHandler.post { appendLog(msg) }
        }
        if (!sdkAvailable) {
            mainHandler.post {
                appendLog("SDK notice: $sdkReason")
                updateStatus("Morpho USB Ready (Direct Mode)", true, COLOR_SUCCESS_GREEN)
                showErrorBanner("Native SDK Libraries Needed For Hardware Capture", sdkReason)
            }
            return
        }

        // Step 6: Connect MorphoDevice via openUsbDeviceFD
        var (morphoDev, code) = MorphoSdkHelper.openDeviceFd(this, bus, address, fd) { msg ->
            mainHandler.post { appendLog(msg) }
        }

        // Fallback: If openUsbDeviceFD did not succeed, try native enumeration & openDevice
        if (code != ErrorCodes.MORPHO_OK || morphoDev == null) {
            mainHandler.post {
                appendLog("openUsbDeviceFD returned $code. Trying device enumeration fallback...")
            }
            val (altDev, altCode) = MorphoSdkHelper.openDeviceByName(this) { msg ->
                mainHandler.post { appendLog(msg) }
            }
            if (altCode == ErrorCodes.MORPHO_OK && altDev != null) {
                morphoDev = altDev
                code = altCode
            }
        }

        if (code == ErrorCodes.MORPHO_OK && morphoDev != null) {
            activeMorphoDevice = morphoDev
            mainHandler.post {
                appendLog("MorphoDevice opened successfully (MORPHO_OK).")
                updateStatus("Morpho MSO 1300 E2 Ready", true, COLOR_SUCCESS_GREEN)
                hideErrorBanner()
                Toast.makeText(this, "Morpho Scanner Connected & Ready!", Toast.LENGTH_SHORT).show()
            }
        } else {
            mainHandler.post {
                appendLog("MorphoDevice open returned code: $code")
                updateStatus("Init Code: $code", true, COLOR_WARNING_AMBER)
                showErrorBanner("Device Initialization Notice", "Morpho initialization returned code $code.")
            }
        }
    }

    /**
     * Inspects interfaces and endpoints from Android descriptor metadata.
     * NEVER claims the interface here, avoiding resource locks with libusb.
     */
    private fun inspectUsbHardware(device: UsbDevice) {
        try {
            val ifaceCount = device.interfaceCount
            mainHandler.post {
                appendLog("USB Device Interfaces: $ifaceCount")
            }
            if (ifaceCount > 0) {
                for (i in 0 until ifaceCount) {
                    val intf: UsbInterface = device.getInterface(i)
                    mainHandler.post {
                        appendLog("  Interface $i: class=${intf.interfaceClass}, sub=${intf.interfaceSubclass}, endpoints=${intf.endpointCount}")
                    }
                    for (j in 0 until intf.endpointCount) {
                        val ep: UsbEndpoint = intf.getEndpoint(j)
                        val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                        mainHandler.post {
                            appendLog("    Endpoint $j: $dir, maxPacket=${ep.maxPacketSize}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error inspecting USB hardware", t)
        }
    }

    private fun parseBusAndAddress(deviceName: String): Pair<Int, Int> {
        return try {
            val parts = deviceName.split("/").filter { it.isNotBlank() }
            val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
            val address = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: 2
            Pair(if (bus > 0) bus else 1, if (address > 0) address else 2)
        } catch (_: Exception) {
            Pair(1, 2)
        }
    }

    /**
     * Executes raw biometric capture. If native SDK is linked, uses morphoDevice.getImage(10000, morphoImage).
     * If native SDK .so is missing on host, falls back gracefully without crashing.
     */
    private fun captureFingerprint() {
        val device = activeMorphoDevice
        val (sdkAvailable, reason) = MorphoSdkHelper.checkSdkAvailability(this) { msg ->
            appendLog(msg)
        }

        if (!sdkAvailable || device == null) {
            appendLog("Physical capture paused: $reason")
            showErrorBanner("Driver Notice", "$reason\n\nYou can click 'Simulate Test Scan' to test the raw decoding and MediaStore saving pipeline.")
            Toast.makeText(this, "SDK native driver not found. Try 'Simulate Test Scan'.", Toast.LENGTH_LONG).show()
            return
        }

        updateStatus("Capturing... Place finger on sensor", true, COLOR_BLOOD_RED)
        appendLog("Acquiring raw image from Morpho scanner (timeout: 10,000ms)...")

        thread(name = "BiometricCaptureThread") {
            try {
                val (retCode, morphoImage) = MorphoSdkHelper.captureRawImage(device, 10000)

                if (retCode == ErrorCodes.MORPHO_OK && morphoImage != null) {
                    val rawBytes: ByteArray? = morphoImage.image ?: morphoImage.compressedImage
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        val header = morphoImage.morphoImageHeader
                        val width = if (header != null && header.nbColumn > 0) header.nbColumn else 256
                        val height = if (header != null && header.nbRow > 0) header.nbRow else 360

                        mainHandler.post {
                            appendLog("Raw capture SUCCESS: ${width}x${height} (${rawBytes.size} bytes).")
                            val bitmap = decodeRawToBitmap(rawBytes, width, height)
                            displayFingerprint(bitmap, "${width}x${height} raw 8-bit (${rawBytes.size} bytes)")
                            saveFingerprintToStorage(bitmap, "Raw_Scan")
                            updateStatus("Fingerprint Captured & Saved", true, COLOR_SUCCESS_GREEN)
                        }
                    } else {
                        mainHandler.post {
                            appendLog("Capture returned MORPHO_OK but raw buffer was null.")
                            updateStatus("No Image Data", true, COLOR_WARNING_AMBER)
                        }
                    }
                } else {
                    mainHandler.post {
                        appendLog("Scanner capture returned code: $retCode")
                        updateStatus("Scan Failed (Code $retCode)", true, COLOR_BLOOD_RED)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error during capture", t)
                mainHandler.post {
                    appendLog("Capture exception: ${t.message}")
                    updateStatus("Capture Error", false, COLOR_BLOOD_RED)
                }
            }
        }
    }

    private fun decodeRawToBitmap(rawBytes: ByteArray, width: Int, height: Int): Bitmap {
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        if (decoded != null) return decoded

        val actualWidth = if (width > 0) width else 256
        val actualHeight = if (height > 0) height else if (actualWidth > 0 && rawBytes.isNotEmpty()) rawBytes.size / actualWidth else 360
        val bitmap = Bitmap.createBitmap(actualWidth, actualHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(actualWidth * actualHeight)
        val count = minOf(pixels.size, rawBytes.size)

        for (i in 0 until count) {
            val gray = rawBytes[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        bitmap.setPixels(pixels, 0, actualWidth, 0, 0, actualWidth, actualHeight)
        return bitmap
    }

    private fun saveFingerprintToStorage(bitmap: Bitmap, prefix: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "Morpho_${prefix}_${timestamp}.png"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MorphoFingerprints")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                var stream: OutputStream? = null
                try {
                    stream = contentResolver.openOutputStream(uri)
                    if (stream != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                } finally {
                    stream?.close()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }

                appendLog("SAVED: Pictures/MorphoFingerprints/$filename")
                Toast.makeText(this, "Saved to Pictures/MorphoFingerprints!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save error", e)
            appendLog("Save error: ${e.message}")
        }
    }

    private fun displayFingerprint(bitmap: Bitmap, info: String) {
        placeholderIcon.visibility = View.GONE
        fingerprintImage.visibility = View.VISIBLE
        fingerprintImage.setImageBitmap(bitmap)
        tvImageInfo.text = info
    }

    private fun simulateDemoCapture() {
        appendLog("Simulating biometric acquisition pipeline...")
        updateStatus("Simulating Raw Capture...", true, COLOR_BLOOD_RED)

        thread {
            val width = 256
            val height = 360
            val rawBytes = generateProceduralFingerprint(width, height)

            Thread.sleep(500)

            mainHandler.post {
                val bitmap = decodeRawToBitmap(rawBytes, width, height)
                displayFingerprint(bitmap, "${width}x${height} raw 8-bit simulated (${rawBytes.size} bytes)")
                saveFingerprintToStorage(bitmap, "Simulated")
                updateStatus("Simulated Capture Saved", true, COLOR_SUCCESS_GREEN)
                appendLog("Simulated capture successfully decoded & saved to storage.")
            }
        }
    }

    private fun generateProceduralFingerprint(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val centerX = width / 2f
        val centerY = height / 2f + 20f

        for (r in 12..140 step 7) {
            val rx = r.toFloat()
            val ry = r.toFloat() * 1.35f
            canvas.drawOval(centerX - rx, centerY - ry, centerX + rx, centerY + ry, paint)
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rawBytes = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114).toInt()
            rawBytes[i] = gray.toByte()
        }

        return rawBytes
    }

    private fun updateStatus(text: String, isConnected: Boolean, indicatorColor: Int) {
        tvStatus.text = text
        statusIndicator.setBackgroundColor(indicatorColor)
        btnScan.isEnabled = isConnected
    }

    private fun showErrorBanner(title: String, message: String) {
        tvErrorTitle.text = title
        tvErrorText.text = message
        cardErrorBanner.visibility = View.VISIBLE
    }

    private fun hideErrorBanner() {
        cardErrorBanner.visibility = View.GONE
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val logLine = "[$time] $message"
        fullLogHistory.append(logLine).append("\n")

        val existing = tvLog.text.toString()
        val lines = existing.split("\n").takeLast(8)
        tvLog.text = (lines + logLine).joinToString("\n")
    }

    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard copy error", e)
        }
    }

    private fun getDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    @Synchronized
    private fun releaseDeviceSession() {
        try {
            MorphoSdkHelper.closeDevice(activeMorphoDevice)
        } catch (_: Throwable) {}
        activeMorphoDevice = null

        try {
            usbConnection?.close()
        } catch (_: Throwable) {}
        usbConnection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver)
            } catch (_: Throwable) {}
            isReceiverRegistered = false
        }
        releaseDeviceSession()
    }
}
