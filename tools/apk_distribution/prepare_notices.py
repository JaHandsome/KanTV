#!/usr/bin/env python3
"""Reconcile releaseRuntimeClasspath with exact POMs and archive notices (stdlib only)."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_pom(cache, coordinate, seen=None):
    seen = set() if seen is None else seen
    if coordinate in seen:
        raise ValueError('POM parent cycle')
    seen.add(coordinate)
    group, name, version = coordinate.split(':')
    candidates = list((cache / group / name / version).glob('*/*.pom'))
    if len(candidates) != 1:
        raise ValueError('Expected one resolved POM: ' + coordinate)
    data = candidates[0].read_bytes()
    tree = ET.fromstring(data)
    licenses = [{'name': x.findtext('m:name', '', NS), 'url': x.findtext('m:url', '', NS)}
                for x in tree.findall('m:licenses/m:license', NS)]
    evidence = {coordinate: data}
    if not licenses:
        parent = tree.find('m:parent', NS)
        if parent is None:
            raise ValueError('No license or parent POM: ' + coordinate)
        parent_id = ':'.join(parent.findtext('m:' + key, '', NS) for key in ('groupId', 'artifactId', 'version'))
        licenses, ancestors, _ = read_pom(cache, parent_id, seen)
        evidence.update(ancestors)
    return licenses, evidence, tree


def license_id(licenses):
    result = []
    for entry in licenses:
        name = entry['name']
        if 'Apache' in name:
            result.append('Apache-2.0')
        elif 'Mozilla' in name:
            result.append('MPL-2.0')
        elif name == 'BSD-3-Clause':
            result.append(name)
        elif name == 'Android Software Development Kit License':
            result.append('LicenseRef-Android-SDK')
        else:
            raise ValueError('Unreviewed license: ' + name)
    return sorted(set(result))


def notice_entries(data, prefix=''):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith('/'):
                continue
            base = name.rsplit('/', 1)[-1].lower()
            if re.match(r'^(license|notice|copying)([._-].*)?$', base) or base.startswith('third_party_licenses.'):
                yield prefix + name, archive.read(name)
            elif name == 'classes.jar' or name == 'assets/omni.ja':
                yield from notice_entries(archive.read(name), prefix + name + '!/')


def prepare(inventory, cache, ndk, output):
    coordinates = [row['coordinate'] for row in inventory]
    if len(coordinates) != len(set(coordinates)):
        raise ValueError('Multiple runtime artifacts for one coordinate require explicit review')
    if output.exists():
        raise ValueError('Output must be a new directory')
    output.mkdir(parents=True)
    notices = output / 'notices'
    notices.mkdir()
    poms = output / 'poms'
    poms.mkdir()
    records = []
    for row in sorted(inventory, key=lambda row: row['coordinate']):
        coordinate = row['coordinate']
        artifact = Path(row['file'])
        data = artifact.read_bytes()
        record = {'coordinate': coordinate, 'file': artifact.name, 'sha256': sha(data), 'notices': []}
        if coordinate.startswith('local:'):
            provenance = dict(line.split('=', 1) for line in (ROOT / 'app/libs/media3-ffmpeg-provenance.properties').read_text().splitlines() if '=' in line)
            if artifact.name != provenance['aar.file'] or sha(data) != provenance['aar.sha256']:
                raise ValueError('Unreviewed local runtime dependency')
            record.update(licenses=['Apache-2.0', 'LGPL-2.1-or-later'], corresponding_source=provenance['source.bundle.url'], corresponding_source_sha256=provenance['source.bundle.sha256'])
        else:
            licenses, evidence, tree = read_pom(cache, coordinate)
            record.update(licenses=license_id(licenses), declared_licenses=licenses)
            record['pom_evidence'] = {}
            for pom_id, pom_data in evidence.items():
                filename = pom_id.replace(':', '__') + '.pom'
                (poms / filename).write_bytes(pom_data)
                record['pom_evidence'][filename] = sha(pom_data)
            if coordinate.startswith('org.mozilla.geckoview:'):
                record['source_revision'] = tree.findtext('m:scm/m:tag', '', NS)
                record['source_repository'] = tree.findtext('m:scm/m:url', '', NS)
                if record['source_revision'] != '86bb7f6af6312ba3c0161085f854bcdff68f1a91':
                    raise ValueError('Gecko source notice must be reviewed for changed revision')
        for origin, text in notice_entries(data):
            suffix = '.html' if origin.endswith('.html') else '.json' if origin.endswith('.json') else '.txt'
            filename = sha(text) + suffix
            (notices / filename).write_bytes(text)
            record['notices'].append({'origin': origin, 'file': filename, 'sha256': sha(text)})
        records.append(record)
    # Complete original notices for the statically linked libc++ from the pinned NDK.
    for name in ('NOTICE', 'NOTICE.toolchain'):
        shutil.copyfile(ndk / name, notices / ('NDK-r28c-' + name + '.txt'))
    manifest = {'configuration': 'releaseRuntimeClasspath', 'components': records,
                'ffmpeg_provenance_sha256': sha((ROOT / 'app/libs/media3-ffmpeg-provenance.properties').read_bytes()),
                'native_toolchain': {'version': '28.2.13676358', 'notices': {
                    name: sha((ndk / name).read_bytes()) for name in ('NOTICE', 'NOTICE.toolchain')}},
                'scope': 'Resolved runtime inputs; not a claim that R8 retains every class. Includes local AAR. No build-only dependencies.'}
    (output / 'runtime-components.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    lines = ['KanTV APK runtime components', '', 'Exact releaseRuntimeClasspath inputs and preserved upstream notices.',
             'Full license texts and source instructions: assets/licenses/README.txt.', '']
    lines.extend(r['coordinate'] + ' | ' + ', '.join(r['licenses']) for r in records)
    (output / 'runtime-components.txt').write_text('\n'.join(lines) + '\n')
    return manifest


def check_generated(generated):
    expected = ROOT / 'third_party/apk-runtime'
    for path in sorted(generated.rglob('*')):
        if path.is_file():
            target = expected / path.relative_to(generated)
            if not target.is_file() or path.read_bytes() != target.read_bytes():
                raise ValueError('Runtime materials drift: ' + str(path.relative_to(generated)))
    actual_names = {p.relative_to(expected) for p in expected.rglob('*') if p.is_file()}
    expected_names = {p.relative_to(generated) for p in generated.rglob('*') if p.is_file()}
    if actual_names != expected_names:
        raise ValueError('Stale runtime material files')
    for name in ('runtime-components.json', 'runtime-components.txt'):
        if (generated / name).read_bytes() != (ROOT / 'app/src/main/assets/licenses' / name).read_bytes():
            raise ValueError('APK runtime index drift')
    for path in (generated / 'notices').iterdir():
        target = ROOT / 'app/src/main/assets/licenses/runtime-notices' / path.name
        if not target.is_file() or path.read_bytes() != target.read_bytes():
            raise ValueError('APK embedded notice drift')


def verify_inventory(inventory):
    """CI gate: compare resolved bytes and all committed notices without an NDK install."""
    base = ROOT / 'third_party/apk-runtime'
    assets = ROOT / 'app/src/main/assets/licenses'
    manifest = json.loads((base / 'runtime-components.json').read_text())
    if sha((ROOT / 'app/libs/media3-ffmpeg-provenance.properties').read_bytes()) != manifest['ffmpeg_provenance_sha256']:
        raise ValueError('FFmpeg provenance drift')
    expected = {r['coordinate']: r['sha256'] for r in manifest['components']}
    actual = {r['coordinate']: sha(Path(r['file']).read_bytes()) for r in inventory}
    if len(actual) != len(inventory) or actual != expected:
        raise ValueError('Resolved runtime coordinate/artifact drift; regenerate and review materials')
    for name in ('runtime-components.json', 'runtime-components.txt'):
        if (base / name).read_bytes() != (assets / name).read_bytes():
            raise ValueError('Embedded runtime index differs')
    notice_files = {}
    pom_files = {}
    for record in manifest['components']:
        for entry in record['notices']:
            notice_files[entry['file']] = entry['sha256']
        pom_files.update(record.get('pom_evidence', {}))
    for name, value in manifest['native_toolchain']['notices'].items():
        notice_files['NDK-r28c-' + name + '.txt'] = value
    for folder, files in ((base / 'poms', pom_files), (base / 'notices', notice_files), (assets / 'runtime-notices', notice_files)):
        if {p.name for p in folder.iterdir() if p.is_file()} != set(files):
            raise ValueError('Missing or stale notice/POM files')
        for name, value in files.items():
            if sha((folder / name).read_bytes()) != value:
                raise ValueError('Notice/POM hash differs: ' + name)
    supplemental = json.loads((ROOT / 'third_party/apk-supplemental.json').read_text())
    for entry in supplemental['files']:
        if sha((assets / entry['file']).read_bytes()) != entry['sha256']:
            raise ValueError('Supplemental runtime notice differs: ' + entry['file'])
    return len(expected)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--inventory', type=Path, required=True, help='Local JSON from runtime_inventory.gradle; never publish local paths')
    p.add_argument('--cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
    p.add_argument('--ndk', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--check', action='store_true')
    p.add_argument('--verify-inventory', action='store_true', help='Lightweight CI gate; no NDK required')
    a = p.parse_args()
    if a.verify_inventory:
        print('Verified runtime material integrity: %d components' % verify_inventory(json.loads(a.inventory.read_text())))
        return
    if a.ndk is None or a.output is None:
        p.error('--ndk and --output are required for generation')
    version = (a.ndk / 'source.properties').read_text()
    if '28.2.13676358' not in version:
        raise ValueError('Pinned NDK r28c required')
    manifest = prepare(json.loads(a.inventory.read_text()), a.cache, a.ndk, a.output)
    if a.check:
        check_generated(a.output)
    print('Runtime material check: %d components, %d preserved notices' % (len(manifest['components']), len(list((a.output / 'notices').iterdir()))))


if __name__ == '__main__':
    main()
