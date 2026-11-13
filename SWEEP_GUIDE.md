# ZKC26B BLE Probe — five-stage automated sweep (v0.5)

This is an experimental diagnostic for a **personally owned** ZKC26B-E4. The firmware and packet format are unknown. It does not pair, flash or call ZKONG's backend. Testing arbitrary opcodes is not guaranteed safe: an unexpected opcode could change device state, reset parameters or initiate a mode change. Stop if the tag behaves unexpectedly.

## Exactly which packets are sent?

**1,280 total**, enumerated in this exact order:

| Global index | Values (hex) | Example first/last |
| --- | --- | --- |
| `#0..#255` | one byte `XX` | `00` ... `FF` |
| `#256..#511` | two bytes `XX 00` | `00 00` ... `FF 00` |
| `#512..#767` | two bytes `XX 01` | `00 01` ... `FF 01` |
| `#768..#1023` | three bytes `XX 00 00` | `00 00 00` ... `FF 00 00` |
| `#1024..#1279` | three bytes `XX 00 01` | `00 00 01` ... `FF 00 01` |

Each payload is sent to the **phone's GATT server characteristic `...5CB8` as an unconfirmed notification**, only after the label has connected and written `0100` to the `5CB8` CCCD. At most **one test packet per second**, measured from Android's notification completion callback; only one notification is outstanding.

## Critical stop condition

**At the first application-level write by the label to `...5CB9` OR `...5CBA`, stop the sweep immediately**, regardless of the response length, whether it matches the previously seen 30-byte packets, or whether the response indicates success.

Stopping happens on the Bluetooth callback thread via an atomic flag + send lock, before the UI callback is scheduled. The response is appended synchronously to the log along with the last attempted outgoing packet, response hex/length, timestamp, elapsed milliseconds since that packet, and saved next index. The server remains open, but **no more automatic probe packets are sent**. The **Resume** button is disabled after a hit; starting again requires explicit entry of a new point.

`0100` CCCD writes **do not count as responses** (they are descriptors, not application characteristics). Likewise, PHY updates and disconnects are not responses.

**Caveat:** A response may be delayed relative to the preceding probe. The logged last packet is a *correlation candidate*, not proven to be the cause. At 1-second pacing this ambiguity is reduced, not eliminated. In particular, a silent malformed message does not prove the packet was rejected by the device's actual protocol parser.

## Running the sweep

1. Close nRF Connect's GATT server and advertiser. Do not use both apps as servers simultaneously.
2. Open this probe, enable Bluetooth and grant Nearby Devices permission if requested.
3. Press **Start GATT server + advertiser** and wait for service registration; the tag initiates connection automatically.
4. In **Start HEX**, enter a valid packet or global index. Default `00` starts at the beginning. Press **START SWEEP from entered packet (new saved log)** and confirm. You may arm it before the label connects.
5. Leave the app **visible and the screen on**. The window is configured to stay awake while the sweep is running. Unlike a foreground-service app, the sweep is **not guaranteed to continue after Android kills the Activity or when switched to the background**.
6. On a normal BLE disconnect the sweep waits automatically for the tag to reconnect and subscribe again. It resumes at the saved next packet. On an uncertain transport callback it pauses rather than skipping or blindly retrying.
7. If a reply is received, the UI shows **STOPPED: response captured**. Press **Export last saved sweep log (.txt)** and save it to Files/Downloads or a shared location. Logs are already saved automatically in the application's private external files while testing; export is for convenient retrieval.

## Manual start-point examples

- `7F` → one-byte `7F`, global index `#127`.
- `7F00` → two-byte `7F 00`, global index `#383`.
- `7F01` → two-byte `7F 01`, global index `#639`.
- `7F0000` → three-byte `7F 00 00`, global index `#895`.
- `7F0001` → three-byte `7F 00 01`, global index `#1151`.
- `#1024` → `00 00 01`, first packet of the final stage.

Spaces, colons and hyphens between hex pairs are accepted. Inputs not in the five requested patterns are refused.

## Saving, pausing and resuming

A new run creates a uniquely named timestamped `.txt` file under the app's `sweep_logs` directory and records every event immediately, using a disk flush for durability. The current **next index** is committed to `SharedPreferences` after Android confirms each notification transmission. That is **not** a tag acknowledgement. If the Android process exits, the next index and last log path survive; relaunch the app, start the GATT server, and press **Resume from last saved position**. An in-flight packet that did not receive a transport completion callback may be repeated. The process will not auto-arm after an app restart.

Manual **Pause** preserves the current position. **Stop and close** also pauses the sweep. The **Copy log** button copies only the recent in-memory view; **Export last saved sweep log** exports the full on-disk log.

## Timing estimate

At one packet per second plus connection overhead, the full 1,280-packet sweep requires at least **21 minutes 20 seconds** of successful subscribed connection time. If sessions last five minutes and reconnect immediately, it would span at least **five sessions**. Actual time depends on the tag's repeated short connections, retry schedule and Android background constraints.

## Build and validation

The included GitHub Actions workflow uses Java 17, Android SDK 35 and Gradle 8.9. It runs pure-Java `Reply30Test` and `SweepPlanTest` and assembles the Android debug APK. The `SweepPlanTest` verifies all 1,280 payloads, uniqueness, endpoints, and manual start-point parsing.

**Note:** The code and JVM tests were checked in the local container. A full Android Gradle build and physical-device run were not possible in that container; build using the included workflow and verify the behaviour on your device. Debug APK signing and upgrade compatibility depend on the signing key used for your previously installed APK.