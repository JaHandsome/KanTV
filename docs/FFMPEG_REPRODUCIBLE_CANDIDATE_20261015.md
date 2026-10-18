# FFmpeg 可追溯候选记录

本页保留首次独立候选的验证事实。原始源码包中的 Wrapper 告知和修改文件告知遗漏已由 2026-10-18 的 r2 材料补正；当前材料以来源清单为准，旧 hash 仅作历史记录。后续默认构建接入与历史清理见 [默认 FFmpeg 记录](FFMPEG_DEFAULT_20261015.md)；下文“默认依赖不变”和“尚未上传”描述当时阶段。

日期：2026-10-15。范围：建立来源明确、可重复构建的独立库并隔离验证。未覆盖历史 AAR、v53 APK/源码归档/验收记录、家庭配置或稳定入口；未切公开，未执行 NAS 部署。当前 app 默认依赖不变。

## 固定来源

| 输入 | 固定标识 |
| --- | --- |
| Media3 1.11.0 | `2bc207851df311340767e913931ca7b28cab1794` |
| FFmpeg 6.0 线 | `b37bf81a7a9782438e4fe06a249329e279694494` |
| NDK / CMake | `28.2.13676358` / `3.22.1` |
| AGP / Gradle | `8.10.1` / `8.11.1` |
| 实际验证的 JDK | 17.0.15 |
| ABI | armeabi-v7a、arm64-v8a、x86、x86_64 |
| Decoder | AAC、AC3、EAC3、MP2、MP3 |

选择同一 FFmpeg 6.0 线遵循 [Media3 1.11.0 官方说明](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg/README.md)。官方源码地址、归档 SHA256 和构建参数见 [pins.json](../tools/ffmpeg/pins.json)。构建拒绝源码 hash 变化，也使用固定的 Gradle verification metadata 严格校验插件和依赖；篡改预期 AGP hash 的独立负例被拒绝，没有修改全局缓存。

## 产物与对应材料

| 产物 | 字节数 | SHA256 |
| --- | ---: | --- |
| `media3-decoder-ffmpeg-1.11.0-ffmpeg6.0-b37bf81-repro.aar` | 2,104,878 | `82024db102de0681cda9d0816c2d4edf660a2b15a2434d4bda00ba6a223a39b5` |
| `media3-ffmpeg-1.11.0-b37bf81-corresponding-source.tar.gz` | 498,891,519 | `aa119a8c9932f2764f81578267dae604774a80e2249c546bf68800d74bd4f06f` |

制品本轮保存在本机忽略的 `.scratch/ffmpeg-repro-20261015/`，尚未作为发布制品上传。源码包内含两个完整官方源码归档、实际 Java/JNI 包装源码、构建配方、明确补丁、许可证、固定 NDK 的 NOTICE/NOTICE.toolchain 原件与 hash、各 ABI 有效配置、固定依赖校验、工具链版本/hash、静态库、JNI object 与可执行重链接脚本。公开分发 AAR 前应与其对应源码包一同提供；APK 还需提供对应的应用源码和重新组合/安装材料，不能把本记录视为整个应用的分发合规结论。

## 构建验证

- 两个独立目录分别解压、配置并编译，关闭构建缓存；12 个 FFmpeg 静态库逐字节相同，最终两个 AAR 逐字节相同。
- 初次 JNI build-id 差异追溯到 AGP 构建目录进入 DWARF；固定编译目录后重新编译 JNI，从输入根因解决差异，未删除 build-id 来伪装一致。
- 用源码包中四 ABI 的 `.a`、JNI object 和链接命令实际重链接，四份 `.so` 均与 AAR 相同。
- 有效配置只有指定五个 decoder，GPL、version3、nonfree 关闭；许可证和 native consumer 规则保留。
- 源码分发材料的清单、归档与最终 AAR hash 相互对应；检查的库、Java 归档和分发记录未发现本机个人路径。

“可复现”限定于记录的 macOS/JDK/NDK/Gradle 工具链及两个独立目录，不表示跨任意宿主或工具版本都相同。未进行上游 GPG 签名验证，不把 HTTPS、完整提交与 hash 核对写成签名验证。

## 隔离代码验证

低配只读模拟器使用单核、请求 512MB（模拟器最低实际 1024MB）、640×360、软件渲染。旧库与候选的应用 ID 分别为 `com.kantv.app.ffmpegcontrol` 和 `com.kantv.app.ffmpegcandidate`，均关闭重诊断，未清空或安装到家庭应用。

旧库与候选的 debug 构建均完成：五种合成音频解码到 EOS、采样率/声道/长度/音调检查、flush 后再次解码、实际 FFmpeg AAC→MP3 播放列表、暂停恢复及正常结束。按相同轮次比较，五种样例的首次解码和 flush 后解码 PCM hash 均与旧库一致。APK 内 JNI 库 hash 也与候选 AAR 一致，证明测试没有误用旧库。

候选还通过了包含测试入口、使用测试签名的 R8 release 隔离包检查：五种解码、flush、FFmpeg AAC→MP3 播放列表、暂停恢复和 EOS 均通过，test APK 无重复 runner。测试代码随目标一起混淆，只保留 Instrumentation 入口；没有追加 FFmpeg/Media3/Kotlin 的整体 keep。它不是未加测试入口的正式 release 运行验收，也不能单独证明正式应用所有反射路径的可达性。重跑方法与测试边界见 [VALIDATION.md](../tools/ffmpeg/VALIDATION.md)。

这些结果不证明真实直播、完整故障窗口、多声道/透传、设备音频输出或可听音画同步。v53 仍是家庭稳定基线；候选进入稳定入口前必须完成 [真实设备对照与验收](PLAYBACK_STABILITY.md)。历史来源不明的二进制也不能因新候选通过而自动获得来源证明。
