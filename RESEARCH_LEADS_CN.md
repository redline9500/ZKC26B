# ZKC26B E4 — Chinese-language research leads and follow-ups

**Reviewed:** 2026-11-14 (Australia/Melbourne). **Scope:** stock ZKC26B E4, preserve original firmware; no destructive actions were performed. This note records first-pass follow-up results, not an assertion of a working image-transfer protocol.

## Baseline (our device, not extrapolated from other boards)

- Black ZKC26B E4; 2.66-inch four-colour panel (candidate raster 152x296); tag initiates BLE *central/client* connection to the phone, which emulates the GATT *peripheral/server*.
- Verified phone-hosted service `0783B03E-8535-B5A0-7140-A304D2495CB7`; `5CB8` notifications phone→tag; `5CBA` 30-byte tag→phone writes; `5CB9` exists in our emulated service but no verified tag write to it in the cited sweep.
- Completed 1,280 short-payload sweep: 9 correlated 30-byte `5CBA` responses, 48–75 ms after latest sends; this **does not identify nine valid commands**. Isolated manual retest of all 9 candidate payloads yielded no application responses. The `B20001` through `B70001` replay at ~1-second intervals also yielded none. Several no-send periods had no observed responses; no formal duration-matched idle control has yet been completed.
- Existing stock-APK research found a ZKONG server-generated `sendDataList` and native-library/server dependencies; no reproducible local encoding/frame constructor. See `RESEARCH.md` and `SWEEP_RESPONSE_CANDIDATES.md`.

## Lead A — Dialog/Renesas Serial Port Service profile — VERIFIED REFERENCE; ZKONG USE UNVERIFIED

