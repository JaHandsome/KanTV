#!/usr/bin/env python3
"""Build the pinned Media3 FFmpeg extension in two independent source trees."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

RECIPE = Path(__file__).resolve().parent
PINS = json.loads((RECIPE / 'pins.json').read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args, cwd, env, log):
    print('RUN', ' '.join(map(str, args)), flush=True)
    with log.open('a') as output:
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'Exit {result.returncode}; inspect {log}')


def unzip_normalized(data):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as src, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as dst:
        for name in sorted(src.namelist()):
            if name.endswith('/'):
                continue
            value = src.read(name)
            if name.endswith('.jar'):
                value = unzip_normalized(value)
            entry = zipfile.ZipInfo(name, (2023, 11, 14, 22, 13, 20))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            dst.writestr(entry, value)
    return out.getvalue()


def extract(archive, destination, prefix):
    with tarfile.open(archive) as src:
        members = [m for m in src.getmembers() if m.name == prefix or m.name.startswith(prefix + '/')]
        if any('..' in Path(m.name).parts  for m in members):
            raise RuntimeError('Unexpected archive member')
        src.extractall(destination, members=[m for m in members if m.isfile() or m.isdir()])
    return destination / prefix


def build(work, sdk, ndk, java_home, downloads, gradlew):
    work.mkdir()
    env = dict(os.environ, JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
               SOURCE_DATE_EPOCH=str(PINS['source_date_epoch']), TZ='UTC', LC_ALL='C', ZERO_AR_DATE='1')
    # No inherited project credentials or endpoint configuration is used by this library project.
    env = {k: v for k, v in env.items() if not k.startswith('KANTV_')}
    media = extract(downloads / PINS['media3']['archive'], work, 'media-' + PINS['media3']['commit'])
    module = work / 'module'
    shutil.copytree(media / 'libraries/decoder_ffmpeg', module)
    ffmpeg = extract(downloads / PINS['ffmpeg']['archive'], module / 'src/main/jni', 'FFmpeg-' + PINS['ffmpeg']['commit'])
    ffmpeg.rename(module / 'src/main/jni/ffmpeg')
    command(['patch', '-p1', '-i', RECIPE / 'patches/0001-deterministic-build.patch'], module, env, work / 'native.log')
    command(['bash', module / 'src/main/jni/build_ffmpeg.sh', module / 'src/main', ndk,
             'darwin-x86_64', str(PINS['android_api']), *PINS['decoders']], module, env, work / 'native.log')
    license_dir = module / 'src/main/assets/ffmpeg-licenses'
    license_dir.mkdir(parents=True)
    for filename in ('COPYING.LGPLv2.1', 'LICENSE.md'):
        shutil.copyfile(module / 'src/main/jni/ffmpeg' / filename, license_dir / filename)
    shutil.copyfile(media / 'LICENSE', license_dir / 'Media3-APACHE-2.0.txt')
    shutil.copyfile(RECIPE / 'pins.json', license_dir / 'sources.json')
    (module / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
 dependencyResolutionManagement { repositories { google(); mavenCentral() } }
 rootProject.name = "media3-decoder-ffmpeg-reproducible"
''')
    prefix_maps = f'-fdebug-compilation-dir=/src/build -ffile-prefix-map={work}=/src/build -ffile-prefix-map={ndk}=/opt/android-ndk'
    (module / 'build.gradle.kts').write_text('''plugins { id("com.android.library") version "8.10.1" }
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 23
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild.cmake {
            arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            cFlags += listOf("PREFIX_MAPS")
            cppFlags += listOf("PREFIX_MAPS")
        }
        consumerProguardFiles("proguard-rules.txt")
    }
    externalNativeBuild.cmake { path = file("src/main/jni/CMakeLists.txt"); version = "3.22.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
dependencies {
    api("androidx.media3:media3-decoder:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.annotation:annotation:1.6.0")
    compileOnly("org.checkerframework:checker-qual:3.13.0")
}
tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
'''.replace('PREFIX_MAPS', prefix_maps))
    (module / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.caching=false\norg.gradle.java.installations.auto-download=false\n')
    (module / 'gradle').mkdir(exist_ok=True)
    shutil.copyfile(RECIPE / 'verification-metadata.xml', module / 'gradle/verification-metadata.xml')
    # Source paths are canonicalized in the binaries. Metadata/logs stay private in work.
    command([gradlew, '-p', module, '--no-daemon', '--no-build-cache', '--console=plain',
             '--dependency-verification', 'strict', 'assembleRelease', 'dependencies', '--configuration', 'releaseRuntimeClasspath'], module, env, work / 'gradle.log')
    raw = module / 'build/outputs/aar/module-release.aar'
    if not raw.exists():
        raw = next((module / 'build/outputs/aar').glob('*release.aar'))
    aar = work / 'media3-decoder-ffmpeg-1.11.0-ffmpeg6.0-b37bf81-repro.aar'
    aar.write_bytes(unzip_normalized(raw.read_bytes()))
    # Reject accidental personal build paths in any AAR entry, including nested Java archives.
    with zipfile.ZipFile(aar) as archive:
        for name in archive.namelist():
            if (b'/' + b'Users' + b'/') in archive.read(name):
                raise RuntimeError(f'Personal path leaked in {name}')
    return aar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--gradlew', type=Path, default=RECIPE.parent.parent / 'gradlew')
    args = parser.parse_args()
    if not args.sdk or not args.java_home:
        parser.error('Set ANDROID_HOME and JAVA_HOME or supply --sdk and --java-home')
    output, sdk, java_home = args.output.resolve(), args.sdk.resolve(), args.java_home.resolve()
    output.mkdir(parents=True, exist_ok=True)
    ndk = sdk / 'ndk' / PINS['ndk']
    if not (ndk / 'source.properties').exists() or not (java_home / 'bin/javac').exists():
        raise RuntimeError('Pinned NDK and JDK are required')
    downloads = output / 'downloads'
    downloads.mkdir(exist_ok=True)
    for source in (PINS['media3'], PINS['ffmpeg']):
        target = downloads / source['archive']
        if not target.exists():
            with urllib.request.urlopen(source['url']) as response, target.open('wb') as dest:
                shutil.copyfileobj(response, dest)
        if sha(target) != source['sha256']:
            raise RuntimeError('Source archive hash mismatch: ' + source['archive'])
    outputs = [build(output / ('build-' + str(i)), sdk, ndk, java_home, downloads, args.gradlew.resolve()) for i in (1, 2)]
    result = {'builds_completed': True, 'byte_identical': outputs[0].read_bytes() == outputs[1].read_bytes(),
              'artifacts': [{'path': str(p.relative_to(output)), 'sha256': sha(p), 'bytes': p.stat().st_size} for p in outputs]}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if not result['byte_identical']:
        raise RuntimeError('Independent builds differ; result is not reproducible')
    command(['python3', RECIPE / 'package_sources.py', '--output', output, '--sdk', sdk, '--java-home', java_home], output, dict(os.environ), output / 'package.log')
    (output / 'COMPLETE').write_text('Independent builds are byte-identical; corresponding source/relink package is ready.\n')


if __name__ == '__main__':
    main()
