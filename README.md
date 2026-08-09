# OTT Hub — Fire TV WebView Launcher

A native Android Studio project (Kotlin) that shows a card-based dashboard of OTT
services and opens each one in a WebView tuned for Fire TV, with a D-pad / virtual-mouse
toggle for sites that aren't built for remote-control navigation.

## Project layout

```
FireTVOTTLauncher/
├── build.gradle, settings.gradle, gradle.properties      (root Gradle config)
└── app/
    ├── build.gradle                                      (app module config, minSdk 21)
    └── src/main/
        ├── AndroidManifest.xml                           (LEANBACK_LAUNCHER + LAUNCHER, banner, permissions)
        ├── java/com/example/firetvott/
        │   ├── OttService.kt        - data model for one OTT card
        │   ├── OttConfig.kt         - EDIT THIS to add/remove OTT services
        │   ├── OttAdapter.kt        - RecyclerView adapter for the card grid
        │   ├── MainActivity.kt      - dashboard/home screen
        │   └── WebViewActivity.kt   - WebView + dual-mode remote navigation
        └── res/
            ├── layout/               (activity_main, activity_webview, card_ott)
            ├── drawable/             (card focus selector, cursor graphic, badge bg)
            ├── drawable-xhdpi/       (app_banner.png — placeholder, 320x180)
            ├── mipmap-*/             (placeholder launcher icons)
            └── values/               (strings, themes)
```

## Adding more OTT services

Everything drives off one list — open `OttConfig.kt`:

```kotlin
val services = mutableListOf(
    OttService(name = "Airtel Xstream Play", url = "https://www.airtelxstream.in", colorHex = "#E40046"),
    OttService(name = "JioCinema",           url = "https://www.jiocinema.com",     colorHex = "#7C1DAB"),
    OttService(name = "Einthusan",           url = "https://einthusan.tv",          colorHex = "#1B5E20", useDesktopUA = false)
)
```

Add a new `OttService(...)` line and rebuild — no layout/adapter changes needed.
`useDesktopUA = false` is useful for sites that serve a *better* player to a mobile UA;
try both if a service's video player doesn't load.

## How the dual navigation mode works

- **D-Pad Mode** (default): normal Android focus navigation. Works for pages with visible
  focus outlines.
- **Mouse Pointer Mode**: press **Play/Pause** (or **Menu** as a fallback) on the remote to
  toggle. A circular cursor appears; D-pad arrows glide it around (it accelerates the
  longer you hold a direction), and **OK/Center** fires a synthetic touch
  (`ACTION_DOWN` + `ACTION_UP`) at the cursor's position inside the WebView. A badge in the
  corner announces the mode for ~1.5 seconds on every switch.
- **Back** button: goes back in WebView history if possible, otherwise exits the activity.

## Video/session notes

- JavaScript, DOM storage, and database storage are all enabled, and cookies are set to
  persist + flushed on page load, so logins survive an app restart or device reboot.
- `WebView.setLayerType(LAYER_TYPE_HARDWARE, null)` is set for smooth HTML5 video.
- The WebView reports itself as desktop Chrome by default (`useDesktopUA = true`) so DRM
  (Widevine) video players load instead of "unsupported browser" fallbacks; per-service
  override is available via `useDesktopUA`.
- `onShowCustomView`/`onHideCustomView` are implemented for true fullscreen video.
- Actual Widevine playback also depends on the Fire TV Stick's system WebView (Amazon
  Silk/Chromium component) supporting EME — this is a device capability, not something the
  app can force.

## Before you ship this for real

1. Replace the placeholder launcher icons (`res/mipmap-*/ic_launcher.png`) and the TV
   banner (`res/drawable-xhdpi/app_banner.png`, must be exactly 320×180px) with real
   branded artwork.
2. Double-check each OTT service's Terms of Service — wrapping a third-party site in your
   own APK and distributing it can violate the service's terms even if it's technically
   just a WebView pointed at their public URL.
3. Consider adding a loading spinner (`onPageStarted`/`onPageFinished`) and basic error
   handling (`onReceivedError`) for when the Fire TV loses network mid-stream.

## Build & sideload instructions

