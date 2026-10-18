#!/usr/bin/env python3
"""Package corresponding source, licenses, build records and tested relink inputs."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import sys
import zlib
import tarfile
import zipfile

RECIPE = Path(__file__).resolve().parent
PINS = json.loads((RECIPE / 'pins.json').read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_gradle_wrapper(repo):
    """Bind the launcher and its complete notices to the reviewed upstream record."""
    notices = repo / 'third_party/gradle-wrapper'
    record = json.loads((notices / 'SOURCE.json').read_text())
    expected = {
        repo / 'gradle/wrapper/gradle-wrapper.jar': record['launcher']['sha256'],
        **{notices / name: record['files'][name]['sha256'] for name in ('LICENSE', 'NOTICE')},
    }
    for path, sha256 in expected.items():
        if digest(path) != sha256:
            raise RuntimeError('Gradle wrapper provenance mismatch: ' + path.relative_to(repo).as_posix())
    return notices


def deterministic_tar(directory, target):
    with target.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as archive:
            for path in sorted(directory.rglob('*')):
                if not path.is_file():
                    continue
                info = tarfile.TarInfo('ffmpeg-corresponding-source/' + path.relative_to(directory).as_posix())
                info.size = path.stat().st_size
                info.mtime = PINS['source_date_epoch']
                info.mode = 0o755 if path.name in ('gradlew', 'relink.py') else 0o644
                archive.addfile(info, io.BytesIO(path.read_bytes()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output, sdk, java_home = args.output.resolve(), args.sdk.resolve(), args.java_home.resolve()
    repo = RECIPE.parent.parent
    notices = verify_gradle_wrapper(repo)
    source = output / 'corresponding-source'
    source.mkdir()
    module = output / 'build-1/module'
    ndk = sdk / 'ndk' / PINS['ndk']
    native_tools = ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'

    def copy(origin, relative):
        dest = source / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, dest)

    for name in ('build_reproducible.py', 'package_sources.py', 'pins.json', 'README.md', 'verification-metadata.xml'):
        copy(RECIPE / name, 'tools/ffmpeg/' + name)
    copy(RECIPE / 'patches/0001-deterministic-build.patch', 'tools/ffmpeg/patches/0001-deterministic-build.patch')
    for name in ('gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'):
        copy(repo / name, name)
    for name in ('LICENSE', 'NOTICE', 'SOURCE.json'):
        copy(notices / name, 'third_party/gradle-wrapper/' + name)
    for item in (PINS['media3'], PINS['ffmpeg']):
        copy(output / 'downloads' / item['archive'], 'source-archives/' + item['archive'])
    copy(output / 'result.json', 'build-records/double-build-result.json')
    for path in (module / 'src/native-configs').rglob('*'):
        if path.is_file():
            copy(path, 'native-configs/' + path.relative_to(module / 'src/native-configs').as_posix())
    for path in (module / 'src/main/assets/ffmpeg-licenses').iterdir():
        copy(path, 'licenses/' + path.name)
    for filename in ('NOTICE', 'NOTICE.toolchain'):
        copy(ndk / filename, 'licenses/NDK-' + filename)
    verification = module / 'gradle/verification-metadata.xml'
    copy(verification, 'build-records/gradle-verification-metadata.xml')
    copy(RECIPE / 'verification-metadata.xml', 'wrapper/gradle/verification-metadata.xml')
    # This is the complete upstream wrapper source; FFmpeg source is in the pinned original archive.
    for path in (module / 'src/main').rglob('*'):
        relative = path.relative_to(module / 'src/main')
        if path.is_file() and not relative.as_posix().startswith('jni/ffmpeg/'):
            copy(path, 'wrapper/src/main/' + relative.as_posix())
    for filename in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'proguard-rules.txt'):
        text = (module / filename).read_text().replace(str(output / 'build-1'), '/src/build').replace(str(ndk), '/opt/android-ndk')
        (source / 'wrapper' / filename).write_text(text)
    expected = {}
    aar = next((output / 'build-1').glob('*.aar'))
    with zipfile.ZipFile(aar) as archive:
        for abi in PINS['abis']:
            expected[abi] = hashlib.sha256(archive.read('jni/' + abi + '/libffmpegJNI.so')).hexdigest()
    commands = {}
    for abi in PINS['abis']:
        for path in (module / 'src/main/jni/ffmpeg/android-libs' / abi).glob('*.a'):
            copy(path, 'relink/' + abi + '/' + path.name)
        object_file = max((module / '.cxx').glob('Release/*/' + abi + '/CMakeFiles/ffmpegJNI.dir/ffmpeg_jni.cc.o'), key=lambda path: path.stat().st_mtime_ns)
        copy(object_file, 'relink/' + abi + '/ffmpeg_jni.cc.o')
        build_dir = object_file.parents[2]
        raw = subprocess.check_output([sdk / 'cmake/3.22.1/bin/ninja', '-C', build_dir, '-t', 'commands'], text=True).splitlines()[-1]
        raw = raw.removeprefix(': && ').removesuffix(' && :')
        tokens = shlex.split(raw)
        converted = []
        skip_target = False
        for token in tokens:
            if skip_target:
                converted.append('{output}/' + abi + '/libffmpegJNI.so')
                skip_target = False
                continue
            if token == '-o':
                skip_target = True
                converted.append(token)
                continue
            if token == 'CMakeFiles/ffmpegJNI.dir/ffmpeg_jni.cc.o':
                token = '{source}/relink/' + abi + '/ffmpeg_jni.cc.o'
            token = token.replace(str(module / 'src/main/jni/ffmpeg/android-libs' / abi), '{source}/relink/' + abi)
            token = token.replace(str(output / 'build-1'), '/src/build').replace(str(ndk), '{ndk}')
            converted.append(token)
        commands[abi] = converted
    (source / 'relink-commands.json').write_text(json.dumps({'commands': commands, 'expected_sha256': expected}, indent=2) + '\n')
    (source / 'relink.py').write_text('''#!/usr/bin/env python3
"""Relink all four JNI libraries with the supplied or modified FFmpeg archives."""
import argparse, hashlib, json, subprocess
from pathlib import Path
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
source, ndk, output = Path(__file__).resolve().parent, a.ndk.resolve(), a.output.resolve()
record = json.loads((source / 'relink-commands.json').read_text())
result = {}
for abi, command in record['commands'].items():
    target = output / abi / 'libffmpegJNI.so'
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([arg.format(source=source, ndk=ndk, output=output) for arg in command], check=True)
    subprocess.run([ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip', '--strip-unneeded', target], check=True)
    actual = hashlib.sha256(target.read_bytes()).hexdigest()
    result[abi] = {'sha256': actual, 'matches_original': actual == record['expected_sha256'][abi]}
(output / 'result.json').write_text(json.dumps(result, indent=2) + '\\n')
print(json.dumps(result, indent=2))
''')
    subprocess.run(['python3', source / 'relink.py', '--ndk', ndk, '--output', output / 'relinked'], check=True)
    relink_result = json.loads((output / 'relinked/result.json').read_text())
    if not all(value['matches_original'] for value in relink_result.values()):
        raise RuntimeError('Relink verification differs from distributed AAR')
    copy(output / 'relinked/result.json', 'build-records/relink-result.json')
    tools = {
        'host': {'system': platform.system(), 'release': platform.release(), 'machine': platform.machine()},
        'versions': {'python': sys.version, 'zlib_compile': zlib.ZLIB_VERSION, 'zlib_runtime': zlib.ZLIB_RUNTIME_VERSION}, 'sha256': {},
    }
    for label, tool, version_args in (
        ('python', Path(sys.executable), ['--version']),
        ('make', Path('/usr/bin/make'), ['--version']),
        ('clang', native_tools / 'clang', ['--version']),
        ('cmake', sdk / 'cmake/3.22.1/bin/cmake', ['--version']),
        ('ninja', sdk / 'cmake/3.22.1/bin/ninja', ['--version']),
        ('java', java_home / 'bin/java', ['-version']),
        ('javac', java_home / 'bin/javac', ['-version']),
    ):
        text = subprocess.check_output([tool, *version_args], stderr=subprocess.STDOUT, text=True)
        tools['versions'][label] = text.replace(str(ndk), '/opt/android-ndk').strip()
        tools['sha256'][label] = digest(tool)
    tools['sha256']['jdk_modules'] = digest(java_home / 'lib/modules')
    tools['sha256']['android_36_android_jar'] = digest(sdk / 'platforms/android-36/android.jar')
    tools['sha256']['ndk_NOTICE'] = digest(ndk / 'NOTICE')
    tools['sha256']['ndk_NOTICE.toolchain'] = digest(ndk / 'NOTICE.toolchain')
    (source / 'build-records/toolchain.json').write_text(json.dumps(tools, indent=2) + '\n')
    # Logs can contain host paths; publish only the normalized build/configuration records above.
    for path in source.rglob('*'):
        if path.is_file() and path.suffix not in ('.gz', '.xz') and (b'/' + b'Users' + b'/') in path.read_bytes():
            raise RuntimeError('Personal path in source package: ' + str(path.relative_to(source)))
    hashes = {p.relative_to(source).as_posix(): digest(p) for p in sorted(source.rglob('*')) if p.is_file()}
    (source / 'SHA256SUMS.json').write_text(json.dumps(hashes, indent=2) + '\n')
    target = output / 'media3-ffmpeg-1.11.0-b37bf81-corresponding-source-r2.tar.gz'
    deterministic_tar(source, target)
    manifest = {'source_bundle': {'filename': target.name, 'sha256': digest(target), 'bytes': target.stat().st_size},
                'relink_verified': True, 'gradle_verification_sha256': digest(RECIPE / 'verification-metadata.xml'), 'pins': PINS, 'aar_sha256': digest(aar)}
    (output / 'distribution-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
