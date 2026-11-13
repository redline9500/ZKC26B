#!/usr/bin/env python3
"""Generate a strictly hypothetical raw two-plane 152x296 test frame for manual BLE probing.

This is NOT a validated ZKC26B image format. The first plane holds a stripe pattern,
the second plane all zeros. No firmware or OTA data. Output length 11248 bytes.
"""
import argparse
from pathlib import Path

W, H = 152, 296
PER_PLANE = W * H // 8

def make_pattern():
    one = bytearray(PER_PLANE)
    two = bytearray(PER_PLANE)
    # simple horizontal bars, 16px period, first plane only, hard edges at 8-pixel boundaries
    for y in range(H):
        for x_byte in range(W // 8):
            if (y // 16) % 2 == 0:
                one[y * (W // 8) + x_byte] = 0xff
    return bytes(one) + bytes(two)

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path, default=Path('zkc26_candidate_2plane_152x296.bin'))
    args = p.parse_args()
    args.output.write_bytes(make_pattern())
    print(f'Wrote {args.output} ({args.output.stat().st_size} bytes). This format is unverified.')
