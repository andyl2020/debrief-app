#!/usr/bin/env python3
"""Fail when a 64-bit native library cannot run on a 16 KB-page Android device."""

import struct
import sys
import zipfile

REQUIRED_ALIGNMENT = 0x4000
ABIS = ("arm64-v8a", "x86_64")


def load_segment_alignments(data: bytes) -> list[int]:
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected a little-endian 64-bit ELF library")
    program_offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size = struct.unpack_from("<H", data, 54)[0]
    entry_count = struct.unpack_from("<H", data, 56)[0]
    alignments = []
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        if struct.unpack_from("<I", data, offset)[0] == 1:  # PT_LOAD
            alignments.append(struct.unpack_from("<Q", data, offset + 48)[0])
    if not alignments:
        raise ValueError("ELF library has no load segments")
    return alignments


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_16kb_elf.py APP.apk", file=sys.stderr)
        return 2
    failures = []
    with zipfile.ZipFile(sys.argv[1]) as apk:
        names = apk.namelist()
        for abi in ABIS:
            libraries = sorted(name for name in names if name.startswith(f"lib/{abi}/") and name.endswith(".so"))
            if not libraries:
                failures.append(f"{abi}: no native libraries found")
                continue
            for name in libraries:
                try:
                    alignments = load_segment_alignments(apk.read(name))
                    aligned = all(value >= REQUIRED_ALIGNMENT for value in alignments)
                    print(f"{'PASS' if aligned else 'FAIL'} {name}: {', '.join(hex(value) for value in alignments)}")
                    if not aligned:
                        failures.append(name)
                except (ValueError, struct.error) as error:
                    failures.append(f"{name}: {error}")
    if failures:
        print("16 KB compatibility check failed: " + "; ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
