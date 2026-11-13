# Build the ZKC26B BLE Probe as an Android debug APK

**Status:** Source is untested and has **not** been compiled in the assistant environment, which lacks the Android SDK and Gradle. The GitHub Actions configuration is provided as a build route, not as evidence of a successful build. Don't treat the app as validated until the build succeeds and you test it on your phone.

## Option 1 — GitHub Actions (no Android Studio installation)

1. Create a **private** GitHub repository (e.g. `zkc26b-ble-probe`) under your own account.
2. Upload **the contents of this project directory to the repository root**, including the hidden `.github/workflows/android-debug-apk.yml` folder. Do not upload `fw.bin` or unrelated firmware dumps; the project does not need them.
3. Go to your repository's **Actions** tab → **Build debug APK** → **Run workflow** (or wait for the workflow triggered by the push).
4. If the build succeeds, open the workflow run → **Artifacts** → download `ZKC26B-BLE-Probe-debug-APK`.
5. Extract the artifact ZIP. Inside should be `app-debug.apk`. Transfer it to your Android phone and install it. Android may require you to allow installation from the file-management app.

The APK is automatically signed with Android's standard **development/debug signing key**. It is intended only for your own testing, not Play Store distribution.

## Option 2 — Android Studio on Windows 11

1. Install Android Studio from https://developer.android.com/studio and let it install Android SDK Platform 35 / Build Tools 35.0.0 and the JDK.
2. **File → Open** and select the project directory containing `settings.gradle`.
3. Let Gradle sync. The project requests Android Gradle plugin 8.7.3 and Gradle 8.9 (Gradle wrapper is not included, so Studio may prompt for a Gradle installation).
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Find the output at `app/build/outputs/apk/debug/app-debug.apk`.

## Testing precautions

- Before starting this app, stop nRF Connect's advertiser/GATT server; two simultaneous BLE advertisers can interfere.
- On first run, grant the Android Nearby Devices permissions.
- Tap **Start GATT server + advertiser**. The app will register the 5CB7 service and 5CB8/5CB9/5CBA characteristics first.
- Wait for the ZKC26B to connect. Watch for `Remote CCCD=0100` and `MTU=247` (actual values vary).
- The app does not send image data automatically. The optional 11,248-byte candidate is **not a verified ZKC26B format**. Keep auto-ACK sending off and don't transmit a full image.
- If the phone's BLE behaviour differs from nRF Connect, share the app's log. A successful Gradle build does not prove BLE or image-transfer compatibility.
