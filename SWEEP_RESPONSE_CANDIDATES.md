# BLE sweep response candidates

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