### Option A — Build entirely from your phone (no PC), via GitHub

This repo already includes `.github/workflows/build-apk.yml`, which builds the APK in
GitHub's cloud and publishes it to a permanent "latest" Release with a plain download URL —
perfect for the Fire TV's **Downloader** app. Everything below can be done in a phone browser.

1. **Create a free GitHub account** at github.com if you don't have one.
2. **Create a new repository** (top right "+" > "New repository"). Public is easiest since
   Release download links work without login on public repos. Name it e.g. `ott-hub`.
3. **Open a Codespace on it**: on the repo page, tap **Code > Codespaces > Create codespace
   on main**. This gives you a full cloud dev environment with a terminal, running in your
   phone's browser.
4. In the Codespace file explorer, use **Upload** (or drag-and-drop) to upload the
   `FireTVOTTLauncher.zip` file into the Codespace.
5. Open the terminal in the Codespace (bottom panel, or Ctrl+backtick) and run:
   ```bash
   unzip FireTVOTTLauncher.zip
   cp -r FireTVOTTLauncher/. .
   rm -rf FireTVOTTLauncher FireTVOTTLauncher.zip
   git add -A
   git commit -m "Add OTT Hub project"
   git push
   ```
6. The push triggers the build automatically. Go to your repo's **Actions** tab and watch
   the "Build APK" workflow run (takes a few minutes).
7. Once it's green, go to the repo's **Releases** section (right sidebar on the repo home
   page, or `github.com/<your-username>/ott-hub/releases`). You'll see a **"latest"**
   release with `app-debug.apk` attached — that link is a stable, direct-download URL.
8. **On the Fire TV Stick**, install the free **Downloader** app from the Amazon Appstore
   (search "Downloader"), enable **Apps from Unknown Sources** in Settings > My Fire TV >
   Developer Options (see below), then open Downloader and paste in the release's APK URL.
   It downloads and installs directly — no PC, no cable.

Every time you push a new commit, the workflow rebuilds and refreshes that same `latest`
release, so the Downloader URL stays the same across updates.

### Option B — Build locally in Android Studio
1. Install the latest **Android Studio** (Koala or newer).
2. `File > Open`, select the `FireTVOTTLauncher` folder.
3. Let Gradle sync (it will download the Gradle 8.x wrapper + dependencies on first sync —
   needs internet access).
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
5. When it finishes, click the notification's **"locate"** link, or find the file at:
   `app/build/outputs/apk/debug/app-debug.apk`

   For a signed release build instead: `Build > Generate Signed Bundle / APK`, choose
   **APK**, create/select a keystore, and build the `release` variant.

### 2. Enable sideloading on the Fire TV Stick
On the Fire TV Stick:
1. **Settings > My Fire TV > Developer Options** (if you don't see Developer Options,
   go to **My Fire TV > About** and click the **Fire TV Stick** name 7 times to unlock it).
2. Turn on **ADB Debugging**.
3. Turn on **Apps from Unknown Sources**.
4. Note the Fire TV's IP address: **Settings > My Fire TV > About > Network**.

### 3. Install via ADB over Wi-Fi
On your computer (with Android Studio's `platform-tools` on your PATH):

```bash
adb connect <FIRE_TV_IP_ADDRESS>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

If `adb` isn't found, it lives in the Android SDK's `platform-tools` folder, e.g.
`~/Library/Android/sdk/platform-tools` (macOS) or `%LOCALAPPDATA%\Android\Sdk\platform-tools`
(Windows) — add it to your PATH or run it with the full path.

### 4. Launch
The app appears under **Your Apps & Channels** on the Fire TV home screen (it may take a
minute, or a manual refresh, to show up) — thanks to the `LEANBACK_LAUNCHER` intent filter
it gets a proper home-screen tile rather than needing a sideloaded-apps app to open it.

### Alternative install methods
- **Downloader app**: host the built APK somewhere reachable (e.g. a personal web server
  or cloud storage direct-download link), then use the **Downloader** app (available in
  the Amazon Appstore on the Fire TV itself) to enter the URL and install directly on the
  device — no computer/ADB needed.
- **Send files to TV** app: similar flow via a companion phone app.
