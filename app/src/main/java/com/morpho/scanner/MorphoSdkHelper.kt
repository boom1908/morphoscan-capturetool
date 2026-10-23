package com.morpho.scanner

import android.content.Context
import android.util.Log
import com.morpho.morphosmart.sdk.CompressionAlgorithm
import com.morpho.morphosmart.sdk.ErrorCodes
import com.morpho.morphosmart.sdk.LatentDetection
import com.morpho.morphosmart.sdk.MorphoDevice
import com.morpho.morphosmart.sdk.MorphoImage
import java.io.File

/**
 * Isolated wrapper for MorphoSmart SDK interactions.
 * Preloads dependent shared libraries directly via System.load with absolute file paths
 * to satisfy Android Bionic linker namespace requirements (clns).
 */
object MorphoSdkHelper {

    private const val TAG = "MorphoSdkHelper"

    private var sdkAvailable: Boolean = false
    private var sdkUnavailableReason: String = "Not initialized"

    /**
     * Preloads native libraries in strict dependency order:
     * 1. libc++_shared.so (LLVM C++ STL runtime required by all Morpho libs)
     * 2. libstdc++.so (Compatibility stub for libusb1.0)
     * 3. libusb1.0.so (Low-level USB transport)
     * 4. libSecuHost.so (Security host library)
     * 5. libMSO_Secu.so (MSO security library)
     * 6. libMSO100.so (Morpho MSO driver)
     * 7. libNativeMorphoSmartSDK_6.42.0.0.so (JNI bridge for MorphoDevice)
     */
    private fun preloadNativeLibraries(
        context: Context,
        logCallback: ((String) -> Unit)?
    ): Boolean {
        fun log(msg: String) {
            Log.i(TAG, msg)
            logCallback?.invoke(msg)
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        log("Native library path: ${nativeDir.absolutePath}")

        val existingFiles = if (nativeDir.exists()) {
            nativeDir.list()?.toList() ?: emptyList()
        } else {
            emptyList()
        }
        log("Extracted in nativeDir (${existingFiles.size}): ${existingFiles.joinToString(", ")}")

        // First ensure system C++ runtime is available for libusb1.0
        try {
            System.loadLibrary("stdc++")
            log("✓ System runtime: stdc++")
        } catch (_: Throwable) {
            // Standalone stdc++ may not be present on newer Android versions
        }

        val libOrder = listOf(
            "libc++_shared.so",
            "libusb1.0.so",
            "libSecuHost.so",
            "libMSO_Secu.so",
            "libMSO100.so",
            "libNativeMorphoSmartSDK_6.42.0.0.so"
        )

        var allLoaded = true
        val failedLibs = mutableListOf<String>()

        // Open app base.apk for extraction fallback if any library is missing from nativeDir
        val apkFile = File(context.applicationInfo.sourceDir)
        var apkZip: java.util.zip.ZipFile? = null
        try {
            if (apkFile.exists()) {
                apkZip = java.util.zip.ZipFile(apkFile)
            }
        } catch (t: Throwable) {
            log("Notice: Unable to open base.apk: ${t.message}")
        }

        try {
            for (libFileName in libOrder) {
                val fileInNativeDir = File(nativeDir, libFileName)
                var loaded = false

                // Method 1: Load directly from nativeLibraryDir (/data/app/.../lib/arm64)
                if (fileInNativeDir.exists()) {
                    try {
                        System.load(fileInNativeDir.absolutePath)
                        log("✓ System.load: $libFileName")
                        loaded = true
                    } catch (t: Throwable) {
                        log("System.load($libFileName) failed: ${t.message}")
                    }
                }

                // Method 2: Extract directly from APK (lib/arm64-v8a/...) to codeCacheDir
                if (!loaded && apkZip != null) {
                    val fallbackFile = File(context.codeCacheDir, libFileName)
                    try {
                        val entry = apkZip.getEntry("lib/arm64-v8a/$libFileName")
                            ?: apkZip.getEntry("lib/arm64/$libFileName")

                        if (entry != null) {
                            apkZip.getInputStream(entry).use { input ->
                                fallbackFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            fallbackFile.setReadable(true, false)
                            fallbackFile.setExecutable(true, false)

                            System.load(fallbackFile.absolutePath)
                            log("✓ Extracted from APK & loaded: $libFileName")
                            loaded = true
                        } else {
                            log("Entry lib/arm64-v8a/$libFileName not found in APK")
                        }
                    } catch (t: Throwable) {
                        log("APK extraction load failed for $libFileName: ${t.message}")
                    }
                }

                // Method 3: Standard System.loadLibrary fallback
                if (!loaded) {
                    val shortName = libFileName.removePrefix("lib").removeSuffix(".so")
                    try {
                        System.loadLibrary(shortName)
                        log("✓ System.loadLibrary: $shortName")
                        loaded = true
                    } catch (t: Throwable) {
                        log("✗ Failed loading $libFileName: ${t.message}")
                        allLoaded = false
                        failedLibs.add(libFileName)
                    }
                }
            }
        } finally {
            try {
                apkZip?.close()
            } catch (_: Throwable) {}
        }

        if (!allLoaded) {
            log("Native preloading incomplete. Missing: ${failedLibs.joinToString(", ")}")
        }

        return allLoaded
    }

    /**
     * Checks whether the native Morpho shared libraries can be linked.
     */
    fun checkSdkAvailability(
        context: Context,
        forceReload: Boolean = false,
        logCallback: ((String) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (sdkAvailable && !forceReload) {
            return Pair(true, sdkUnavailableReason)
        }

        try {
            val preloaded = preloadNativeLibraries(context, logCallback)
            if (!preloaded) {
                sdkAvailable = false
                sdkUnavailableReason = "Native Morpho drivers missing: libc++_shared.so or dependent libraries failed to load."
                logCallback?.invoke(sdkUnavailableReason)
                return Pair(false, sdkUnavailableReason)
            }

            // Test instantiation of MorphoDevice to verify JNI bindings
            val testDevice = MorphoDevice()
            testDevice.closeDevice()

            sdkAvailable = true
            sdkUnavailableReason = "MorphoSmart SDK and native drivers loaded successfully."
            logCallback?.invoke("MorphoDevice initialized successfully (JNI OK).")
        } catch (e: UnsatisfiedLinkError) {
            sdkAvailable = false
            sdkUnavailableReason = "Native Morpho drivers missing: ${e.message ?: "UnsatisfiedLinkError"}."
            Log.w(TAG, sdkUnavailableReason, e)
            logCallback?.invoke("UnsatisfiedLinkError: ${e.message}")
        } catch (e: ExceptionInInitializerError) {
            sdkAvailable = false
            val cause = e.cause?.message ?: e.message ?: "ExceptionInInitializerError"
            sdkUnavailableReason = "Failed initializing Morpho Native library: $cause."
            Log.w(TAG, sdkUnavailableReason, e)
            logCallback?.invoke("ExceptionInInitializerError: $cause")
        } catch (e: NoClassDefFoundError) {
            sdkAvailable = false
            sdkUnavailableReason = "Morpho SDK class not found: ${e.message}"
            Log.w(TAG, sdkUnavailableReason, e)
            logCallback?.invoke("NoClassDefFoundError: ${e.message}")
        } catch (t: Throwable) {
            sdkAvailable = false
            sdkUnavailableReason = "SDK initialization error: ${t.message}"
            Log.w(TAG, sdkUnavailableReason, t)
            logCallback?.invoke("Initialization error: ${t.message}")
        }

        return Pair(sdkAvailable, sdkUnavailableReason)
    }

    /**
     * Opens MorphoDevice with openUsbDeviceFD.
     */
    fun openDeviceFd(
        context: Context,
        bus: Int,
        address: Int,
        fd: Int,
        logCallback: ((String) -> Unit)? = null
    ): Pair<MorphoDevice?, Int> {
        val (available, _) = checkSdkAvailability(context, logCallback = logCallback)
        if (!available) {
            return Pair(null, -999)
        }

        return try {
            val device = MorphoDevice()
            val ret = device.openUsbDeviceFD(bus, address, fd, 0)
            if (ret == ErrorCodes.MORPHO_OK) {
                Pair(device, ErrorCodes.MORPHO_OK)
            } else {
                try {
                    device.closeDevice()
                } catch (_: Throwable) {}
                Pair(null, ret)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in openDeviceFd", t)
            Pair(null, -1)
        }
    }

    /**
     * Executes morphoDevice.getImage(10000, morphoImage) on background thread.
     */
    fun captureRawImage(
        device: MorphoDevice,
        timeoutMs: Int = 10000
    ): Pair<Int, MorphoImage?> {
        return try {
            val morphoImage = MorphoImage()
            val ret = device.getImage(
                timeoutMs,
                0, // coder
                CompressionAlgorithm.MORPHO_NO_COMPRESS,
                0, // compressionRate
                0, // compressQuality
                LatentDetection.LATENT_DETECT_DISABLE,
                morphoImage,
                0, // securityLevel
                null // observer
            )
            Pair(ret, morphoImage)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during captureRawImage", t)
            Pair(-1, null)
        }
    }

    /**
     * Safely closes the MorphoDevice.
     */
    fun closeDevice(device: MorphoDevice?) {
        try {
            device?.closeDevice()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing device", t)
        }
    }
}
