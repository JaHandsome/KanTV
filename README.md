# KanTV

KanTV 是 Android TV 直播客户端，支持 M3U/TXT 导入和官网频道。可选的自托管服务位于 `server/`，提供播放列表、频道路由、健康检查与 Hub 接口；客户端和服务端在同一仓库维护。

## 开始使用

- **只使用客户端**：构建 APK 后可浏览内置官网目录，或在设置中导入自己的 M3U/TXT。公共源码不附带私人播放源、订阅凭据或默认 NAS 配对。
- **使用自托管服务**：按 [服务端部署说明](server/deploy/README.md) 初始化独立配置、启动服务，再将自己的端点注入客户端构建。默认端点为空，未配置时不连接任何预设家庭服务器。
- **构建与配置**：见 [配置说明](docs/CONFIGURATION.md)。正式构建关闭重诊断；更新与回流只有配置自己的端点和令牌后才启用。

## 开发与验证

使用 JDK 17 与 Android SDK，通过 Gradle wrapper 构建：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3.12 server/tests/run_offline.py
node tools/test_gecko_audio_observer.cjs
node tools/test_gecko_frame_observer.cjs
node tools/test_gecko_startup_resource.cjs
python3 tools/check_public_tree.py
python3 tools/check_public_history.py
```

服务端没有 pip 依赖；TLS 回归需要本机 `openssl`。Android 单测中的真实 Hub 载荷和外部 EPG 快照检查需自行提供输入，不作为离线数据。模拟器、构建与单测不能证明真实电视盒子上的播放和音画同步。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `app/` | Android TV 客户端、变体和测试 |
| `server/` | Python 自托管服务、公开配置元数据及离线测试 |
| `tools/` | 构建、候选发布与验证工具 |
| `docs/` | 配置、接口契约和开发说明 |

## 稳定版本与数据边界

当前开发树包含尚待真实设备验收的候选变更。[播放稳定性约定](docs/PLAYBACK_STABILITY.md) 记录 v53 的验收边界。历史家庭稳定包、签名材料、个人运维记录和私有配置单独保留；公共源码不包含这些资料，也不自动替换已安装的家庭版本或稳定更新入口。

默认 FFmpeg 库由固定的官方源码与工具链构建，构建时核验制品 SHA256。来源、完整对应源码与验证边界见 [默认 FFmpeg 记录](docs/FFMPEG_DEFAULT_20261015.md)；旧的来源未明二进制仅私有保留，不进入拟公开历史。

请使用自己有权访问的播放源。配置、播放请求头、真实签名 URL、令牌、数据库与现场日志均不进入 Git。此项目的 LAN 部署模式不等于适合直接暴露到公网。

## 许可

项目自身代码采用 [MIT 许可证](LICENSE)。第三方组件仍遵守各自许可，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