**Source (primary vendor documentation):** [Renesas DA1453x Serial Port Service reference architecture](https://lpccs-docs.renesas.com/UM-B-088-DA1453x-Serial-Port-Service-Reference-Application/Architecture/architecture.html), section 3.3.

The four UUIDs have a published profile independent of ZKONG:

| UUID suffix | Standard SPS / DSPS function | Standard properties and direction in our phone-as-server setup |
| --- | --- | --- |
| `5CB7` | Service | phone GATT server |
| `5CB8` | SERVER_TX | notification, phone→tag |
| `5CB9` | FLOW_CTRL | 1-byte write-without-response from client or notification from server; standard values `01` FLOW_ON, `02` FLOW_OFF |
| `5CBA` | SERVER_RX | write-without-response, tag→phone |

Flow control values are **transport signals on `5CB9` only**, not image-display opcodes. A 30-byte write to `5CBA` is **not** a standard DSPS flow-control signal. The UUIDs can be reused on OnMicro hardware and do not show that the tag implements all reference semantics. The CodeLess `AT+BINREQ` sequence in the [Renesas SmartConsole guide](https://lpccs-docs.renesas.com/da1458x_codeless/smartconsole.html) is for CodeLess devices and **must not be assumed applicable** to our tag.

**Completed follow-up:** audited `app/src/main/java/au/example/zkc26ble/MainActivity.java` and the public [AgentMing BLEProbe.swift](https://github.com/AgentMing/zkc42v-ai-usage/blob/main/mac/BLEProbe.swift). Our app exposes `5CB8` with NOTIFY + CCCD; `5CB9` with READ/WRITE/WRITE_NO_RESPONSE but **without NOTIFY or its own CCCD**; `5CBA` with WRITE. AgentMing's experimental server also lacks `5CB9` NOTIFY despite adding a CCCD descriptor. This differs from the generic published SPS profile, but the deviation may reflect ZKONG's variant. The observed tag has subscribed to `5CB8`, not proven to expect `5CB9` notifications.

**Implemented after first-pass research (not yet tested on a tag):** the Android probe now has a default-off **Experimental DSPS compatibility** option that adds `5CB9` NOTIFY + its own CCCD when the GATT server starts. Descriptor reads/writes and single-byte flow candidates are separately logged; `5CB8` and `5CB9` subscriptions are tracked independently. The app transmits **no automatic flow commands**. Keep option OFF for a control session, then turn ON, restart GATT server and compare initial subscription/flows with no packet sends. This is diagnostic work, **not validation of the standard SPS protocol on ZKONG**. Do not invent/send `01` as an application command.

## Lead B — OnMicro OM6626 debugging/dump technique — DOCUMENTED BY VENDOR FORUM; DEVICE READOUT UNVERIFIED

**Primary source:** [关于Dump 6626带睡眠的现场数据](https://bbs.onmicro.com.cn/OM6626/730.html) (roughly “Dumping a sleeping OM6626's live state,” 2026).

- When GDB Server cannot attach because the OM6626 sleeps, the poster describes **Keil → Options → Debug → Settings (Use J-Link/J-Trace) → SCAN**. Keil repeatedly attempts a debug connection until it detects the SW device and halts the CPU.
- After connection/halt, the poster opens GDB Server and uses **`om6626_dump.exe`** to obtain a dump.
- **Screenshot follow-up:** [debugger/GDB screenshot](https://bbs.onmicro.com.cn/static/upload/2026/09/05/69194313.png) shows SEGGER J-Link GDB Server **V7.92j**, an **OM6626B101** target **halted** via SWD at **3.30 V / 2000 kHz** (conditions of the author's setup, **not** recommended settings for our unit), and a terminal running the dump utility. [Output directory screenshot](https://bbs.onmicro.com.cn/static/upload/2026/09/05/20753415.png) shows files named **`flash.fbin`, `sram.bin`, `em.ebin`** plus `gdb.log`. This is direct evidence that the author's dump utility generates a flash-named artifact; it does **not** prove unrestricted firmware extraction from ZKC26B hardware.
- **Limitations:** the post does not establish authentic executable availability, CLI arguments, memory ranges and completeness, flash readout-protection status, or whether it can extract a full ZKC26B factory image. Halting the MCU (and apparently stopping its watchdog in the author's session) changes its running state even when no flash writes occur. The exact chip/revision and pad wiring on our *own* unit have not been independently verified.

**Completed follow-up:** source verified; forum screenshots inspected and outputs identified; procedure and blockers extracted. No debugger session, dump, erase, chip-unlock, or firmware operation was attempted.

**Additional source-code follow-up:** the previously known [InkTag repository](https://github.com/solar2ain/ink-tag) contains an inspectable [OM6626 backup entrypoint](https://github.com/solar2ain/ink-tag/blob/main/firmware/om6626_zkel/tools/internal/runtime/firmware/ports/onmicro/om6626/tools/backup.ps1) and [J-Flash read backend](https://github.com/solar2ain/ink-tag/blob/main/firmware/om6626_zkel/tools/internal/runtime/firmware/ports/onmicro/om6626/tools/internal/backup-jlink.ps1). The code requests **two independent 1 MiB reads** from **0x00400000**, verifies each read length and the J-Flash success log, compares SHA-256, and saves the raw factory backup plus logs. The [project template](https://github.com/solar2ain/ink-tag/blob/main/firmware/om6626_zkel/tools/internal/runtime/firmware/ports/onmicro/om6626/tools/om6626-readonly-v788j.jflash) has no automatic erase/program/start-app steps. It uses SEGGER J-Flash **7.88j**, a separate `OM662XX_NOMBR` device pack/loader, a low initial attach speed, and intentionally **does not back up external SPI Flash**. It is a plausible **reference implementation for backup planning**, *not* proof that its generic backend supports the exact OM66268 revision or can read through protection; J-Link and the loader can still halt/reset or modify RAM during attachment. Do **not** run this on the ZKC26B until the actual board, memory map, loader trust and protections are independently checked.

**Independent silicon verification:** [OnMicro's official OM6626 specification](https://www.onmicro.com.cn/enBLE/406.html) and [SEGGER's OM6x knowledge base](https://kb.segger.com/OnMicro_OM6x) both identify **ARM Cortex-M4**, **1 MiB on-chip serial flash** and **SWD** for the OM6626 family. This conflicts with the **M0/M0+** CPU identification asserted in the known ZKC26B hardware issue; the issue may be mistaken. SEGGER identifies a **CPFT factory calibration region 0x00403000–0x00403FFF that must not be overwritten**. These are family-level statements, not proof our particular chip and board use identical layouts. Identifying the exact silicon remains a gate.

**Next safe step:** collect *non-invasive* PCB/MCU identification and confirm accessible debug pad labels, expected voltage, SWD connections, power management and any read-protection behaviour from the exact-board documentation. Locate an authentic `om6626_dump.exe` distribution and verify provenance, and audit InkTag's generic backup workflow without running it before touching hardware. Do **not** run “unlock,” mass erase, recovery flash or other potentially destructive commands to bypass protection. Read-only attachment still requires prior risk assessment.

## Lead C — OM6626 developer resources — VERIFIED TOOL INDEX; SDK NOT PUBLICLY VERIFIED

**Primary source:** [昂瑞微：OM6626开发汇总](https://bbs.onmicro.com.cn/OM6626/381.html), updated March 2025.

The index names:
- Keil MDK **V5.36** guidance and an OnMicro Keil device plugin `BLE-OM66XXX-KEIL-1.4.0`.
- `OmBleIspStudio` ISP download/programming tool, `OmBleConfigurator`, J-Link OB debugging pointers, hardware reference notes and pinmux.
- It explicitly says the **full SDK requires NDA/business or technical contact**; a public example may be incomplete.

**Followed references:**
- [Keil setup / firmware flashing overview](https://bbs.onmicro.com.cn/OM6626/361.html): an attached RAR tutorial; concerns programming and debug setup, not proof of stock-image readout.
- [OnMicro Keil plugin](https://bbs.onmicro.com.cn/BLEzhzl/87.html): enables OM662x device selections in Keil, but installing a plugin does not grant firmware access.
- [OM6626 hardware reference](https://bbs.onmicro.com.cn/OM6626/358.html); [pinmux resource](https://bbs.onmicro.com.cn/OM6626/555.html); [ISP tool](https://bbs.onmicro.com.cn/BLEzhzl/89.html).

**Next step:** obtain and study publicly available hardware/reference documentation *offline*; verify chip identity and memory layout before any debugger attachment. Keep ISP, configuration, and flashing applications out of the read-only workflow.

## Lead D — OM6626B SDK request from another ZKONG developer — VERIFIED REQUEST; NOT A SOLUTION

**Source:** [申请 OM6626B BLE 单从机完整 SDK 与开发资料](https://bbs.onmicro.com.cn/OM6626/739.html), 2026.

The requester has a **different** ZKONG tag, `ZKC42V` / `ZK_EL_OM42V_V1.0` / `OM6626B`, and is attempting standalone Web Bluetooth e-paper control. They state the public `ble_app_centre_hrs` example lacks the HAL, ROM libraries/symbols, scatter/linker files and shared SDK components necessary for a complete build. They request memory layout and factory-calibration preservation guidance, plus terms for an NDA for individuals.

**Completed follow-up:** confirmed these specific blockers from the forum post. The visible page lists replies but does **not expose a verified solution or complete SDK**; no claim that an SDK can presently be downloaded.

**Next step:** revisit thread for a public answer or release; compare its OM6626B tooling only after confirming the exact chip in our device. Do not copy another model's board configuration or firmware.

## Lead E — Reverse-engineering method via packet capture (not ZKONG)

**Source:** [少数派：抓包解协议，代码造布局](https://sspai.com/post/78993), 26 March 2023.

A separate ESL's app logs revealed an image packet with command byte `13`, sequence byte, payload length `B4` (=180), payload, trailing check byte; another command `12` carried a red plane. The author found 26 full packets plus a 56-byte tail, totalling 4,736 pixel bytes per plane for a 296x128 display.

**Source-code follow-up:** the article links [gadore/ink-screen-bluetooth](https://github.com/gadore/ink-screen-bluetooth), whose `app.js` implements `FFE0` service / `FFE2` characteristic on **a different tag**. Its per-chunk checksum is the **sum of pixel data bytes modulo 256**, it uses 180-data-byte chunks with the fields above, and sends a final four-byte `50 00 01 01` command. This gives us a complete, inspectable example of *how* another tag frames two colour planes; it supplies **no reason** to transmit those bytes to ZKC26B.

**Transferable method, not transferable wire format:** collect a **known-good** update, isolate relevant BLE traffic by characteristic and time, compare sequential packets, infer length/checksum and final refresh. Our ZKC26B's `5CB8` uses notifications in the opposite direction from many phone-central products; `13`, `12`, `50` and `B4` are **not validated ZKC26B commands**.

**Next step:** if a known-good image update from the ZKONG app/server is available, save the encoded `sendDataList` and pair it with a full GATT trace. Compare two images differing by one pixel or colour; look for headers, address/sequence fields, trailer, checksum and request-dependent server output.

## Lead F — Original-board reuse / hardware method (other tags)

**Source:** [数码之家：墨水屏电子价签原版利用](https://www.mydigit.cn/thread-514818-1-1.html), April 2025.

Covers **other** e-paper boards (nRF51822/51802, CC-series and DA14585) and advocates identifying MCU, display/controller, test pads and programming interfaces before firmware work. It neither names ZKC26B nor provides its stock protocol. Some examples involve destructive disassembly/flashing.

**Next step:** retain as a board-identification checklist only. Do not use its pinouts or flashing procedures on the OnMicro board.

## Existing leads (not discoveries from this pass)

- [OpenEPaperLink issue #604](https://github.com/OpenEPaperLink/OpenEPaperLink/issues/604): previously known ZKC26B E4 / OM66268 board investigation; not yet a complete working firmware port.
- [InkTag](https://github.com/solar2ain/ink-tag): previously known active firmware project with OM6626 support on *other boards*; its [board list](https://github.com/solar2ain/ink-tag/blob/main/docs/zh-CN/supported-devices.md) does **not** certify our ZKC26B board. Do not flash it merely because the chip family matches.
- [EPD-nRF5](https://github.com/tsl0922/EPD-nRF5): open custom firmware implementing the separate `6275...` GATT service; not a way to connect to the stock ZKC26B over its `5CB7` service.

## Sequential follow-up ledger

| Order | Investigation | Status on 2026-11-14 | Next decision/gate |
| --- | --- | --- | --- |
| 1 | Map generic SPS/DSPS profile against our actual Android GATT table | **Source verified, default-off compatibility instrumentation committed; handset test pending** | Compare baseline OFF vs ON after restart, without transmitting data or speculative flow injection |
| 2 | Validate OnMicro dump workflow and its limits | **Forum output files verified; open InkTag double-read backup implementation audited; actual tag readout unknown** | Verify exact chip, ROM/flash layout, CPFT calibration and read protection before any physical probe |
| 3 | Trace OM6626 tool/doc index | **Index and linked pages identified** | Obtain genuine read-only documentation; no ISP actions |
| 4 | Track OM6626B SDK request | **Request verified, no public usable SDK confirmed** | Recheck thread for public answer; separate model/revision |
| 5 | Extract methodology from Chinese packet-capture writeup | **Method extracted** | Apply only to a real known-good ZKC26B update, if one can be captured |
| 6 | Review other-board reuse thread | **General checklist only** | No direct ZKC26B payload/firmware action |

**Policy for this project:** preserve stock firmware; separate Android transport callbacks from tag application messages; avoid firmware erase/unlock/flash, OTA, or invasive probes until device-specific evidence and a recoverable backup exist. Document source date, actual device variant, experiment context and raw results for each new finding.
