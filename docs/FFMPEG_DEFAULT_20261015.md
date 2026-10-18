# 默认 FFmpeg 接入记录

当前状态（2026-10-18）：[KanTV 源码](https://github.com/JaHandsome/KanTV)和 [FFmpeg r2 构建材料](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018)已公开，可匿名获取；不提供 APK。旧历史、v53 原件和家庭配置继续私有保留，家庭稳定入口未切换。新用户请先看 [构建与实例配置](CONFIGURATION.md)。

以下记录保留 2026-10-15 接入和公开前复核的过程。其“私有阶段”等描述是当时状态，不代表当前仓库可见性。本次接入将已经独立双构建验证的新 AAR 接入 KanTV 默认构建，并准备只含通用源码和可追溯二进制的 Git 历史；不执行 NAS 发布或家庭应用安装。

## 2026-10-18 许可复核补正

公开前最终复核发现并补齐 Gradle Wrapper launcher 的完整 LICENSE/NOTICE、实际来源版本，以及被修改的上游构建脚本内的醒目修改告知。r2 是新文件和新材料标签，原始源码包和旧标签保留；旧 release 转为草稿私有留档，并附补充许可和 r2 指引，避免后来公开仓库时仍公开分发旧归档中的已知遗漏。AAR、Wrapper JAR 和所有原生库不变。当前源码包下载地址与 hash 已在来源清单、应用告知和合约测试中同步。

Gradle Wrapper JAR 字节与官方 v7.4.2 一致，7.4/7.4.1/7.4.2 共用该 hash；它负责启动 Gradle 8.11.1 distribution，不能把两者的版本混称。

## 制品与对应材料

默认文件为 `app/libs/media3-decoder-ffmpeg-1.11.0-ffmpeg6.0-b37bf81-repro.aar`，SHA256 为 `82024db102de0681cda9d0816c2d4edf660a2b15a2434d4bda00ba6a223a39b5`。Gradle 配置阶段读取 [来源清单](../app/libs/media3-ffmpeg-provenance.properties)，校验实际依赖文件；缺失或 hash 不同即失败。

当前 r2 完整对应源码包 SHA256 为 `4b9b0e60597a82048b51567dac72967fa3dff356c7587bd10f03f935f61f8596`，包含固定源码、补丁、构建工具链与许可证记录、静态库、JNI object 和四 ABI 重链接材料。[构建材料 release](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018) 的地址固定在来源清单中，现已支持匿名下载。该材料 release 不包含 APK。

固定 Media3 提交 `2bc207851df311340767e913931ca7b28cab1794`，FFmpeg 提交 `b37bf81a7a9782438e4fe06a249329e279694494`。四 ABI 与 AAC、AC3、EAC3、MP2、MP3 保持现有范围，不启用 GPL、version3 或 nonfree。此前双目录构建和原生重链接结果见 [独立候选记录](FFMPEG_REPRODUCIBLE_CANDIDATE_20261015.md)。

## 本地验证

- 默认依赖、无家庭素材的隔离副本：856 项单测，0 失败、0 错误、3 跳过；APK 构建通过；Lint 0 Error、0 Fatal、81 Warning。
- 来源合约检查固定制品、对应源码、四 ABI JNI hash、五 decoder 和必要 consumer 规则。
- 篡改同名 AAR 的构建负例被配置阶段拒绝；默认隔离测试显式拒绝 AAR override。
- 默认 APK 的 arm64 JNI SHA256 为 `d1f6939ad11807b678acda7020a235e159a29b0a6da1d635c08757c0fed625e5`，匹配新 AAR。
- 历史守卫覆盖旧路径曾存在后被删除、旧内容改名、浅克隆、graft 隐藏及 author/committer 个人邮箱进入祖先历史；公开树与历史守卫共 10 项合成回归通过。CI 使用完整历史，默认不采用导出过滤。

低配 arm64/API 34 模拟器使用单核、640×360、软件渲染、只读和无快照；请求 512MB，模拟器自动提高到最低 1024MB。同一启动周期内完成旧 control Debug、默认 Debug、默认 R8 release，三阶段均通过五 codec 解码到 EOS、flush 后重解码、实际 FFmpeg AAC→MP3 播放列表和暂停恢复。五 codec 各阶段 PCM hash 与旧 control 对应阶段一致，默认两包实际 JNI hash 均匹配新库，且没有 AAR override。测试仅使用隔离应用 ID，不安装到家庭应用，结束后关闭自有模拟器。

R8 测试把有限测试入口编入目标 APK，仅保留 Instrumentation 入口，不追加 FFmpeg/Media3/Kotlin 的整体 keep。该测试包不等于未加测试入口的正式发行包。模拟器无音频输出，不能证明真实直播或可听音画同步；5X 同频道、同线路的 v53 对照、完整故障窗口、音频输出和实际播放仍待验收。完成前不得替换家庭稳定更新入口。

## 远端与材料验证

- 2026-10-15 的独立私有筹备仓库曾以 `008aa6a40e50d5bf850f06fce8e4076a9ee2d29b` 为根。2026-10-18 复核发现后续提交继承了个人邮箱，因此将该筹备对象图整体转入私有归档，并以完整许可、仅 GitHub noreply 身份的最终快照重新建立拟公开历史，不继承旧对象图。最终仓库须通过所有 refs 的旧 AAR 与提交邮箱门禁，并确认旧对象无法从新仓库取回。
- [构建材料预发布](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018) 已上传 AAR、498,900,455 字节的完整对应源码包、来源清单和 SHA256SUMS。四个附件均实际重新下载并逐字节 SHA256 核对通过。r2 外层归档包含 78 个文件；原生库、object、源码归档等构建材料保留原字节，变动仅为许可、来源与修改告知。完整嵌套归档的敏感扫描已另行核查。
- 此 release 为非草稿的 prerelease，不含 APK，不设为 latest；仓库 latest release 查询为 404。此项准备验证时仓库仍为 private，后续已按用户授权公开通用源码与 r2，旧对象图仅私有保留。
- v53 两个原始签名 APK、源码归档、归档清单、完成标记及四份基线记录复核未变化。未执行 NAS 写入或家庭稳定入口切换。

CI 结果另见与当前提交关联的 GitHub Actions；完整本地命令、退出状态与合成运行证据保留在本机忽略目录，不上传现场或家庭资料。

## 历史与私有资料边界

旧 FFmpeg AAR 原始来源无法确认，因此从拟公开仓库全部历史中排除。旧历史、既有 PR、v53 与私有配置仍在本机备份或私有归档仓库中保留；含个人邮箱的筹备历史也仅私有保留。清理针对新的独立仓库对象图，不宣称从 GitHub 全局删除已归档对象。后续只推送明确审核过的目标分支或材料标签，不推送本机全部引用。

项目自身代码采用 MIT；第三方许可各自保留。本次不发布 APK，不将库源码包视为整个 APK 的分发材料或真实播放验收证明。
