# Read-only image transport research — 13 November 2026

**Update 14 November 2026:** see [RESEARCH_LEADS_CN.md](RESEARCH_LEADS_CN.md) for first-pass follow-up of Chinese-language OnMicro debugging resources, the official Renesas DSPS GATT specification, and non-ZKONG capture-method references. The original observations below are preserved. The 1,280-short-payload sweep is now complete: nine temporally correlated `5CBA` responses did not reproduce when the nine candidate HEX values were sent individually; one six-packet replay also yielded no `5CBA` write. No opcode is validated. See [SWEEP_RESPONSE_CANDIDATES.md](SWEEP_RESPONSE_CANDIDATES.md).

## Evidence (strong)

The owner used nRF Connect Android to advertise a vendor-specific payload containing
six NFC-derived MAC bytes. Black ZKC26B E4 (`A0:A3:10:56:CC:0E`) connected from its
stock firmware, negotiated ATT MTU=247, and subscribed (`CCCD 01 00`) to a characteristic
ending `5CB8` on the phone's custom service. A one-byte notification `00` was
accepted by the Android stack without an observed response on `5CB9` or `5CBA`.

From the public ZK26SF-N donor `fw.bin` (SHA-256
`10c34fd661ec89f463715913b3731f6ac174275b14e3776abe5d4724aac3874e`),
we extracted the exact four Bluetooth service/characteristic UUID constants:

| Suffix | Dump offset | Full address | Cross-reference pointer at |
|---|---:|---:|---:|
| 5CB7 | 0x3CEE8 | 0x0043CEE8 | 0x26288 |
| 5CB8 | 0x3CED8 | 0x0043CED8 | 0x261B8 |
| 5CB9 | 0x3CEB8 | 0x0043CEB8 | 0x261BC |
| 5CBA | 0x3CEC8 | 0x0043CEC8 | 0x261B4 |

The UUIDs are in reverse-byte order appropriate for their 128-bit representation
in the little-endian firmware. `tools/audit_ble_uuids.py` reproduces this.

An ARM Thumb code section around `0x004260D0` loads 16-byte UUID pointers and
calls a comparison helper at `0x00404390` to identify GATT characteristics;
roughly `0x00426270` references the `5CB7` service UUID. This is **executable
code referencing** those UUIDs, not merely stray strings.

## What the open-source reference actually demonstrates

`AgentMing/zkc42v-ai-usage/mac/BLEProbe.swift` has two separate modes:

1. Working direct-to-tag image update on a **GigaDevice GR5513** ZKC42V using
   a different **6275** command service and two colour planes (400x300 BWR).
2. Experimental phone-as-GATT-server implementation with service `5CB7`,
   notification characteristic `5CB8` and ACK write characteristics `5CB9`/`5CBA`.
   It passes raw image planes from its local `ZKEPD1` container (magic/header
   are not transmitted). Each plane is segmented into 230-byte chunks. First
   notification goes out after subscription, then a write >=35 bytes triggers
   the next chunk. The README does NOT prove this particular method successfully
   updates the ZKC26B.

### Container/payload assumptions in their experimental server

Local container: ASCII `ZKEPD1\n` (7 bytes), then 2-byte little-endian width,
2-byte little-endian height, then two bitplanes, each `width*height/8` bytes.
The *wire* sees the planes concatenated with no `ZKEPD1` header.

For ZKC26B E4's 152x296 screen, this predicts **5,624 bytes per plane** and
**11,248 raw bytes total**. **This is a candidate, not verified.** Two planes
could represent four colour values, but which bit combination maps to which
pigment, bit ordering and refresh trigger are unknown.

At 230-byte chunks a 11,248-byte frame requires 49 notifications (48 full
230-byte chunks and one 208-byte tail). This is *inferred from the other
project's experimental implementation*, not a working ZKC26B procedure.

## Safer next test

1. In the Android probe project, start the custom server **before** advertising.
2. Observe MAC match, MTU and notification subscription.
3. Optionally load the deliberately simple 11,248-byte two-plane **candidate**
   (`zkc26_candidate_2plane_152x296.bin`) and send the **first 230-byte chunk
   only**. It contains a predictable stripe pattern in plane one, not any OTA
   header, and is never automatically transmitted.
4. Inspect whether the tag writes on `5CB9`/`5CBA`. This would establish a
   flow-control response, but not a validated image encoding. Stop if it
   disconnects or shows unexpected screen behaviour.
5. Do not attempt full update until image format and ACK behaviour are better
   evidenced. Avoid using the ZK26SF donor image for flashing.

**Build limitation:** the Android source has not been compiled in this
analysis environment (no Android SDK/Gradle). It is an experimental source
handoff, not a tested APK.

Reference: https://github.com/AgentMing/zkc42v-ai-usage/blob/main/mac/BLEProbe.swift
