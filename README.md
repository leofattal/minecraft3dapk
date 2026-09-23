# MC3D — Minecraft-style Voxel Sandbox in Glasses-Free 3D for Lume Pad

Two apps live in this repo, both targeting the **Leia Lume Pad / Nubia Pad 3D** lightfield tablets:

1. **`app` — MC3D**: a self-contained Minecraft-*style* voxel game (three.js, WebXR) that renders
   true stereo on the lightfield via a WebView + WebXR shim + CNSDK interlacer pipeline.
2. **`weaver` — MC 3D Weaver**: real **Minecraft Bedrock** (or any installed app) played in
   glasses-free 3D on the Lume Pad 2 using a trusted virtual display + on-device AI depth
   (MiDaS on the Hexagon NPU) + DIBR stereo synthesis + the CNSDK interlacer. **No gaming PC
   needed — everything runs on the tablet.**

Pick the APK you want:

- `app/build/outputs/apk/release/app-release.apk` — the voxel game (works on Lume Pad 1/2)
- `weaver/build/outputs/apk/release/weaver-release.apk` — the weaver for real Minecraft (Lume Pad 2)

---

## MC 3D Weaver — real Minecraft in 3D, on-device

### Why the weaver looks the way it does

Actual Minecraft Bedrock cannot render stereo itself (closed renderer, no SBS, no mods), the
Lume Pad 2 has no global SBS/AI-3D mode for apps, and an overlay + screen capture approach
feeds back on itself. The weaver therefore moves the **game onto a hidden virtual display**
and rebuilds the visible image:

```
Minecraft ──runs on──> trusted virtual display (created via Shizuku/shell)
                              │ frames
                              ▼
                     ImageReader (our own surface, no MediaProjection,
                              no consent dialog, no feedback loop)
                              │ RGBA frame + MiDaS depth (Hexagon NPU, ~15-30 Hz)
                              ▼
                     DIBR shader: synthesize left/right views (SBS)
                              │
                              ▼
        CNSDK InterlacedSurfaceView overlay ── lightfield weave + face tracking
        (fullscreen, pixel-exact, not touchable)
                              ▲
        touches ── touch-catcher overlay ── injected onto the virtual display
```

- **Depth is AI-estimated** from the mono frame (the same "SS3D" idea Leia used), so 3D
  quality is good-but-not-perfect: terrain, buildings and the world read deeply; thin
  foreground objects (leaves, the hotbar) can warp. Voxel geometry is a friendly case.
- **The game itself never runs in 3D internally** — Bedrock cannot. This is a real-time
  2D→stereo conversion, done on-device.
- **Latency**: the 3D image trails direct rendering by roughly 1-3 frames; depth updates are
  throttled. Fine for building/exploring, noticeable in fast motion.

### One-time setup (on the tablet, no PC)

