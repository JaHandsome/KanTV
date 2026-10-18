# 可追溯的 Media3 FFmpeg 隔离构建

本配方构建来源明确的 FFmpeg 库，KanTV 默认依赖已接入其中完成验证的固定 AAR。`app/libs/media3-ffmpeg-provenance.properties` 绑定实际制品与对应源码包，Gradle 配置阶段会校验 AAR 的 SHA256，缺失或不匹配时构建失败。配方重建只输出到指定目录，不自动覆盖仓库制品；接入默认构建不改变家庭配置、稳定基线、NAS 或更新入口。通过双构建与合成音频检查仍不代表 5X 真实线路的持续播放与音画同步验收。

KanTV 源码与 [r2 构建材料](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018) 已公开，可匿名下载。普通客户端构建按 [构建说明](../../docs/CONFIGURATION.md) 使用仓库内的 AAR，无需运行本配方。需要审查或复现库时，再下载 r2 完整对应源码；下载入口与校验步骤见 [r2 材料核验](../../docs/CONFIGURATION.md#ffmpeg-r2-材料核验)。当前不提供 APK。

## 固定输入与来源

精确地址、提交、源码包 SHA256、工具版本、ABI 与 decoder 清单见 `pins.json`。

- Media3 1.11.0 官方 tag 对应提交 `2bc207851df311340767e913931ca7b28cab1794`。从官方 GitHub 项目下载按完整提交固定的归档。
- FFmpeg 固定到官方 GitHub 镜像 6.0 维护线提交 `b37bf81a7a9782438e4fe06a249329e279694494`，与核实的上游 6.0 分支头一致。该值固定后不会跟随分支移动。Media3 1.11.0 README 推荐 6.0 线，本配方不跨大版本。
- 初始 SHA256 来自经 HTTPS 下载的官方归档，随后每次下载均按固定 hash 拒绝变化；未进行上游 GPG 签名验证。Media3 tag 本身没有签名，不将 hash 校验说成签名认证。
- Android library 包装采用 KanTV 已使用的 AGP 8.10.1 / Gradle 8.11.1，避免将上游整仓 AGP 升级引入播放器工程。Java 与 JNI 源码取自固定的官方源码包，没有从旧 AAR 反编译拼装。
- 随配方提供的 `gradle/wrapper/gradle-wrapper.jar` 与官方 Gradle v7.4.2 launcher 字节一致，负责下载并运行 `gradle-wrapper.properties` 指定的 Gradle 8.11.1 distribution；两者版本分别记录。7.4 / 7.4.1 使用相同 launcher hash，不据此唯一认定生成版本。launcher 的官方来源与 SHA256、完整上游 `LICENSE` / `NOTICE` 位于 `third_party/gradle-wrapper/`。每次打包先校验 launcher 和两份许可文本的 hash，不匹配则在创建源码打包目录前失败；更换 launcher 必须先核对并更新该来源记录与许可。
- NDK r28c `28.2.13676358`，CMake `3.22.1`，JDK 17。当前配方限定 macOS 的 NDK `darwin-x86_64` 主机工具，支持 Apple Silicon 上使用该 NDK。其他宿主需要另行验证。
- FFmpeg 静态库 API 21；JNI 包装与 AAR manifest 最低 API 23 与 Media3 1.11.0 上游一致。五个 decoder 为 AAC、AC3、EAC3、MP2、MP3；四 ABI 为 armeabi-v7a、arm64-v8a、x86、x86_64。

官方依据：

- https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg/README.md
- https://github.com/FFmpeg/FFmpeg/commit/b37bf81a7a9782438e4fe06a249329e279694494
- https://ffmpeg.org/legal.html

## 运行

先安装固定 NDK/CMake、Android SDK platform 36 和 JDK 17，将 `ANDROID_HOME` 与 `JAVA_HOME` 指向安装目录。需要网络下载固定源码及 Google Maven / Maven Central 中的固定依赖。

```sh
python3 tools/ffmpeg/build_reproducible.py --output .scratch/ffmpeg-repro-20261015
```

输出目录必须没有 `build-1`、`build-2` 和 `corresponding-source`，脚本拒绝复用旧源码构建树。两个构建均独立解压源码、configure、编译 FFmpeg、编译 JNI、编译 Java 和打包；关闭 Gradle build cache，共享已安装工具链和下载依赖缓存。二者原生二进制必须相同，外层 AAR 与内部 classes.jar 的条目顺序、时间、权限、压缩方式统一后逐字节比较。

`run-final.log` / `native.log` / `gradle.log` 为私有本地运行记录，可能包含本机路径，不发布。完整完成标记为 `COMPLETE`；`result.json` 表示 AAR 双构建结果，`distribution-manifest.json` 表示源码分发包信息，`relinked/result.json` 表示所有 JNI 库重链接校验结果。失败不会写 `COMPLETE`。

## 对上游的修改

`patches/0001-deterministic-build.patch` 只应用于 Media3 上游构建脚本，没有修改 FFmpeg 解码实现：

1. 将共用 configure 参数改成 Bash 数组，以正确保留包含空格的路径映射参数。
2. 禁用调试信息和宿主可选依赖自动探测，防止宿主安装环境改变输入或泄露路径；不改变指定 decoder、平台汇编策略或音频算法。
3. `-ffile-prefix-map` 将源码与 NDK 路径规范化。只将 `FFMPEG_CONFIGURATION` 的信息字符串中的实际目录替换成标准目录；实际编译参数仍使用真实目录。
4. 每次 configure 后保存该 ABI 的配置头与 make 配置，供许可证、decoder 与构建参数复核。归档记录中的本机目录也被规范化。

JNI/CMake 与 Java 源码保持上游原样。JNI 编译使用 `-fdebug-compilation-dir=/src/build`：AGP 会按实际目录生成不同 `.cxx` 短目录，单靠前缀映射仍使 DWARF 的编译目录不同，并影响保留在 strip 后库中的 GNU build-id；固定编译目录从输入根因消除差异，保留正常 build-id 和调试 object。隔离包装工程显式使用静态 libc++、官方 consumer ProGuard 规则和上游声明的 annotation / checker-qual 依赖。固定的 `verification-metadata.xml` 记录已核对构建所用插件及依赖校验值。每个新工程复制此文件，并以 `--dependency-verification strict` 构建，不在正常构建中自动接受新校验值。文件随对应源码包保存，其 SHA256 写入 `distribution-manifest.json`。更换依赖或工具版本须独立核对并更新该文件，不能通过重新生成校验文件绕过失败。

## 对应源码与重链接材料

成功后产生 `media3-ffmpeg-1.11.0-b37bf81-corresponding-source-r2.tar.gz`，包含：

- 完整的 Media3 与 FFmpeg 官方固定源码归档，保留各自原有许可文件；完整构建配方、patch、Gradle wrapper；
- 实际使用的 wrapper 源码、独立 Gradle 工程、每 ABI 配置、依赖校验材料、工具链版本与关键工具 hash；
- 四 ABI 的 `libavcodec.a`、`libavutil.a`、`libswresample.a`、JNI object、精确链接参数与可执行重链接脚本；
- FFmpeg LGPL 2.1 及上游许可说明、Media3 Apache 2.0 许可证、固定 NDK 的 `NOTICE` 与 `NOTICE.toolchain` 原件及其 hash、源包及文件 hash。
- `third_party/gradle-wrapper/` 原路径下的 launcher 7.4.2 完整 `LICENSE` / `NOTICE` 和 `SOURCE.json` 来源记录；解压后配方仍能在相同路径读取并校验它们。

`r2` 补齐随包 Gradle launcher 的许可与来源材料，并更新打包配方、本文和文件 hash 清单。已被 patch 修改的 `wrapper/src/main/jni/build_ffmpeg.sh` 在保留上游版权头的同时，新增醒目的 KanTV 修改告知；新增告知仅为注释，不改变构建逻辑。原始上游归档、native 配置、静态库、JNI object 和重链接材料字节保持首版原样；AAR 不变。首版源码包保留为历史原件，不能用 r2 覆盖或沿用首版包的 SHA256。

JNI 库静态链接 FFmpeg 的 `.a`。本次二进制构建配置不启用 GPL、version3 或 nonfree，运行库适用许可证以固定源码及有效配置为准。源包保留的完整官方 FFmpeg 源码归档仍包含 GPL 等未启用组件，各文件依各自原有许可证分发；不能将整个源码包概括为仅 LGPL 或 Apache 2.0。Media3 和 FFmpeg 的原始许可分别保留。对包含该库的 APK 分发，还须提供允许修改 FFmpeg 并重新组合应用所需的对应源码和安装/重打包材料，且不能限制相关修改调试所需的反向工程权利。本隔离 AAR 包不自动完成整个 KanTV APK 的分发合规工作。

在分发包解压目录内，运行下面命令可使用原始或已替换的 FFmpeg 静态库重链接所有 JNI 库：

```sh
python3 relink.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --output relinked
```

脚本会报告与原始 AAR 的 hash 比较。未修改的材料已实际执行此检查并要求四 ABI 都匹配；换成修改后的 FFmpeg 库时 hash 不同是预期结果，重链接脚本仍会产生新库。修改源码后可用配方重新编译 FFmpeg，再替换 `relink/<ABI>/*.a`。

要完全重建分发包里的 AAR，可以把 `source-archives` 中的归档复制到新输出目录的 `downloads`，再运行配方；Gradle wrapper 和 recipe 已一并提供。完整包只包含本隔离库的源材料，不包含家庭播放列表、凭据或旧家庭历史。

默认库的独立构建材料使用 release tag `ffmpeg-build-1.11.0-b37bf81-20261018`，下载地址和固定 SHA256 见 [来源清单](../../app/libs/media3-ffmpeg-provenance.properties)。该预发布包含 AAR、r2 完整对应源码包、provenance 和 `SHA256SUMS` 四个附件，不包含 APK。源码包内文档保留打包时的阶段记录；后续仓库文档整理不会重打包或覆盖已核验的 r2 材料。默认库与私有旧库的隔离对照方式见 [验证说明](VALIDATION.md)。
