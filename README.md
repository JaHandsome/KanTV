# KanTV

KanTV 是 Android TV 直播客户端，支持导入自己的 M3U/TXT 播放列表和浏览内置官网频道。可选服务端位于 `server/`，提供列表生成、线路健康检查与 Hub 接口；客户端和服务端在同一仓库维护。

**源码与已核验的 FFmpeg r2 构建材料已公开，当前不提供预编译 APK。** 请按下面的步骤自行构建。仓库不附带私人播放源、订阅凭据或家庭服务器配对；只使用客户端无需部署服务端。

## 快速开始：构建客户端

准备 Git、**JDK 17**、Android SDK Platform **36** 和 Build-Tools **35.0.0**。可以通过 Android Studio 的 SDK Manager 安装 SDK，并把项目的 Gradle JDK 设为 17；命令行构建需配置自己的 `JAVA_HOME` 和 `ANDROID_HOME`。

```sh
git clone https://github.com/JaHandsome/KanTV.git
cd KanTV
./gradlew :app:assembleDebug
```

以上命令面向 macOS / Linux；仓库当前不提供 Windows 原生 `gradlew.bat` 脚本。首次构建需要联网下载固定的 Gradle 和 Maven 依赖。

输出为 `app/build/outputs/apk/debug/app-debug.apk`，默认面向 **arm64-v8a**，最低 Android **8.0 / API 26**。32 位 ARM 设备改用：

```sh
./gradlew :app:assembleDebug -Pkantv.abi=armeabi-v7a
```

两种构建使用同一输出路径；需要保留两个包时，先保存前一个产物。将开发包安装到自己的测试设备后，可浏览官网目录，或在设置中导入自己的 M3U/TXT。ABI 选择、SDK 安装、签名与常见问题见 [构建与实例配置](docs/CONFIGURATION.md)。

## 可选：部署自己的列表服务

需要集中维护播放源时，准备 Python 3.11 或更新版本、Docker Engine 和 Docker Compose v2 或更新版本，在仓库根目录执行：

```sh
python3 server/tools/init_config.py --output server/runtime/config
cd server
docker compose config --quiet
docker compose up -d --build
curl -fsS http://127.0.0.1:8420/healthz
```

容器启动后再检查健康接口；若尚未就绪，用 `docker compose ps` 和 `docker compose logs --tail=100 iptv-nas` 查看状态。若 Docker Hub 基础镜像下载失败，见 [官方镜像备用路径](server/deploy/README.md#基础镜像下载失败)。初始配置没有来源和目标频道，空播放列表是正常结果。按 [服务端部署说明](server/deploy/README.md#添加自己有权使用的来源) 先配置目标频道，再添加自己有权使用的来源，并显式配置局域网访问。随后在客户端导入 `http://你的服务地址:8420/live.txt` 即可使用；Hub 配对属于进阶选项。默认 Compose 只向宿主机回环地址映射端口，不适合直接映射公网。

## FFmpeg r2 下载与来源

普通客户端构建直接使用仓库已附带的 AAR，无需先重编 FFmpeg。需要核对来源、重建或修改库时，使用 [r2 构建材料页面](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018)：

| 材料 | 下载与说明 |
| --- | --- |
| AAR | [固定版本 FFmpeg 库](https://github.com/JaHandsome/KanTV/releases/download/ffmpeg-build-1.11.0-b37bf81-20261018/media3-decoder-ffmpeg-1.11.0-ffmpeg6.0-b37bf81-repro.aar)，与 `app/libs/` 中默认制品相同 |
| 完整对应源码 | [r2 源码与重链接材料](https://github.com/JaHandsome/KanTV/releases/download/ffmpeg-build-1.11.0-b37bf81-20261018/media3-ffmpeg-1.11.0-b37bf81-corresponding-source-r2.tar.gz)，约 499 MB，含固定源码、补丁、许可、工具链记录与四 ABI 重链接输入 |
| 来源与校验 | [provenance](https://github.com/JaHandsome/KanTV/releases/download/ffmpeg-build-1.11.0-b37bf81-20261018/media3-ffmpeg-provenance.properties) · [SHA256SUMS](https://github.com/JaHandsome/KanTV/releases/download/ffmpeg-build-1.11.0-b37bf81-20261018/SHA256SUMS) |

这些附件是库的构建材料，不能作为应用安装包使用。Gradle 会校验默认 AAR 的 SHA256；详细复现方法见 [FFmpeg 构建说明](tools/ffmpeg/README.md)，制品与验证记录见 [默认 FFmpeg 记录](docs/FFMPEG_DEFAULT_20261015.md)。

## 开发与验证

在仓库根目录运行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3.12 server/tests/run_offline.py
node tools/test_gecko_audio_observer.cjs
node tools/test_gecko_frame_observer.cjs
node tools/test_gecko_startup_resource.cjs
python3 tools/check_public_tree.py
python3 tools/check_public_history.py
```

服务端没有 pip 依赖；离线回归按 CI 使用 Python 3.12，TLS 回归需本机 `openssl`，Gecko 资源检查需 Node.js。Android 中依赖真实 Hub 载荷或外部 EPG 快照的检查需要另行提供输入。更多验证范围见 [构建说明](docs/CONFIGURATION.md#验证与故障排查)。

## 项目结构与使用边界

| 目录 | 内容 |
| --- | --- |
| `app/` | Android TV 客户端、变体和测试 |
| `server/` | 可选的 Python 自托管列表与 Hub 服务 |
| `tools/` | 构建、材料复现与受控发布工具 |
| `docs/` | 配置、接口契约和验证记录 |
| `third_party/` | 随仓库保留的第三方许可与来源记录 |

当前开发树的播放候选仍待真实设备持续播放验收，构建或模拟器通过不代表音画同步通过。[播放稳定性约定](docs/PLAYBACK_STABILITY.md) 记录 v53 的验收边界；v53 原件、旧历史、家庭配置与签名资料继续私有保留。公开源码与库材料不会自动更新已安装的家庭应用或稳定入口。

请使用自己有权访问的播放源。真实播放 URL、请求头、令牌、数据库和现场日志不进入 Git；服务端只部署在自己的受信网络中。Hub、更新与诊断端点默认留空，按需配置自己的实例，详见 [实例配置](docs/CONFIGURATION.md#实例配对与签名)。

## 许可

项目自身代码采用 [MIT 许可证](LICENSE)。第三方组件保留各自许可和源码提供义务，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
