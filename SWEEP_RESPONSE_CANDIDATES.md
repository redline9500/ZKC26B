# BLE sweep response candidates

**Retest update — 14 November 2026:** the 1,280-packet sweep completed, with nine 30-byte `5CBA` replies associated temporally with indices `416`, `502`, `631`, `873`, `911`, `963`, `961` (re-run of an earlier interval), `1034`, and `1207`. Their last-sent HEX candidates were `A000`, `F600`, `7701`, `690000`, `8F0000`, `C30000`, `C10000`, `0A0001`, `B70001`. All nine were retested individually and yielded **no application-level response**. The five preceding packets plus `B70001` were replayed at approximately one-second intervals, also with no application response. In the first manual pass `870001` was accidentally entered instead of `B70001`; it was corrected in a subsequent pass. Android status=0 callbacks are not tag ACKs. Other preceding-window tests were incomplete due to disconnections. No command or traffic-trigger theory is confirmed. [New external research ledger](RESEARCH_LEADS_CN.md).

Record every packet closely followed by a tag-to-phone write to 5CB9 or 5CBA. A correlated response is **not proof** the last packet is a valid opcode. Collect candidates first, then conduct isolated retests after the sweep.

## Candidate 1 — `A000`

| Field | Recorded value |
| --- | --- |
| Local time | 2026-11-14 03:05:36.503 +1100 (Australia/Melbourne) |
| Sent payload | `A0 00` |
| Sweep index | `416` of `1280` (zero-based) |
| Send attempt | 03:05:36.445 +1100 |
| Notification callback | 03:05:36.462 +1100 (`status=0`) |
| Response | 03:05:36.503 +1100 |
| Reported age since send | 57 ms |
| Inbound characteristic | `0783b03e-8535-b5a0-7140-a304d2495cba` |
| Inbound length | 30 bytes |
| App result | `SWEEP HIT STOP IMMEDIATE`; saved next index `417` |
| Next untested packet | `A100`, **not** `A001` |
| Status | **Unconfirmed candidate; retest later** |

Reply (raw HEX):

```text
01A0A31056CC0E00000000001A09000D006C006D01006F0070060A3B2386
```

The response followed `A000` closely, but 30-byte writes to `5CBA` have also been observed after image-data chunks. Do not interpret these writes as ACKs or proof of a working command without controlled reproduction.

## Retest plan (after collecting candidates)

- Run candidate `A000` alone, preferably in a fresh BLE session, with at least several seconds of observation before and after.
- Run a matched negative control such as `9F00` or `A100` and a no-payload session; compare unsolicited status traffic rates.
- Repeat candidate on multiple connections; note timing relative to the last packet and connection start.
- Preserve exact 30-byte replies and compare fields with previous unsolicited replies, including any changing counters.
- Continue the original sweep from `A100` only when ready to resume manually. The app blocks automatic resume on a hit by design.

Update this document when additional hits occur. Do not classify an opcode as validated until reproduced.