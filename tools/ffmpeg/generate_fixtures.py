#!/usr/bin/env python3
"""Generate synthetic tones and packet fixtures for the isolated Android decoder check.

Requires host ffmpeg/ffprobe. Outputs are test data, never household recordings.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess


def run(*args: str) -> bytes:
    return subprocess.check_output(args)


def extradata(value: str) -> bytes:
    result = bytearray()
    for line in value.splitlines():
        if ':' in line:
            result.extend(bytes.fromhex(line.split(':', 1)[1].split('  ', 1)[0].strip()))
    return bytes(result)


def generate(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    codecs = [
        ('aac', 'audio/mp4a-latm', 'aac', 'm4a', []),
        ('ac3', 'audio/ac3', 'ac3', 'ac3', []),
        ('eac3', 'audio/eac3', 'eac3', 'eac3', []),
        ('mp2', 'audio/mpeg-L2', 'mp2', 'mp2', []),
        ('mp3', 'audio/mpeg', 'libmp3lame', 'mp3', ['-write_xing', '0', '-id3v2_version', '0']),
    ]
    fixtures = []
    for name, mime, encoder, extension, options in codecs:
        media = output / f'tone.{extension}'
        run('ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
            '-f', 'lavfi', '-i', 'sine=frequency=997:sample_rate=48000:duration=2',
            '-ac', '2', '-c:a', encoder, '-b:a', '192k', '-map_metadata', '-1',
            '-fflags', '+bitexact', '-flags:a', '+bitexact', *options, str(media))
        probe = json.loads(run('ffprobe', '-v', 'error', '-show_streams', '-show_packets',
                               '-show_data', '-of', 'json', str(media)))
        stream = probe['streams'][0]
        data = media.read_bytes()
        packets = []
        for packet in probe['packets']:
            pos, size = int(packet['pos']), int(packet['size'])
            frame = data[pos:pos + size]
            if len(frame) != size:
                raise ValueError(f'incomplete {name} packet')
            packets.append(base64.b64encode(frame).decode())
        fixtures.append({
            'name': name, 'mime': mime, 'media': media.name,
            'sampleRate': int(stream['sample_rate']), 'channels': int(stream['channels']),
            'initializationData': [base64.b64encode(extradata(stream['extradata'])).decode()]
                if stream.get('extradata') else [],
            'packets': packets, 'sha256': hashlib.sha256(data).hexdigest(),
        })
    manifest = {
        'description': 'Two-second generated sine tones; no recordings or external media.',
        'ffmpeg': run('ffmpeg', '-version').decode().splitlines()[0],
        'fixtures': fixtures,
    }
    (output / 'fixtures.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Generated {len(fixtures)} synthetic codec fixtures.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    generate(parser.parse_args().output)
