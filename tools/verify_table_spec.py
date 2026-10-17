#!/usr/bin/env python3
"""Read-only verification of every compiled edit slot against the exact SYSTEM ELF."""
import argparse
import hashlib
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
SHA256 = '649C9F11CE3D2682C877A20272DD924D65C83E5500100596880D4CCEFE89A1CA'
FLAGS = {
    'msce': 'mi-surround-compressor-steering-enable',
    'mdle': 'mi-dv-leveler-steering-enable',
    'miee': 'mi-ieq-steering-enable',
    'mdee': 'mi-dialog-enhancer-steering-enable',
    'ieon': 'ieq-enable',
    'deon': 'dialog-enhancer-enable',
    'dvle': 'volume-leveler-enable',
}
COMMON = {
    'dea': ('dialog-enhancer-amount', 0, 9),
    'dvli': ('volume-leveler-in-target', -640, 0),
    'dvlo': ('volume-leveler-out-target', -640, 0),
    'vmb': ('volume-max-boost', 0, 192),
}
TABLES = {0: 0x16c8, 1: 0x18e8, 2: 0x1b08, 3: 0x1d28, 8: 0x2168, -1: 0x25a8}


def verify(library):
    data = library.read_bytes()
    assert len(data) == 27584 and hashlib.sha256(data).hexdigest().upper() == SHA256, 'Wrong SYSTEM artifact'
    assert data[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
    header = (ROOT / 'src/native/table_spec.h').read_text(encoding='utf-8')
    assert f'kLibrarySha256="{SHA256}"' in header
    assert 'kLibrarySize=27584' in header
    # The exact tables reside in the first non-executable, read-only PT_LOAD.
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entry_size, count = struct.unpack_from('<HH', data, 54)
    loads = [struct.unpack_from('<IIQQQQQQ', data, phoff + i * entry_size) for i in range(count)]
    assert any(p[0] == 1 and p[1] == 4 and p[2] == p[3] == 0 and p[5] == 0x2f7c for p in loads)

    expected = {}
    rendering_bytes = set()
    for profile, offset in TABLES.items():
        actual_profile, records = struct.unpack_from('<ii', data, offset)
        assert actual_profile == profile and records == (24 if profile == -1 else 18)
        cursor = offset + 8
        for _ in range(records):
            device, parameter, n = struct.unpack_from('<IIi', data, cursor)
            assert 0 < n <= 256
            value_offset = cursor + 12
            values = struct.unpack_from('<' + 'i' * n, data, value_offset)
            code = parameter.to_bytes(4, 'little').rstrip(b'\0').decode('ascii')
            if code in ('mave', 'dom', 'hvrc', 'svrc'):
                rendering_bytes.update(range(cursor, value_offset + n * 4))
            if profile != -1 and code in FLAGS:
                key, low, high, flag = FLAGS[code], 0, 1, True
            elif profile != -1 and code == 'dvla':
                key, low, high, flag = 'volume-leveler-amount', 0, 9, False
            elif profile == -1 and code in COMMON:
                key, low, high = COMMON[code]
                flag = False
            else:
                cursor = value_offset + n * 4
                continue
            assert device == 0x40000000 and n == 1
            expected[(profile, key)] = (value_offset, parameter, values[0], 0 if flag else values[0], low, high, flag, code)
            cursor = value_offset + n * 4
        assert cursor - offset == (664 if profile == -1 else 544)

    pattern = re.compile(r'\{0x([0-9a-f]+)ULL,0x([0-9a-f]+)U,(-?\d+),(-?\d+),(-?\d+),"([a-z-]+)",(-?\d+),(-?\d+),(true|false),"([^"]+)"\}')
    records = pattern.findall(header)
    assert len(records) == len(expected) == 44, 'Expected exactly 44 fixed slots'
    seen = set()
    neutral = bytearray(data)
    value_bytes = set()
    for offset, parameter, original, target, profile, key, low, high, flag, name in records:
        identity = (int(profile), key)
        assert identity not in seen and identity in expected, 'Duplicate or unknown scope/key'
        seen.add(identity)
        values = (int(offset, 16), int(parameter, 16), int(original), int(target), int(low), int(high), flag == 'true')
        assert values == expected[identity][:7], f'Wrong metadata for {identity}'
        address, parameter_id, baseline, target_value, _, _, is_flag = values
        code = expected[identity][7]
        assert name.endswith('_' + code)
        assert name.startswith('Common_' if identity[0] == -1 else f'profile{identity[0]}_')
        assert struct.unpack_from('<IIIi', data, address - 12) == (0x40000000, parameter_id, 1, baseline)
        value_bytes.update(range(address, address + 4))
        if is_flag:
            struct.pack_into('<i', neutral, address, target_value)
    assert seen == set(expected)
    assert not value_bytes.intersection(rendering_bytes), 'Rendering data entered the allowlist'
    changed = {i for i, (before, after) in enumerate(zip(data, neutral)) if before != after}
    assert changed <= value_bytes and len(changed) == 14, 'Neutral simulation changed unexpected bytes'
    assert data == library.read_bytes(), 'Source artifact changed during verification'
    print('Verified 44 exact SYSTEM slots: 35 flags, 5 per-profile amounts, 4 Common numbers.')
    print('Profiles -1,0,1,2,3,8 only; every full-record prefix/original/range matches.')
    print('Neutral simulation changes 14 flag words; numeric and all rendering records remain unchanged.')
    print('SYSTEM SHA256 ' + SHA256)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=ROOT.parent / 'device/system-libspatializerparamstorage.so')
    verify(parser.parse_args().library)
