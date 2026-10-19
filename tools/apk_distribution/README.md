# APK 许可与重组合材料

本目录补齐公开 APK 的运行时告知、Gecko 完整源码入口和 FFmpeg 重组合工具。它不上传 APK、不安装应用、不读取正式签名材料，也不改变播放实现或依赖版本。

`third_party/apk-runtime/runtime-components.json` 基于实际 `releaseRuntimeClasspath`，记录 **103 个 Maven 制品和 1 个本地 FFmpeg AAR**，含精确版本、SHA256、POM 声明及原始告知的来源条目。Gradle 冲突解决后的版本才是清单依据，例如 core 解析为 1.17.0、Kotlin stdlib 为 2.2.21；不能用 build.gradle 中的直接声明替代。清单覆盖全部解析输入，不声称 R8 最终保留每个类。插件、KSP 和测试依赖不属于 APK runtime 范围。

## 重新核对

使用项目要求的 JDK 17 和正常 Gradle 缓存。以下 JSON 含本机文件路径，只保存在忽略目录，不分发：

```sh
mkdir -p .scratch/apk-material-check
./gradlew -I tools/apk_distribution/runtime_inventory.gradle :app:apkRuntimeInventory \
  -Dkantv.inventory.output="$PWD/.scratch/apk-material-check/runtime-local.json"
python3 tools/apk_distribution/prepare_notices.py \
  --inventory .scratch/apk-material-check/runtime-local.json --verify-inventory
python3 -m unittest discover -s tools/apk_distribution -p 'test_*.py'
```

轻量校验不需要安装 NDK，不联网；它逐项核对坐标、制品 hash、POM 原件、APK 内索引、完整告知集合、原文 hash 和补充许可原文。依赖变化时失败，须重新生成并审核，不能直接自动接受新清单。

需要重新生成时使用固定 NDK r28c `28.2.13676358`：

```sh
python3 tools/apk_distribution/prepare_notices.py \
  --inventory .scratch/apk-material-check/runtime-local.json \
  --ndk "$ANDROID_HOME/ndk/28.2.13676358" \
  --output .scratch/apk-material-check/generated --check
```

`--output` 必须是新目录。去掉 `--check` 仅生成候选材料，不覆盖已审材料；审核后将生成目录复制到 `third_party/apk-runtime/`，将其中两个 runtime 索引与 `notices/` 分别复制到 `app/src/main/assets/licenses/` 和其 `runtime-notices/`。完整 POM 原件供复查并继承父 POM 的许可，例如 Guava；APK 内用 JSON 索引绑定该证据。新增/未知许可、Gecko revision 漂移或未审核的本地依赖会失败。

`third_party/apk-supplemental.json` 绑定原始 POM/制品没有带全的补充许可原文。DataStore repackaged protobuf 的 `RuntimeVersion.class` 常量明确为 4.28.2，BSD 全文取官方 protobuf v28.2 LICENSE。Google 四个 Play services 制品的 POM 声明为 Android SDK License，保留其 Google SDK 原文及 AAR 自带 third_party_licenses，不称它们为 Apache。Google 源文含适用第三方许可的例外；应用自身 MIT 不附加禁止 LGPL 修改调试的条款。此为材料及条款对应核查，不作一般法律保证，也不单独再分发 Google AAR。

## Gecko 完整源码

`geckoview` 与 `geckoview-exoplayer2` 的精确版本为 `146.0.20251217121356`，POM `<scm><tag>` 与 AAR 内 `assets/omni.ja!/chrome/toolkit/content/global/buildconfig.html` 一致锁定 `86bb7f6af6312ba3c0161085f854bcdff68f1a91`。Mozilla 官方 `FIREFOX_146_0_1_RELEASE` tag 指向同一提交。**这里应取 Firefox 146.0.1 完整源包，不是按 Maven 主版本猜测 Firefox 146.0，也不是仅取 sources.jar。**

```sh
curl --fail --location --output firefox-146.0.1.source.tar.xz \
  https://archive.mozilla.org/pub/firefox/releases/146.0.1/source/firefox-146.0.1.source.tar.xz
python3 -c 'import hashlib; from pathlib import Path; assert hashlib.file_digest(Path("firefox-146.0.1.source.tar.xz").open("rb"), "sha256").hexdigest() == "e9678a0e8473923953e1dc312c37919068623b6aa20adade16266049258191eb"'
```

