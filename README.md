> **Version 0.5.0: automated five-stage, 1,280-packet sweep.** See [SWEEP_GUIDE.md](SWEEP_GUIDE.md) for exact payload order, first-reply stop, automatic log export, manual starting point, and reconnect behaviour.

# ZKC26B BLE protocol research probe (EXPERIMENTAL)

## New live handshake finding — 14 November 2026

**Repeated startup-class messages observed without any sent image/HEX payload:** five **108-byte** `5CBA` payloads followed the tag's subscriptions to `5CB8` and newly exposed `5CB9` flow-control CCCD by **42–47 ms** (after `5CB9`). All contain ASCII `ZKC26B-NA`, a four-byte sequence incrementing **0→4**, and a seconds-like counter whose **+614** change agrees with **614.091 s** elapsed wall time. The old GATT schema may have been incomplete, but a default-OFF control is still required. Full raw log, byte offsets and next tests: [DSPS_HANDSHAKE_2026-11-14.md](DSPS_HANDSHAKE_2026-11-14.md). **Do not assume this is a validated image ACK or transmit flow-control bytes automatically.**

## Current external research (14 November 2026)

See [RESEARCH_LEADS_CN.md](RESEARCH_LEADS_CN.md) for verified Chinese-language OnMicro resources, the published Dialog/Renesas DSPS UUID/flow-control mapping, open questions about the stock ZKC26B, and a sequential follow-up ledger. The source confirms that `5CB9` is normally a one-byte FLOW_ON/FLOW_OFF characteristic; it does **not** establish that ZKONG uses the reference application unmodified. The existing app's **default** `5CB9` lacks DSPS's standard NOTIFY/CCCD configuration. A default-off **Experimental DSPS compatibility** option is now available for a controlled comparison on the next GATT server start. It sends no flow-control bytes and is not evidence that ZKONG follows the standard.

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

This folder contains **v0.2.0** of a native Java Android app (not an APK). It uses only the Android
SDK APIs and no external application dependencies. The owner successfully built and
ran v0.1.0 on Android 16, confirming GATT connections and 30-byte 5CBA replies.
**The new v0.2.0 changes have not yet been Android-built or handset-tested in this environment.**

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
   When matching 30-byte replies arrive, inspect decoded counter, unknown four-byte
   field and trailing four-byte value. **They are not proven positive ACKs.**
6. Tap **Copy log to clipboard** to share the full retained log (up to 2,500 lines).
   Log text is also manually selectable. Top/bottom safe-area spacing prevents overlap
   with Android 15/16 status/navigation bars.
7. The app will NEVER send image bytes just because the tag subscribes.
   An explicit tap is required to send the first image chunk.
8. To experiment with the raw-image theory, transfer `zkc26_candidate_2plane_152x296.bin`
   to your phone, press **Load**, and tap **Send FIRST raw chunk ONLY**. The new
   guarded auto-reply mode is OFF by default; it is explicitly experimental, has
   stricter format checks, a 3-additional-chunk per-connection limit and a 4-second
   per-auto-chunk reply timeout. It stops on unexpected responses or disconnects;
   the transfer cursor then resets to zero rather than blindly resuming.

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
- ZKC26B now demonstrably writes 30-byte messages on `5CBA` after raw chunk
  notifications. The observed counter often increases 0,1,2,3 and restarts on
  reconnection, but the `00000000`, `FF000000`, `FFFFFF00` values are not decoded.
  There is **no basis yet to call all such messages successful ACKs**.
- The ZKC42V's old `ACK >= 35 bytes => send next` rule is **not used in v0.2**.
  Instead there is an explicit, conservative 30-byte reply **test** with strict
  formatting, a small per-connection cap, and no automatic reconnection resume.
  Do not use it for a full image until the status meaning is verified.
- The prototype does not implement cloud authentication, encryption, or OTA.
- Bluetooth notification success proves *local enqueue*, not necessarily application ACK.

## References

- https://github.com/AgentMing/zkc42v-ai-usage/blob/main/mac/BLEProbe.swift
- https://github.com/AgentMing/zkc42v-ai-usage
- https://github.com/OpenEPaperLink/OpenEPaperLink/issues/604
- https://www.zkong.com/products/electronic-shelf-labels

## Next evidence needed

We now **have** 30-byte responses on `5CBA` after manual image chunks. The next
requirement is decoding their status and trailer fields, identifying the exact
ZKC26B transfer framing, and observing a known-good display refresh. A reply alone
does not establish correct image format or successful transfer.

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