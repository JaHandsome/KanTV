#!/usr/bin/env python3
"""Replace FFmpeg JNI in an APK, remove invalid signatures, and align for user signing."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

ABI_ELF = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86': (1, 3), 'x86_64': (2, 62)}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def is_signature(name):
    return bool(re.fullmatch(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))', name, flags=re.I))


def check_elf(data, abi):
    elf_class, machine = ABI_ELF[abi]
    if len(data) < 20 or data[:4] != b'\x7fELF' or data[4:6] != bytes([elf_class, 1]) or struct.unpack_from('<H', data, 18)[0] != machine:
        raise ValueError('Replacement ELF ABI mismatch: ' + abi)


def rewrite(apk, relinked, target):
    replaced = {}
    with zipfile.ZipFile(apk) as source, zipfile.ZipFile(target, 'x') as dest:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        for item in source.infolist():
            if is_signature(item.filename):
                continue
            data = source.read(item)
            match = re.fullmatch(r'lib/([^/]+)/libffmpegJNI\.so', item.filename)
            if match:
                abi = match.group(1)
                if abi not in ABI_ELF:
                    raise ValueError('Unsupported ABI: ' + abi)
                replacement = (relinked / abi / 'libffmpegJNI.so').read_bytes()
                check_elf(replacement, abi)
                replaced[item.filename] = {'original_sha256': digest(data), 'replacement_sha256': digest(replacement)}
                data = replacement
            dest.writestr(item, data)
    if not replaced:
        raise ValueError('No FFmpeg JNI entry in APK')
    return replaced


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--relinked', type=Path, required=True, help='Output directory from r2 relink.py')
    p.add_argument('--build-tools', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True, help='New unsigned aligned APK; original APK is untouched')
    a = p.parse_args()
    if a.output.exists() or a.output.with_suffix('.json').exists():
        raise ValueError('Output already exists')
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='kantv-recombine-') as tmp:
        raw = Path(tmp) / 'unsigned.apk'
        replacements = rewrite(a.apk, a.relinked, raw)
        subprocess.run([a.build_tools / 'zipalign', '-P', '16', '-f', '4', raw, a.output], check=True)
    subprocess.run([a.build_tools / 'zipalign', '-c', '-P', '16', '4', a.output], check=True)
    # Check every unmodified entry's decompressed bytes after repacking/alignment.
    with zipfile.ZipFile(a.apk) as original, zipfile.ZipFile(a.output) as result:
        expected = {name for name in original.namelist() if not is_signature(name)}
        if set(result.namelist()) != expected:
            raise ValueError('Recombined entry set changed unexpectedly')
        for name, entry in replacements.items():
            if digest(result.read(name)) != entry['replacement_sha256']:
                raise ValueError('Aligned replacement bytes differ')
        for name in expected - replacements.keys():
            if original.read(name) != result.read(name):
                raise ValueError('Unexpected entry modification')
    record = {'input_apk_sha256': digest(a.apk.read_bytes()), 'unsigned_apk_sha256': digest(a.output.read_bytes()),
              'replaced': replacements, 'unmodified_entries_verified': True, 'signature': 'unsigned; sign with your own key before installation'}
    a.output.with_suffix('.json').write_text(json.dumps(record, indent=2) + '\n')
    print('Recombined and aligned; %d JNI entries. Sign with your own key before installing.' % len(replacements))


if __name__ == '__main__':
    main()
