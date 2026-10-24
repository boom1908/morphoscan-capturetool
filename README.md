# MorphoScan Capture Tool

[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?style=flat&logo=android)](https://developer.android.com/)
[![Architecture: arm64-v8a](https://img.shields.io/badge/Architecture-arm64--v8a-blue.svg)](https://developer.android.com/ndk/guides/abis)
[![Hardware: Safran Morpho MSO 1300](https://img.shields.io/badge/Hardware-MSO%201300%20E%2FE2%2FE3-orange.svg)](https://www.idemia.com/)
[![Theme: Obsidian Crimson](https://img.shields.io/badge/Theme-Obsidian%20%26%20Crimson-8B0000.svg)](https://github.com/)

A dedicated, high-performance Android biometric fingerprint acquisition application engineered specifically for **IDEMIA / Safran MorphoSmart™ MSO 1300 Series (MSO 1300 E, E2, and E3)** optical fingerprint scanners over USB OTG. Styled in an **Obsidian Black & Deep Crimson** aesthetic with razor-sharp vector biometric branding.

---

## Overview

MorphoScan Capture Tool interfaces directly with Safran/IDEMIA biometric hardware on Android devices. It pairs the official **MorphoSmart SDK (v6.42.0)** native JNI layer with a modern **Material Design 3 (M3) Jetpack Compose** interface, enabling zero-configuration hardware discovery, live fingerprint capture, template extraction, and real-time hardware telemetry.

---

## Key Features

- **Automated USB OTG Hardware Lifecycle**:
  - Auto-detection via Android `UsbManager` and system broadcast filters (`android.hardware.usb.action.USB_DEVICE_ATTACHED`).
  - Pre-filtered for Safran Morpho Vendor ID (`0x079B` / `1947`) and Product ID (`0x0047` / `71`).
  - Native file descriptor handoff using `MorphoDevice.openUsbDeviceFd(...)` without requiring root access.

- **500 DPI Biometric Capture & Image Processing**:
  - Live finger placement detection and real-time capture callbacks.
  - Multi-format extraction: WSQ (FBI certified compression), Raw 8-bit Greyscale, and Windows Bitmap (BMP).
  - Biometric template generation compliant with ISO/IEC 19794-4 and ANSI/INCITS 381 standards.

- **Diagnostic Terminal & Telemetry**:
  - Real-time logging of USB protocol handshakes, JNI native library loads, and biometric SDK state transitions.
  - Single-tap **Copy to Clipboard** for field diagnostics and troubleshooting.
  - Runtime environment inspection (native library paths, extracted `.so` verification, ABI detection).

- **Modern Android Architecture**:
  - 100% Kotlin with Jetpack Compose Material 3 UI.
  - Full edge-to-edge support with dynamic system bar insets.
  - Reactive `StateFlow` and Coroutine architecture for smooth 60 FPS operation without UI blocking.

---

## Native JNI Architecture (`arm64-v8a`)

The application bundles the complete 64-bit ARM (`arm64-v8a`) MorphoSmart native runtime located under `app/src/main/jniLibs/arm64-v8a/`:

| Native Library | Purpose & Dependency Chain |
| :--- | :--- |
| `libc++_shared.so` | **LLVM C++ Shared Runtime** — Required by all Morpho C++ shared objects (tracked in repo as `app/libs/native_arm64_libcxx.so` and automatically synchronized by Gradle during build). |
| `libusb1.0.so` | **User-Space USB Library** — Manages low-level endpoint pipes and transfers. |
| `libSecuHost.so` | **Security Host Subsystem** — Hardware encryption and secure channel negotiation. |
| `libMSO_Secu.so` | **MSO Security Protocol** — Mutual authentication and anti-tamper communication. |
| `libMSO100.so` | **Hardware Driver Layer** — Physical device protocol driver for MSO 1300 series. |
| `libNativeMorphoSmartSDK_6.42.0.0.so` | **Morpho Core SDK** — Biometric feature extraction, quality scoring, and matching. |

### Packaging Configuration

To ensure consistent extraction across all Android versions (Android 8 to Android 15+), the build configuration enforces uncompressed native packaging:

```kotlin
// app/build.gradle.kts
packaging {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

```xml
<!-- app/src/main/AndroidManifest.xml -->
<application
    android:extractNativeLibs="true"
    ... >
```

This guarantees the Android Package Manager copies all `.so` files into the app's private `nativeLibraryDir` (`/data/app/.../lib/arm64/`) at installation time, satisfying dynamic linker (`dlopen`) namespace lookups.

---

## Hardware & System Requirements

1. **Fingerprint Scanner**:
   - Safran / IDEMIA MorphoSmart MSO 1300 E
   - Safran / IDEMIA MorphoSmart MSO 1300 E2
   - Safran / IDEMIA MorphoSmart MSO 1300 E3
2. **Android Device**:
   - Android 8.0 (API level 26) or higher.
   - 64-bit ARM CPU (`arm64-v8a`).
   - USB Host (OTG) enabled.
3. **Cables / Adapters**:
   - USB-A to USB-C (or Micro-USB) OTG host adapter capable of supplying standard 5V/500mA power to the scanner.

---

## Installation & First Run

### 1. Fresh Installation
If installing an update or replacing an older version, **uninstall the previous build first**:
```bash
adb uninstall com.morpho.scanner
adb install app-debug.apk
```
*Note: Uninstalling ensures that Android cleans the cached `nativeLibraryDir` and extracts the complete set of native libraries, including `libc++_shared.so`.*

### 2. Connect Hardware
1. Plug the Morpho MSO 1300 scanner into your Android device using the USB OTG adapter.
2. When prompted: **"Allow Morpho Scanner to access this USB device?"**, check **"Always open Morpho Scanner when this device is connected"** and tap **OK**.
3. The app will automatically initialize the USB communication layer, load the native drivers, and illuminate the scanner's optical sensor for fingerprint capture.

---

## Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK 35 (Platform Tools, Build-Tools 35.0.0)
- Android NDK (r25c or r26 recommended)

### Build Command
Compile and generate the debug APK:

```bash
gradle assembleDebug
```

The compiled APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
├── app/
│   ├── build.gradle.kts                   # App-level build and packaging configuration
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml        # Permissions, USB feature filters & intents
│           ├── java/com/morpho/scanner/
│           │   ├── MainActivity.kt        # Edge-to-edge UI, USB receiver, permission flows
│           │   ├── ui/                    # Jetpack Compose screens, dialogs, theme
│           │   ├── native/                # Morpho JNI loader and dynamic link manager
│           │   └── usb/                   # USB device enumeration and endpoint manager
│           ├── jniLibs/
│           │   └── arm64-v8a/             # 64-bit native binaries (Morpho SDK + libc++_shared)
│           └── res/
│               ├── values/                # Strings, colors, styles
│               └── xml/
│                   └── device_filter.xml  # USB VID (1947) & PID (71) hardware matchers
├── gradle/                                # Gradle wrapper and build dependencies
├── build.gradle.kts                       # Root project build configuration
├── settings.gradle.kts                    # Repository definitions and project naming
└── README.md                              # Project documentation
```

---

## Hardware Troubleshooting & Diagnostics

### 1. USB Interface Contention & libusb Handshake
- The MorphoSmart SDK utilizes `libusb1.0.so` and `libMSO100.so` to communicate with the optical sensor.
- When opening the device via `MorphoDevice.openUsbDeviceFD(...)`, Android's `UsbDeviceConnection` file descriptor is shared with native code.
- **Critical Requirement**: Android Java code must **not** claim Interface 0 via `connection.claimInterface(...)` before passing the file descriptor to the native layer, as this prevents `libusb` from claiming the USB device endpoints and causes a kernel driver contention crash.

### 2. Android 14+ USB Permission Strict Handling
- `PendingIntent` creation for `ACTION_USB_PERMISSION` requires `FLAG_MUTABLE` on Android 12+ (SDK 31+) with explicit package setting (`intent.setPackage(...)`) to comply with Android 14 security sandboxing.
- `USBManager.context` must point to `context.applicationContext` so that package name and licensing checks succeed across device lifecycle events.

### 3. Persistent Diagnostics & Crash Logging
- In-app diagnostics logging captures every native library load event, USB broadcast, and capture state transition.
- Uncaught exceptions are stored to persistent app storage (`last_crash.log`) and rendered in the in-app Activity Log on the subsequent launch for rapid debugging.

---

## License & Third-Party Notices

- **MorphoSmart™ SDK & Native Binaries**: Proprietary software © IDEMIA / Safran Identity & Security. All rights reserved.
- **Application Code**: Licensed under the MIT License. See [LICENSE](LICENSE) for details.
