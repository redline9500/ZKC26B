# DSPS reference implementation — 5CB9 flow-control test

**Date:** 2026-11-14 (Australia/Melbourne). **Goal:** test the standard transport handshake independently from ZKONG's unknown application-level image/update packets. This does **not** prove stock ZKC26B implements an unmodified Dialog/Renesas DSPS stack.

## Primary sources

1. [Renesas UM-B-088 Serial Port Service architecture §3.3](https://lpccs-docs.renesas.com/UM-B-088-DA1453x-Serial-Port-Service-Reference-Application/Architecture/architecture.html). Exact UUIDs:
   - Service `...5CB7`.
   - Server TX `...5CB8`, NOTIFY (phone→tag in our setup).
   - Flow-control `...5CB9`, NOTIFY + WRITE WITHOUT RESPONSE.
   - Server RX `...5CBA`, WRITE WITHOUT RESPONSE (tag→phone).
2. **Source code, not only documentation:** Renesas public [DSPS peripheral service implementation](https://github.com/renesas/ble-sdk10-da1469x-examples/blob/main/features/dsps_ble_peripheral/dsps/dsps.c) and [protocol definitions](https://github.com/renesas/ble-sdk10-da1469x-examples/blob/main/features/dsps_ble_peripheral/dsps/include/dsps.h); corresponding [central example](https://github.com/renesas/ble-sdk10-da1469x-examples/blob/main/features/dsps_ble_central/dsps_ble_central_task.c).
3. [Renesas official SPS downloads](https://www.renesas.com/en/software-tool/serial-port-service-sps): reference SDK and mobile-app source packages, some requiring login. These are **BLE transport examples**, not a ZKONG protocol library.
4. [Renesas support example discussing three necessary subscriptions before binary transfer](https://community.renesas.com/wireles-connectivity/f/bluetooth-low-energy/29387/da14531-binary-mode-acknowledgement---invalid-command-outside-of-dialog-app). **CodeLess AT+BINREQ** commands are product-specific and are **not justified tests on ZKC26B**.

## Actual source behavior and exact packets

- The published code defines `DSPS_FLOW_CONTROL_ON = 0x01` and `DSPS_FLOW_CONTROL_OFF = 0x02`.
- In `dsps.c` (`handle_flow_ctrl_ccc_write`), on a client's enabling `5CB9` notifications, the standard server **immediately notifies its current flow-control state**, defaulting to `0x02` if no state is stored.
- `dsps_tx_data` refuses to send data notifications unless **both** the `5CB8` subscription and server flow state `0x01` are present.
- The **central/client** reference example subscribes to `5CB8` and `5CB9`, then **writes `0x01` to `5CB9`** (client→server GATT write without response) after discovery. This write is made by the **central/client**, i.e., by our **tag** in the ZKC26B deployment; the phone cannot emulate it by sending a 5CB8 notification.
- The **server/peripheral** notifies `0x01` or `0x02` on **`5CB9`** (phone→tag). This is the only standard one-byte outbound HEX payload family warranted by the reference; no arbitrary 2-/3-byte ZKONG display opcode is defined by DSPS.
- The `5CBA` **108-byte tag report** we observed five times was sent **without** an intervening phone notification on `5CB9`; this suggests the tag's initial report is not gated by standard DSPS flow state, or the implementation differs. No claim of exact conformance.
- **No publicly verified original-ZKONG application packet constructor** found in this source family. Other devices (smart glasses, measuring tools, vehicle electronics) also use the same UUIDs; copying their application bytes would be invalid.

## What was added to our Android test probe

**One shared manual HEX sender** is now used for both notification characteristics. Select **`5CB8 — data`** or **`5CB9 — flow`**, enter any valid HEX byte string, and tap **Send entered HEX once on selected characteristic**. The old dedicated FLOW_ON/FLOW_OFF buttons were removed. For the standard transport checks, select **5CB9**, then enter **`01`** (FLOW_ON) or **`02`** (FLOW_OFF). Other values/lengths can be entered for future research but are **not established standard DSPS flow signals**.

Sending requires an explicit tap, a connected tag, selected channel's NOTIFY property and CCCD subscription, packet length within ATT MTU-3, and no in-flight image/sweep/manual notification. **For 5CB9, enable Experimental DSPS compatibility before starting the server.** No auto retry, automatic flow state, image data, or firmware operation is triggered.

The app logs requested value, epoch, Android return code and asynchronous notification callback, and receives tag writes as before. **Android's callback is not a ZKONG application-level acknowledgement.** CCCD state resets at reconnect, and old callbacks should not be treated as current results.

**Risk:** `0x01` permits sending data under standard DSPS semantics, but the stock tag could attach additional meaning to it. It may generate further status or data traffic, or end the connection. **Stop** if it behaves unexpectedly. A `0x02` test may cause the client to stop sending and should not be used during image tests.

## Live result — first FLOW_ON notification (2026-11-14 13:30 +1100)

The user selected **5CB9** and sent `01` exactly once, after receiving the 108-byte tag report. Android accepted and completed the notification, **but the tag produced no further application write** before disconnecting 0.918 seconds after the send. The send was **11.197 seconds after the report**; the session ended **12.115 seconds after the report**. Previous no-send sessions usually ended around 15 seconds post-report, but had a short outlier, so a single late flow notification is inconclusive. **No demonstrated FLOW_ON response or longer connection.**

**Next:** repeat only with an *early* 5CB9 `01` (preferably within 1–2 seconds after the 108-byte report), compare with a no-send control, and keep precise timestamps. This is a directional DSPS transport test, not validation of an image opcode. Full exact log in [DSPS_HANDSHAKE_2026-11-14.md](DSPS_HANDSHAKE_2026-11-14.md).

## Queued reply support (14 November 2026)

Because the normal idle connection often ends ~15 seconds after the 108-byte report (and sometimes much earlier), manual tapping is not reliable. The Android probe now supports an **explicitly armed, exactly-one-send queue** based on the shared HEX input and channel selector:

- Start GATT server with DSPS compatibility **ON**.
- Choose **5CB9 (flow)** and enter `01` (or another packet explicitly chosen by the operator).
- Tap **ARM ONE queued send on next 108-byte report**. The app freezes channel+bytes at arm time, logs `QUEUE ARMED`, and shows the pending item in the UI. No transmission is made until a strictly validated 108-byte `ZKC26B-NA` report is written on 5CBA, with correct target MAC and frame markers.
- On a matching report, it sends as soon as the Android callback/UI event can run, logs `QUEUE TRIGGERED`, the Android result, and `QUEUED NOTIFY_CALLBACK_OK/FAILED`. It disarms **before attempting** delivery: no replay, no automatic retries. If disconnected before the trigger it stays armed for the next session; if user stops the GATT server or cancels, it is cleared. Selected characteristic must be subscribed and packet must fit the actual ATT MTU.
- Sweep/guarded-image modes and queued mode cannot run together. The existing manual sender still works separately when the queue is not armed. No queued packet is persisted across app shutdown.

This allows a controlled **early** DSPS FLOW_ON trial and makes the outcome comparable with the 15-second no-send baseline. Neither Android notification success nor a longer/shorter connection alone proves that a ZKONG command was understood.

## Conservative experiment (no ZKONG image bytes)

1. Update to a successful build that includes the **shared manual HEX channel selector**. Ensure **Experimental DSPS compatibility ON** *before starting* the GATT server. Disable sweep and image auto modes; do not load/transmit an image.
2. Let a fresh tag connection subscribe to `5CB8`, then `5CB9`, and produce its expected `108`-byte `5CBA` report. This is our baseline and demonstrates successful client GATT initialization.
3. **While the same fully subscribed connection is active**, choose **5CB9 — flow** in the manual HEX selector, enter **`01`**, and tap **Send entered HEX once on selected characteristic**. Record local enqueue, `onNotificationSent`, any new `5CBA` reply or client write on `5CB9`, disconnect and elapsed time. Do not send it repeatedly or on a session lacking the 5CB9 subscription.
4. If no reaction, don't assume rejection; flow control is normally not acknowledged at the application level. Optionally enter **`02`** with **5CB9 selected** on a later fresh subscribed connection to see if timing or message behavior differs. Do not mix both into the same first test.
5. Compare to sessions with a 108-byte startup report but **no flow notification**; those already exist. Preserve full HEX and precise timestamps.

**Important practical caveat:** ZKC26B sometimes disconnects within ~2–3 seconds after the 108-byte report, so manual tapping may be difficult. This build deliberately does **not** add automated flow-on replies until a human-triggered test justifies it.

See [DSPS_HANDSHAKE_2026-11-14.md](DSPS_HANDSHAKE_2026-11-14.md) for five fully reproduced startup frames and prior ON/OFF comparison.
