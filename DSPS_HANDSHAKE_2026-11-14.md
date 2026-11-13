# Stock ZKC26B — 108-byte GATT capability response after enabling DSPS flow CCCD

**Observed:** Saturday 14 November 2026, Australia/Melbourne (UTC+11); phone-hosted GATT peripheral; original-firmware tag `A0:A3:10:56:CC:0E`.

## Controlled variable in this run

Android probe has a **default-off** option named “Experimental DSPS compatibility: add 5CB9 NOTIFY + CCCD”. In this run it was **ON** when the phone registered its GATT service. It only changes characteristic properties and makes its flow CCCD writable. **No automatic notifications to either characteristic are transmitted.** No manual HEX payload or image chunk was sent during the captured connection.

Raw log:

```text
2026-11-14 07:46:22.578 +1100 GATT server opened, waiting for service registration BEFORE advertising | 5CB9 NOTIFY+CCCD=true (no auto flow-control sends)
2026-11-14 07:46:22.578 +1100 Registered 5CB7 service with 5CB8, 5CB9, 5CBA; now advertising
2026-11-14 07:46:22.595 +1100 Advertiser started
2026-11-14 07:47:08.441 +1100 Remote connected: A0:A3:10:56:CC:0E status=0
2026-11-14 07:47:08.444 +1100 MTU=247
2026-11-14 07:47:09.230 +1100 Remote CCCD=0100 char=5CB8 (subscribed=true)
2026-11-14 07:47:09.270 +1100 DSPS FLOW CCCD=0100 char=5CB9 (subscribed=true) — no flow message automatically sent
2026-11-14 07:47:09.312 +1100 Remote WRITE 5cba len=108 data=01A0A31056CC0E00000000006801005B8100000BA1825A860202003888010128009889000000095A4B433236422D4E418A00007530000054600000000C000151808C018D028E078F000000009100920293020100940704D2C68B110269950B5E96000075309A0101AD2FCBAF
2026-11-14 07:47:09.319 +1100 5CBA unrecognised reply (len=108); not treated as ACK
2026-11-14 07:47:24.322 +1100 Remote disconnected: 0
2026-11-14 07:48:24.692 +1100 Remote connected: A0:A3:10:56:CC:0E status=0
2026-11-14 07:48:24.695 +1100 MTU=247
2026-11-14 07:48:27.318 +1100 Remote disconnected: 0
```

The **108-byte application write occurred 42 ms after the 5CB9 CCCD subscription**, 82 ms after 5CB8 subscription and 871 ms after the connection callback. There was no outgoing notification in this connection.

The **second connection** was short and did not subscribe to either CCCD before disconnecting; do not treat it as a failed reproduction of the first subscription/response sequence.

## Structural findings from the exact packet (zero-based byte offsets)

| Offset | Bytes | Observation / confidence |
| --- | --- | --- |
| `0` | `01` | Same leading marker as existing 30-byte messages; significance unknown |
| `1..6` | `A0 A3 10 56 CC 0E` | Exact BLE MAC bytes (verified) |
| `7..11` | `00 00 00 00 00` | Five zero bytes; do not assume counter vs state |
| `12` | `68` = 104 | **Equals total length minus four**: 108-4. Existing 30-byte messages have `1A` = 26 = 30-4. Strong shared framing clue; semantics not established |
| `13` | `01` | Differs from `09` at same offset in 30-byte messages; candidate message class |
| `14..15` | `00 5B` = 91 (big-endian) | Equals 108-17; previous `00 0D` = 13 equals 30-17. Another structural relationship; meaning not established |
| `16..33` | `81 00 00 0B A1 82 5A 86 02 02 00 38 88 01 01 28 00 98` | Looks like a structured field sequence; no validated parser |
| `34` | `89` | Candidate field/tag marker before model name |
| `35..38` | `00 00 00 09` | Big-endian integer 9; matches following ASCII string length |
| `39..47` | `5A 4B 43 32 36 42 2D 4E 41` | ASCII **`ZKC26B-NA`** (9 chars); identification-like field confirmed |
| `48..103` | `8A ... 9A 01 01` | Further structured/configuration-like bytes; field types/units unknown |
| `104..107` | `AD 2F CB AF` | Variable-looking four-byte trailer; **not validated as CRC** (standard zlib CRC32 over `bytes[0..103]` does not match) |

Full HEX, intact:

```text
01A0A31056CC0E00000000006801005B8100000BA1825A860202003888010128009889000000095A4B433236422D4E418A00007530000054600000000C000151808C018D028E078F000000009100920293020100940704D2C68B110269950B5E96000075309A0101AD2FCBAF
```

**Important interpretation:** this is consistent with a device-information/capability announcement at GATT initialization, not a confirmed image ACK. The `5CB9` CCCD may be required for the tag's startup path. Mere temporal correlation does **not** distinguish a change in characteristics from an ordinary startup message or other session state. The required A/B comparison is still pending.

## Immediate next experiment (no HEX transmission)

1. **ON:** restart GATT server with the DSPS option enabled; wait for a fresh connection and both CCCD subscriptions, and log whether another 108-byte `5CBA` write appears. Repeat for at least two *fully subscribed* sessions if practical.
2. **OFF:** restart with DSPS option disabled. Without sending any notifications, record the tag's subscriptions, characteristic reads/writes and any `5CBA` message across similar fresh connection windows.
3. Compare `5CBA` payloads byte-by-byte: stable model fields, startup/session counters and trailer. Record exact delay from `5CB9` CCCD enabling and from connection.
4. **Do not transmit** flow-control byte `01` or a bulk image until the initialization handshake is understood. Do not infer a successful display-update procedure from the 108-byte packet.

The earlier 1,280-packet sweep and nine failed isolated candidate replays were performed with the previous GATT schema (5CB9 lacked notify/CCCD). Their failure to reproduce may be due to incomplete service properties or session state, but that has not been proven.

Related: [RESEARCH_LEADS_CN.md](RESEARCH_LEADS_CN.md), [RESEARCH.md](RESEARCH.md), [Renesas Serial Port Service architecture](https://lpccs-docs.renesas.com/UM-B-088-DA1453x-Serial-Port-Service-Reference-Application/Architecture/architecture.html).
