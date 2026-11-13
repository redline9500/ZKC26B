#!/usr/bin/env python3
"""Read-only audit of exact ZKONG GATT UUIDs and code references in a 1 MiB dump.

Usage: python audit_ble_uuids.py /path/to/fw.bin
Model data and calibration are NOT printed. No firmware changes.
"""
import argparse
import hashlib
import struct
import uuid
from pathlib import Path

BASE=0x00400000
PREFIX='0783b03e-8535-b5a0-7140-a304d249'


def matches(data: bytes, needle: bytes):
    start=0
    while True:
        found=data.find(needle, start)
        if found<0:return
        yield found
        start=found+1


def audit(path: Path):
    blob=path.read_bytes()
    print(f'File bytes={len(blob)}, sha256={hashlib.sha256(blob).hexdigest()}')
    for suffix in ['5cb7','5cb8','5cb9','5cba']:
        key=uuid.UUID(PREFIX+suffix).bytes[::-1]
        offsets=list(matches(blob,key))
        print('\n'+suffix.upper()+': '+' '.join(hex(i) for i in offsets))
        for o in offsets:
            ptr=struct.pack('<I',BASE+o)
            xrefs=list(matches(blob,ptr))
            print('   linked flash address '+hex(BASE+o)+' referenced by literal pointers at '+' '.join(hex(x) for x in xrefs))
    print('\nInterpretation: identical 16-byte constants and literal references prove UUIDs are present in firmware, not that image encoding is identical.')

if __name__=='__main__':
    ap=argparse.ArgumentParser()
    ap.add_argument('firmware', type=Path)
    args=ap.parse_args()
    audit(args.firmware)