1. Install the **Shizuku** app (Play Store, or https://shizuku.rikka.app) and start it via
   **"Wireless debugging"** — Shizuku's own setup guide walks you through it entirely
   on-device.
2. Install `weaver-release.apk` (`adb install -r weaver/build/outputs/apk/release/weaver-release.apk`
   or copy it over and open with a file manager).
3. Open **MC 3D Weaver**:
   - tap **Fix Shizuku** → allow,
   - tap **Overlay permission** → allow,
   - allow the camera permission when asked (used by the display's face tracking).
4. Make sure **Minecraft Bedrock** (`com.mojang.minecraft`) is installed and you're logged
   in (the weaver force-stops and relaunches it — any app in the picker works, Minecraft is
   just the default).

### Playing

- Tap **START 3D**. Minecraft launches and appears in glasses-free 3D; your touches are
  forwarded automatically. Mind the face-tracking sweet spot.
- **FLIP / SWAP / STOP 3D** buttons (top-right) work live while playing: fix an upside-down
  image, inverted depth, or exit. Recents/Home/notification shade stay usable.
- The game defaults to whichever Minecraft build is installed — the Lume Pad 2's Bedrock
  package is `com.mojang.minecraftpe` (the Play listing's `com.mojang.minecraft` is also
  accepted). Any other installed app can be picked instead.
- Tuning in the app: **3D depth strength**, **convergence** (where the screen plane sits),
  **swap eyes** if depth looks inverted, **flip image** if the picture renders upside down.
- `adb logcat -s WeaverService StereoRenderer DepthEngine ShellPriv Overlay3D` shows the
  pipeline state (fps, NPU vs CPU depth, display id). A healthy session logs
  `depth interpreter running on Hexagon HTP` and ~50-60 render fps.

### Requirements & limits

- Lume Pad 2 (Snapdragon 888, Android 12, Leia services present). The CNSDK 0.6.167 AAR
  requires Android 11+. On non-Leia devices the overlay init fails gracefully.
- Shizuku must be running (it stops on reboot — restart it, then START 3D again).
- Performance: depth on the Hexagon NPU is real-time; XNNPACK-CPU fallback is slow — if
  you see "QNN HTP unavailable" in logcat, free some memory and retry.
- DRM/secure-flag apps capture black — ordinary games are fine.
- Multi-touch is not forwarded (single-pointer taps/swipes only). A Bluetooth controller
  connected to the tablet works natively in Minecraft while the weaver displays the game.

### Licensing / attribution notes for the weaver

- **LeiaSR/CNSDK 0.6.167 AAR** (`cnsdk-weaver/sdk-final-aar-release.aar`, © Leia Inc.) —
  proprietary artifact, obtained from the public
  [LumePad3DEverywhere](https://github.com/alienware377/LumePad3DEverywhere) project
  (which itself redistributes it). For personal use. For distribution, get licensing from
  developers@leiainc.com, or extract the CNSDK from your own device's
  `com.moonlight.leia` APK following the recipe in
  [DepthFlix](https://github.com/nautymac/DepthFlix)'s docs.
- **MiDaS w8a8 model** (`weaver/src/main/assets/midas_w8a8.tflite`, ~17.7 MB) — the
  quantized MiDaS CNN exported for **Qualcomm AI Hub** (`midas-tflite-w8a8`); same
  provenance as above. Code: isl-org/MiDaS (MIT). Use subject to the AI Hub terms.
- **Architecture credit**: the virtual-display weaver approach and the QNN/HTP depth path
  were demonstrated by the (unlicensed, hence not copied) `LumePad3DEverywhere` project and
  by [DepthFlix](https://github.com/nautymac/DepthFlix)'s documented Moonlight weaving.
  All source code in `weaver/` here is an original clean-room implementation.
- **Shizuku** (Apache-2.0), **HiddenApiBypass** (Apache-2.0), **TensorFlow Lite**
  (Apache-2.0), **Qualcomm QNN LiteRT delegate + runtime** — via Maven Central.
- Minecraft is a trademark of Mojang/Microsoft; this project is not affiliated and does not
  modify or redistribute the game — it displays the game you installed, on your own device.

---

## MC3D (the voxel game)

An Android APK that runs a Minecraft-style voxel world in **glasses-free stereo 3D** on
the **Leia Lume Pad (and other LeiaSR lightfield products)**, using a **WebXR** pipeline
in the spirit of the WebXR tools on [dfattal.github.io](https://dfattal.github.io).

The game itself is a self-contained three.js web app (no network needed). The APK hosts
it in a WebView, provides a WebXR shim so the page can start `immersive-vr` sessions, and
pipes the WebView's side-by-side stereo output through the Leia CNSDK interlacer, which
drives the lightfield display with face tracking.

```
┌───────────────────── Lume Pad ─────────────────────┐
│  WebView (assets/www, offline)                     │
│   ├─ game: three.js voxel world                     │
│   └─ webxr-shim.js: navigator.xr → stereo session   │
│        renders each eye → half of an SBS framebuffer │
│  SurfaceAwareWebView → SurfaceTexture               │
│  GameView (Leia InterlacedSurfaceView)              │
│   └─ CNSDK interlacer → lightfield + face tracking   │
│  LeiaSDK.enableBacklight(true) → stereo backlight    │
└────────────────────────────────────────────────────┘
```

## What's in the game

- Procedurally generated voxel terrain (seeded), grass/dirt/stone/sand, water, trees,
  coal & iron ore, snowy peaks
- Break blocks (tap / left-click), place blocks (+BLOCK button / right-click),
  9-slot hotbar with procedurally drawn pixel textures
- Walking, sprinting, jumping, swimming, fly mode (double-jump or F/FLY)
- Baked ambient occlusion in the chunk meshes, drifting clouds, fog
- World auto-saves to localStorage (edits + player position); NEW WORLD resets it
- Touch controls (joystick + drag-look) and desktop controls (WASD + pointer lock)

## The 3D / WebXR pipeline

`assets/www/js/webxr-shim.js` implements the WebXR API surface three.js needs
(`XRSystem`, `XRSession`, `XRWebGLLayer`, `XRReferenceSpace`, `XRRigidTransform`, …).
When the page enters an immersive session:

1. `NativeLeia.enable3D()` (Java bridge) switches the display to stereo backlight via
   `LeiaSDK.enableBacklight(true)` with face tracking, and redirects the WebView's
   rendering into a SurfaceTexture.
2. The shim renders the two eyes into the left/right halves of a framebuffer, with a
   per-eye frustum shift so the stereo window converges at a comfortable distance.
3. The CNSDK `InterlacedSurfaceView` consumes the WebView texture and interlaces it for
   the lightfield display; the device's face tracking gives the moving "window" effect
   (the asymmetric-stereo-window model used by dfattal.github.io's LIF/WebXR viewers).

### Tuning the 3D (URL parameters)

Append these to the URL if you host the page, or edit the defaults in
`app/src/main/assets/www/js/webxr-shim.js` (CFG object) for the APK:

| Param | Default | Meaning |
|-------|---------|---------|
| `ipd`  | `0.03`  | Virtual interocular distance in meters (depth strength) |
| `conv` | `2.5`   | Convergence distance in meters (where the screen plane sits) |
| `fov`  | `70`    | Vertical FOV per eye in degrees |
| `dpr`  | `1.25`  | Canvas pixel-ratio cap (perf vs sharpness) |
| `gyro` | off     | `1` rotates the world with the device orientation sensor |
| `seed` | random | World seed |
| `debug`| off    | `1` shows FPS/position overlay |
| `xrshim`| off   | `1` forces the shim in browsers with native WebXR (desktop SBS preview) |

Too much depth popping out of the screen? Lower `ipd` or raise `conv`.

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools). Then:

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # signed with the debug key for easy sideloading
```

## Installing on a Lume Pad

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK onto the device and open it with a file manager. Grant the camera
permission when asked — it is used by the display's face tracking, which makes the 3D
window follow your head.

On first launch: tap **PLAY IN 3D**. Exit 3D with the on-screen **EXIT 3D** button or
the system back gesture.

### Non-Leia devices

On devices without Leia system services the app still runs — the game plays in flat 2D
("PLAY") or in side-by-side preview mode. The same web app also runs in any desktop
browser (flat), and in real WebXR browsers (e.g. Vision Pro, Quest) with native stereo.

## Project layout

```
app/                       MC3D — the voxel game (WebView + WebXR shim)
  src/main/java/com/leofattal/mc3d/
    MainActivity.java        WebView host, asset loader, JS bridge, permissions
    SurfaceAwareWebView.java redirects WebView drawing into a Surface in 3D mode
    GameView.java            Leia InterlacedSurfaceView hosting the WebView texture
    InterlacedAsset.java     InputViewsAsset/InputGLBinding glue + FBO for interlacer
    QuadRenderer.java        GL quad drawing of the WebView SurfaceTexture
  src/main/assets/www/
    index.html, style.css    UI shell (splash, hotbar, touch controls)
    js/main.js               the voxel game (world gen, meshing, physics, XR transport)
    js/webxr-shim.js         WebXR implementation for the Leia stereo window
    js/vendor/three.module.min.js  three.js r160
leia-cnsdk/                 local AAR module wrapping the Leia CNSDK 0.7.28 artifact
weaver/                     MC 3D Weaver — real Minecraft in 3D (Lume Pad 2)
  src/main/java/com/leofattal/mcweaver/
    MainActivity.kt          prerequisites UI, tuning sliders, start/stop
    WeaverService.kt         orchestration: capture, launch, overlay, input
    ShellPriv.kt             Shizuku/shell: trusted virtual display, launch, injection
    StereoRenderer.kt        EGL + DIBR stereo synthesis into the CNSDK surface
    DepthEngine.kt           MiDaS w8a8 on the Hexagon NPU (QNN delegate)
    Overlay3D.kt             CNSDK interlaced overlay + touch-catcher windows
  src/main/assets/midas_w8a8.tflite   quantized MiDaS depth model
cnsdk-weaver/               local AAR module wrapping the Leia CNSDK 0.6.167 artifact
```

## Licensing / attribution notes

- **three.js** is MIT licensed (vendored under `assets/www/js/vendor/`).
- **LeiaSR / CNSDK AAR** (`leia-cnsdk/sdk-faceTrackingService-0.7.28.aar`, © Leia Inc.)
  is a proprietary artifact obtained from the community project
  [SupernaviX/LeiaWebXR](https://github.com/SupernaviX/LeiaWebXR), which demonstrated
  this WebView→interlacer approach on the Lume Pad 2. The official SDK is distributed to
  partners via [Immersity](https://immersity.ai/developers); if you plan to distribute
  this app publicly, get proper licensing from developers@leiainc.com.
- The WebXR stereo-window approach follows the public WebXR examples of
  [dfattal.github.io](https://dfattal.github.io) (Leia's WebXR LIF viewers).
- This is an original Minecraft-*style* game; it contains no Mojang/Microsoft assets or
  code. "Minecraft" is a trademark of Mojang; this project is not affiliated.

## Desktop preview

The game runs in any browser from the `assets/www` folder:

```bash
cd app/src/main/assets/www && python3 -m http.server 8000
# open http://localhost:8000 — flat mode
# add ?xrshim=1 to preview the side-by-side stereo pair
```
