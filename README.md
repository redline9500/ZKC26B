# ZKC26B BLE protocol research probe (EXPERIMENTAL)

## What was established

The owner's black **ZKC26B E4**, BLE MAC `A0:A3:10:56:CC:0E` (derived from its NFC record),
connected from its original firmware to an Android phone running nRF Connect with
manufacturer data `89 06 08 A0 A3 10 56 CC 0E`, a scan-response service UUID
`0783B03E-8535-B5A0-7140-A304D2495CB7`, and phone-hosted GATT characteristics
ending `5CB8`, `5CB9`, `5CBA`. The log confirmed the tag enabled notifications on
`5CB8` via `CCCD = 01 00`. A single `00` notification produced no application ACK.

The donor **ZK26SF-N** 1MiB `fw.bin` has an exact embedded 128-bit binary copy of all four UUIDs:

| File offset | UUID suffix |
|---:|---|
| 0x3CEE8 | 5CB7 |
| 0x3CED8 | 5CB8 |
| 0x3CEB8 | 5CB9 |
| 0x3CEC8 | 5CBA |

This strongly corroborates a shared ZKONG BLE app protocol but is **not an image
format specification**. DO NOT flash `fw.bin` to the ZKC26B.

## Android Studio source project

This folder contains a native Java Android app (not an APK). It uses only the Android
SDK APIs and no external application dependencies. It was authored without a local
Android SDK, so **it has not been compiled or tested on a handset yet**.

1. Open this folder as a project in Android Studio on Windows 11. It expects Android
   SDK 35, JDK 17+, and Gradle Android plugin 8.7.3 (the IDE can download these).
   If your Android Studio requires a Gradle wrapper, let it create one; this ZIP
   doesn't include the wrapper JAR. An offline/air-gapped build is not guaranteed.
2. Build/debug onto your Android phone (Android 8.0+; tested code targets Android 15
   APIs and is intended for Android 16 too). Grant "Nearby Devices" permission.
3. Stop any nRF Connect advertiser/server before starting the probe app; two apps
   competing to advertise the same service may cause misleading results.
4. Tap **Start GATT server + advertiser**. The service is registered *before*
   advertising starts. It should connect when the tag scans and sees its MAC.
5. Inspect log: remote connection, MTU, CCCD `0100`, writes to `5CB9`/`5CBA`.
6. The app will NEVER send image bytes just because the tag subscribes.
   An explicit tap is required.
7. To experiment with the raw-image theory, transfer `zkc26_candidate_2plane_152x296.bin`
   to your phone, press **Load**, and tap **Send FIRST raw chunk ONLY**. Watch for any
   ACK. No automatic next chunks unless the checkbox is checked (OFF by default).

## Safety and interpretation

- Advertising and GATT messaging occur on your phone, without changing firmware.
- The same service UUID is present in the ZK26SF firmware; control and image payload
  compatibility with ZKC26B **is not fully demonstrated**.
- The *only* verified successful display refresh in AgentMing's ZKC42V project uses
  a different command channel (`6275...`) and a 400x300 three-colour panel.
  Its independent *phone-as-GATT-server* code streams two raw planes in 230-byte
  chunks, beginning after a notification subscription. Whether that workflow
  refreshes this ZKC26B is unknown.
- `zkc26_candidate_2plane_152x296.bin` is a hypothesis-based **raw image candidate**,
  not a validated four-colour image. Display results are unpredictable; it may overwrite
  original content. Do not transmit a full image or firmware unless the result of
  the first-chunk experiment warrants it.
- The `ACK >= 35 bytes => send next` rule is from the ZKC42V repository, not
  proved with ZKC26B. Treat all auto-ACK sends as experimental and leave disabled.
- The prototype does not implement cloud authentication, encryption, or OTA.
- Bluetooth notification success proves *local enqueue*, not necessarily application ACK.

## References

- https://github.com/AgentMing/zkc42v-ai-usage/blob/main/mac/BLEProbe.swift
- https://github.com/AgentMing/zkc42v-ai-usage
- https://github.com/OpenEPaperLink/OpenEPaperLink/issues/604
- https://www.zkong.com/products/electronic-shelf-labels

## Next evidence needed

A ZKC26B response on `5CB9`/`5CBA` after the **first** 230-byte raw chunk, or
an actual image-transfer capture from a ZKONG app on the exact model. A reply
would confirm flow-control compatibility but not guarantee four-colour pixel mapping.

## Alternative: manual first-chunk test using nRF Connect (no Android Studio)

After the ZKC26B connects and the `5CB8` descriptor says **Notifications enabled**:

1. Confirm server MTU has negotiated at least 233 (normally 247).
2. On the phone's **SERVER** tab, tap the upward arrow on `5CB8`.
3. Select **BYTE ARRAY**, then paste the contents of `first_chunk_230_hex.txt`
   (460 hexadecimal characters = 230 bytes, all `FF`). Send **once**.
4. Examine the server log for a `Write request to characteristic` ending
   `5CB9` or `5CBA`. Stop on unexpected disconnection or visible change.

This is the **first 230-byte portion of a hypothetical 2-plane stripe test**.
It is *not* a proven protocol-valid update. Lack of a write response remains
inconclusive. The large packet can alter tag image buffer data, so perform only
if you're comfortable changing the original screen content. Do not send
repeated chunks blindly.