本次已实际下载完整 648,938,528 字节官方源包并核对 SHA256，来源记录见 `third_party/geckoview-source.json`。该校验值来自 Mozilla 官方 [SHA256SUMS](https://archive.mozilla.org/pub/firefox/releases/146.0.1/SHA256SUMS)，不是签名验证；[提交与 release tag](https://hg.mozilla.org/releases/mozilla-release/json-rev/FIREFOX_146_0_1_RELEASE) 可交叉核对。未修改 GeckoView；完整 MPL 原文及 Gecko 随包原始 `license.html` 都在 APK assets 内。hg 的按提交 archive 链接本次返回 403，不将其作为可用下载入口。每次正式 APK 发出前仍应重验完整源包可取性与 hash；推荐在同一发行材料中保留该官方原包副本，以免只依赖第三方服务可用性。

## FFmpeg r2 与可执行 APK 重组合

r2 的固定 hash、下载入口及四 ABI 重链接操作见 [`../ffmpeg/README.md`](../ffmpeg/README.md)。r2 足以重链接本库，但它不包含整个应用的源码或 APK 重打包工具。**发出 APK 时必须同时提供该 APK 的精确 KanTV 源码快照、本目录、r2 完整包及 hash、APK 自身 hash 和构建说明。** 不用可移动的 `main` 分支代替发行快照，不将旧家庭包用于公开材料。

1. 解压 r2，修改 FFmpeg 源码，按其中固定配方重编译；用新的同 ABI 静态库替换 `relink/<ABI>/*.a`。保留必要的链接约定，必要时同时修改 wrapper 源码后重建 JNI。具体算法/API 变更的兼容性由修改者验证。
2. 在 r2 解压目录运行：

   ```sh
   python3 relink.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --output relinked
   ```

   未修改材料应四 ABI `matches_original=true`；有意修改后 hash 不同正常。脚本产出 `relinked/<ABI>/libffmpegJNI.so`。
3. 对希望修改的同一发行 APK 运行，原包保持不动：

   ```sh
   python3 tools/apk_distribution/recombine_apk.py \
     --apk kantv-original.apk --relinked /path/to/relinked \
     --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
     --output kantv-modified-unsigned.apk
   ```

   工具拒绝覆盖输出、重复 ZIP 条目、缺失 JNI、错误 ELF ABI。它替换包内每个 ABI 的 JNI，删除失效的 v1 签名；ZIP 重写自然移除旧 v2/v3 签名块，保留其他条目，执行 `zipalign -P 16`，并逐条核对所有未改内容。JSON 旁车记录原包和替换库 hash；该输出仍未签名。
4. 用自己已有的测试密钥签名。没有密钥时先创建自己的密钥；以下工具交互询问密码，不把密码写入命令或脚本：

   ```sh
   keytool -genkeypair -keystore kantv-user.keystore -alias kantv-user \
     -keyalg RSA -keysize 3072 -validity 3650
   "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
     --ks kantv-user.keystore --out kantv-modified.apk kantv-modified-unsigned.apk
   "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose kantv-modified.apk
   ```

5. Android 不允许不同签名覆盖既有安装。同一设备的多个 Android 用户共享应用包身份，因此切换用户不能规避签名冲突。使用整台设备尚未安装同包名应用的独立测试设备或全新独立模拟器，在那里运行 `adb install kantv-modified.apk`；不要清空或卸载家庭稳定应用来强行安装。此流程不需要原发布者的私钥；在公开发行材料中不得限制适用 LGPL 条款允许的修改调试。应用源代码路线也可重新构建，但不得绕过 Gradle 的 AAR provenance 门禁来将未知库冒称官方制品。

## 本次验证边界

2026-10-19：r2 原包 SHA256 匹配来源清单；从 r2 解压运行重链接，四 ABI 全部匹配原 AAR。用旧本地 unsigned APK 演练重组合，JNI 替换、16KB 对齐和其余条目逐字节校验通过。此仅为工具验证，发行候选仍需使用新构建的无家庭注入 APK 重跑。原始材料、日志、hash 和完成标记位于忽略目录 `.scratch/apk-release-prep-20261019/materials/`。本次没有公开 APK、没有安装设备或切家庭入口；重组合通过也不代表持续播放、音画同步或设备验收。
