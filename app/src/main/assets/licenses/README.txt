KanTV third-party runtime notices

KanTV itself is licensed under MIT. Third-party components retain their own
licenses. The project license does not relicense those components.

runtime-components.txt lists all resolved releaseRuntimeClasspath inputs;
runtime-components.json binds their exact artifact hashes, declared licenses,
POM evidence, and preserved archive notices. R8 may remove unused classes;
the list intentionally accounts for every resolved runtime input.

Apache-2.0: AndroidX-Media3-Apache-2.0.txt plus the original notices in
runtime-notices/. This includes Kotlin, coroutines, Guava, Okio, SnakeYAML,
Material, and the AndroidX components identified in the runtime index.
DataStore's repackaged protobuf 4.28.2: Protobuf-BSD-3-Clause.txt.
Google Play services base, basement, FIDO and tasks: Android-SDK-License.txt;
their original third_party_licenses.txt/json are preserved in runtime-notices/.
These four components are not represented as Apache-licensed libraries.

GeckoView 146.0.20251217121356 and geckoview-exoplayer2:
Mozilla-MPL-2.0.txt and the complete original Gecko license.html in
runtime-notices/ (the JSON index identifies its exact filename). Gecko embeds
additional third-party components under the terms in that original page.
No KanTV modifications were made to GeckoView. The complete MPL-covered source
is available free of charge from Mozilla, not just the Java sources JAR:
https://archive.mozilla.org/pub/firefox/releases/146.0.1/source/firefox-146.0.1.source.tar.xz
SHA256: e9678a0e8473923953e1dc312c37919068623b6aa20adade16266049258191eb
Official checksum list:
https://archive.mozilla.org/pub/firefox/releases/146.0.1/SHA256SUMS
Source revision: 86bb7f6af6312ba3c0161085f854bcdff68f1a91
Mozilla release tag FIREFOX_146_0_1_RELEASE resolves to that revision. It is also
recorded in the exact AAR's internal buildconfig.html and Maven POM.
Browse source at:
https://hg.mozilla.org/releases/mozilla-release/rev/86bb7f6af6312ba3c0161085f854bcdff68f1a91
MPL-covered source retains its MPL rights; other source files retain their own
license notices. The complete source archive includes third-party licenses.

FFmpeg audio decoding fallback: FFmpeg-LGPL-2.1.txt and
media3-ffmpeg-notice.txt identify fixed sources, configuration and the unchanged
r2 source/relink archive. FFmpeg is LGPL-2.1-or-later; the Media3 wrapper is
Apache-2.0. Static NDK libc++ and other applicable native/toolchain notices are
preserved in full in runtime-notices/NDK-r28c-NOTICE.txt and
runtime-notices/NDK-r28c-NOTICE.toolchain.txt.

You may modify FFmpeg and relink/recombine it with the application. KanTV does
not impose a restriction on reverse engineering needed to debug modifications
under the applicable LGPL terms. Obtain the exact APK's KanTV source archive,
the r2 corresponding-source archive, and tools/apk_distribution/ from the same
release materials. Instructions are in tools/apk_distribution/README.md.
Use your own signing key for a modified APK. Android will not allow it to update
an installation signed by a different key. Android users on the same device share
the package identity. Use a separate test device or fresh emulator where no user
has the same package installed; do not erase existing data or overwrite a
household stable installation.

All original third-party license notices and disclaimers remain applicable.
