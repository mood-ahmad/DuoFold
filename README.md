# DuoFold for Android

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/jcx396905-gif/DuoFold)
[![Shizuku](https://img.shields.io/badge/Shizuku-required-7C4DFF)](https://github.com/RikkaApps/Shizuku)
[![Release](https://img.shields.io/github/v/release/jcx396905-gif/DuoFold?display_name=tag)](https://github.com/jcx396905-gif/DuoFold/releases/latest)
[![License](https://img.shields.io/github/license/jcx396905-gif/DuoFold)](LICENSE)
[![Stars](https://img.shields.io/github/stars/jcx396905-gif/DuoFold?style=flat)](https://github.com/jcx396905-gif/DuoFold/stargazers)

**A system-wide iPhone Duo-style folding illusion for regular Android phones — no foldable hardware and no root required.**

DuoFold turns the entire Android display into a motion-driven spatial surface. As you tilt your phone left/right or forward/backward, the current screen is reprojected in real time with perspective, translation, blur, lighting, and depth effects. The motion pauses when the phone stops and reverses naturally when you tilt it back.

**English** · [简体中文](README.zh-CN.md)

---

## What's new in this maintenance build (v0.4.0)

> This repository is a **maintained fork** of [jcx396905-gif/DuoFold](https://github.com/jcx396905-gif/DuoFold) (MIT license, original author Simon / jcx396905-gif). It focuses on modern Android (Android 14–17) and everyday usability.

### 1. Android 17 compatibility ✅

The original build could not get past Shizuku authorization on the latest Android (Android 17 / API 37) — it crashed immediately with:

```
NoSuchMethodException: android.view.SurfaceControl.getInternalDisplayToken
```

- **Cause:** Android 17 refactored `SurfaceControl` and removed legacy internal entry points such as `getInternalDisplayToken()`. The original binary called symbols that no longer exist, so the authorization flow died with `NoSuchMethodException`.
- **Fix:** reworked the call path to use the `SurfaceControl` API still present on Android 17, so capture and authorization work across Android 14–17.
- Also fixed: **upside-down output**, **cropped capture** (right/top content cut off), and **missing status bar / Dock** — the captured frame is now aligned to the full screen.

### 2. More adjustable controls 🎚️

The "Adjust to comfortable feel" panel (3 parameters in the original) is expanded to **6 real-time settings**:

| Parameter | Range | Description |
|---|---|---|
| Max tilt angle | 15°–80° | Maximum tilt that triggers the effect |
| Spatial strength | 20%–100% | Overall perspective & depth intensity |
| Blur strength | 0%–200% | Edge blur intensity while tilted |
| Stretch amount | 0%–150% | Horizontal pull of the side of the screen (the original had a harsh stretch; now finely adjustable) |
| Frame-rate cap | 30–180 Hz | **Custom FPS cap** — high-refresh displays (e.g. 120Hz) are no longer locked to ~30 FPS |
| Z-axis sensing | On/Off | Respond to forward/back tilt (expands up/down), smoothly blended with left/right motion |

All sliders use **debounced commit** (persisted 260 ms after release) with live value preview while dragging; toggles and sliders also **lock page scrolling** while being dragged so the page never follows your finger.

### 3. Refined UI 🎨

Aligned with the polish of KernelSU / BG Next:

- **Collapsible large title + always-on frosted glass:** the large title collapses smoothly on scroll; the whole top bar (including the status-bar area) is real frosted glass (textureBlur 25f + 87% surface blend), and content flows underneath as you scroll — it never overlaps the title.
- **Fully immersive (edge-to-edge):** transparent status bar and bottom gesture bar, content extends to the screen edges, status-bar icons adapt to dark/light theme automatically.
- The top-right dark-mode toggle has no ripple mask and fades out as the bar collapses.
- Card element margins, button spacing and title weights are normalized.

### 4. Performance & Vulkan ⚡

- **New Vulkan renderer (direct acceleration):** skips the SurfaceTexture copy, sending captured frames straight to the GPU — higher frame rate, lower latency. A **Vulkan / OpenGL switch** is provided: pick OpenGL for compatibility, or Vulkan for raw speed.
- Fixed the original **30 FPS lock**; combine with the FPS-cap slider to run full-rate on high-refresh panels.
- Fixed **flickering / twitching** (alternating old/new frames) by aligning render and composition timing.

### Building

- Open the repository in Android Studio / Gradle and build. `app/` is the main app module; `miuix/` and `backdrop/` are local library modules.
- The Vulkan path needs NDK/CMake (`app/src/main/cpp/`).

### License

MIT License (same as upstream). Copyright of the original author and subsequent contributors.

---

## Demo

<a href="https://github.com/jcx396905-gif/DuoFold/releases/download/v0.4.0/DuoFold-IMG-0019.mov"><img src="media/extra-demo-cover.jpg" width="360" alt="DuoFold latest real-device demo"></a>

▶ [Watch the latest real-device demo (~23 s)](https://github.com/jcx396905-gif/DuoFold/releases/download/v0.4.0/DuoFold-IMG-0019.mov)

## More test videos

<a href="https://github.com/jcx396905-gif/DuoFold/releases/download/v0.4.0/DuoFold-full-test.mp4"><img src="media/full-test-cover.jpg" width="360" alt="DuoFold full test video"></a>

▶ [Watch the full test video (~30 s)](https://github.com/jcx396905-gif/DuoFold/releases/download/v0.4.0/DuoFold-full-test.mp4)

<a href="https://github.com/jcx396905-gif/DuoFold/releases/download/v0.4.0/DuoFold-demo-clip.mp4"><img src="media/demo-clip-cover.jpg" width="360" alt="DuoFold short demo clip"></a>

▶ [Watch the short demo clip (~6 s)](https://github.com/jcx396905-gif/DuoFold/releases/download/v0.4.0/DuoFold-demo-clip.mp4)

## Screenshots

<p align="center">
  <img src="media/app-setup.jpg" width="320" alt="DuoFold setup screen">
  <img src="media/app-settings.jpg" width="320" alt="DuoFold settings and author screen">
</p>

## Download

- **Android 14 and newer:** download `DuoFold-0.4.0-Android14-and-newer.apk` from the [Latest Release](https://github.com/jcx396905-gif/DuoFold/releases/latest).
- **Android 10–13:** use the compatibility APK from the [`android10-compat`](https://github.com/jcx396905-gif/DuoFold/tree/android10-compat) branch: [DuoFold-0.4.0-Android10-13.apk](https://raw.githubusercontent.com/jcx396905-gif/DuoFold/android10-compat/apk/DuoFold-0.4.0-Android10-13.apk). The compatibility build is not included in GitHub Releases.

## Features

- Treats the entire active Android screen as one continuous spatial surface and applies perspective, translation, frosted blur, and lighting effects.
- Uses device motion sensors to drive the effect in real time. Stop moving the phone and the animation stops; tilt in the opposite direction and it reverses naturally.
- Left/right motion is enabled by default. Optional **Z-axis sensing · forward/back tilt** adds front/back motion, with diagonal movement blended continuously.
- Automatically adapts to portrait and landscape orientation and maps sensor axes to the current display coordinates.
- Does not modify launcher layouts or third-party app UIs; the effect is rendered over whatever interface you are currently using.
- On Android 14+, the effect can be stopped with a three-finger touch, the notification action, or by returning to DuoFold. The compatibility build uses the notification stop action.

## Requirements

- The standard build requires Android 14 or newer. The compatibility branch supports Android 10 and newer.
- [Shizuku](https://github.com/RikkaApps/Shizuku) must be installed and running.
- **No root required.**
- Tested on **Xiaomi 15 / Android 16**. Screen-capture and overlay restrictions may vary between Android vendors.

## Setup

1. On Android 14+, download the standard build from the [Latest Release](https://github.com/jcx396905-gif/DuoFold/releases/latest). On Android 10–13, download the compatibility build from the [`android10-compat`](https://github.com/jcx396905-gif/DuoFold/tree/android10-compat) branch.
2. Open Shizuku and start its service using wireless debugging or ADB, following Shizuku's instructions.
3. Open DuoFold and tap **01 Connect & authorize Shizuku**, then allow access in the authorization dialog.
4. Tap **02 Enable screen-effect accessibility service**, find DuoFold, and enable the service.
5. Hold the phone naturally while facing the screen and tap **03 Try for 10 seconds**. Tilt the phone left and right to see the folding illusion.
6. Adjust **Maximum tilt angle** and **Spatial strength** to your preference. Z-axis sensing is disabled by default in both builds; enable **Z-axis sensing · forward/back tilt** only if you want front/back motion. Changes apply the next time the effect starts.
7. Tap **04 Enable global effect**. If you change your grip, return to the app and tap **Recalibrate current grip**.
8. Android 14+ can stop the effect with a three-finger touch or the notification action. Android 10–13 uses the notification stop action.

If the accessibility service appears connected but the effect does not start, toggle the accessibility service off and on once. On some Xiaomi systems, touch correction also requires **USB debugging (Security settings)** in Developer options.

## How it works

DuoFold uses Shizuku to access the current display image and passes it through local memory to a renderer (OpenGL ES by default, or the optional Vulkan path). The game rotation vector and gyroscope are used to calculate relative device pose. If those sensors are unavailable, the compatibility build falls back to the gravity sensor or accelerometer. The frame is reconstructed according to the current display orientation, and calibration updates automatically after portrait/landscape changes.

Default parameters use an **80° maximum tilt angle**, **100% spatial strength**, **6–32 blur samples**, and **40 ms gyroscope prediction**. The maintenance build adds a frame-rate cap (30–180 Hz) so high-refresh panels are no longer locked to 30 FPS.

## Privacy and limitations

- The app has no Internet permission. During normal use, screen images are neither saved nor uploaded.
- The effect overlay is excluded from system screenshots to prevent recursive screen capture.
- Android 10/11 lack the per-layer exclusion API. On those versions, the compatibility build captures one real frame when the effect starts and then uses sensors to manipulate that frame in real time. Restarting the effect or rotating the display refreshes the base frame.
- Android 10–13 do not support the accessibility touch-coordinate correction API introduced in Android 14. If interaction becomes difficult while tilted, return to the calibrated pose first.
- Protected or screenshot-blocked content may appear black.
- DuoFold changes only the final displayed image. It does not rearrange icons, text, or components inside third-party apps.
- Extended use increases GPU load and battery consumption.

## Build from source

JDK 17 and Android SDK 36 are required:

```shell
./gradlew :app:assembleStandardDebug :app:assembleCompatDebug :motion:test
```

The Android 14+ standard build is generated at `app/build/outputs/apk/standard/debug/app-standard-debug.apk`. The Android 10+ compatibility build is generated at `app/build/outputs/apk/compat/debug/app-compat-debug.apk`.

The project contains two modules: `app` handles Shizuku integration, accessibility services, screen capture, and OpenGL ES / Vulkan rendering; `motion` handles pose-axis mapping, filtering, and projection math and can run independent JVM unit tests.

## Author

[jcx](https://github.com/jcx396905-gif)

## License

Released under the MIT License. Third-party license notices are available at `app/src/main/assets/THIRD_PARTY_NOTICES.txt`.