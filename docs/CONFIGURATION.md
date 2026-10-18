# 构建与实例配置

KanTV 源码和 [FFmpeg r2 构建材料](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018) 已公开，目前没有预编译 APK。普通客户端构建直接使用仓库内已验证的 AAR；无需先下载约 499 MB 的源码材料或重编 FFmpeg。

## 构建环境

| 项目 | 当前要求 |
| --- | --- |
| JDK | 17；Android Studio 中选择 Gradle JDK 17，命令行使用自己的 `JAVA_HOME` |
| Android SDK | Platform 36、Build-Tools 35.0.0、Platform-Tools；命令行配置自己的 `ANDROID_HOME` |
| Gradle / Android 插件 | Wrapper 启动固定的 Gradle 8.11.1，AGP 8.10.1；使用仓库脚本，不用全局 Gradle 替代 |
| 目标设备 | Android 8.0 / API 26 或更新，ARM 64 位或 32 位 |
| 网络 | 首次构建需访问 Gradle distribution、Google Maven、Maven Central、Gradle Plugin Portal 与 Mozilla Maven |

Android Studio 可直接打开仓库目录并通过 SDK Manager 安装组件。使用命令行工具时，确认 `sdkmanager` 在自己的 PATH 中，执行：

```sh
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
java -version
```

按提示阅读并接受 SDK 许可。`java -version` 应为 17；Studio 的 Gradle JDK 与终端 JDK 是分别配置的。[AGP 8.10 兼容表](https://developer.android.com/build/releases/past-releases/agp-8-10-0-release-notes)列明 JDK 17、Gradle 8.11.1 和默认 Build-Tools 35.0.0，支持到 API 36。

`local.properties` 可以由 IDE 保存本机 SDK 路径，它已被忽略，不应提交。仅重建 FFmpeg 库时才需要按 [独立配方](../tools/ffmpeg/README.md) 准备固定 NDK/CMake 等工具。

## 首次构建与安装

在 macOS / Linux 终端中：

```sh
git clone https://github.com/JaHandsome/KanTV.git
cd KanTV
./gradlew --version
./gradlew :app:assembleDebug
```

`--version` 应显示 Gradle 8.11.1 和 JDK 17。当前仓库仅附带 Unix Wrapper 脚本，本文构建步骤面向 macOS / Linux；未提供 Windows 原生 `gradlew.bat` 及相应验证。

默认生成 `app/build/outputs/apk/debug/app-debug.apk`，使用本机开发签名。当前 ABI 选择：

| 目标 ABI | 命令 |
| --- | --- |
| `arm64-v8a`（默认） | `./gradlew :app:assembleDebug` |
| `armeabi-v7a` | `./gradlew :app:assembleDebug -Pkantv.abi=armeabi-v7a` |

两者均写入同一个 `app-debug.apk`，先保存要保留的产物再构建另一种 ABI。仅接受这两个单 ABI 选项；x86/x86_64 模拟器不在默认安装目标内。可在自己的测试设备上用 `adb shell getprop ro.product.cpu.abilist` 查看支持的 ABI。

将包安装到自己的空白测试设备或匹配 ABI 的模拟器，例如：

```sh
adb devices
adb -s YOUR_TEST_DEVICE_SERIAL install app/build/outputs/apk/debug/app-debug.apk
```

将 `YOUR_TEST_DEVICE_SERIAL` 替换为 `adb devices` 中自己的测试设备序列号。客户端包名为 `com.kantv.app`，开发签名与既有正式签名可能不同。已有正式安装遇到签名或降级冲突时，保留原应用与数据，换独立测试环境；不要通过卸载、清空数据或覆盖稳定入口来强行安装候选。

启动后可使用官网目录，或在设置中导入自己的 M3U/TXT。客户端默认没有 Hub、更新和诊断配对，不要求提供 NAS。若已部署自己的列表服务，可直接导入其 `http://你的服务地址:8420/live.txt`，无需为普通 M3U 订阅重新编译客户端。

## 实例配对与签名

| 环境变量 | 用途 |
| --- | --- |
| `KANTV_HUB_ENDPOINT` | 自己的 Hub 基地址；空值关闭 Hub 默认订阅与回流 |
| `KANTV_HUB_FEEDBACK_TOKEN` | 自己服务端的回流凭据；未配置时不上传回流 |
| `KANTV_UPDATE_ENDPOINT` / `KANTV_UPDATE_TOKEN` | 自己的更新清单与认证；两者配对后才启用 |
| `KANTV_DIAGNOSTIC_ENDPOINT` / `KANTV_DIAGNOSTIC_TOKEN` | 仅需诊断的构建使用；正式包重诊断始终关闭 |
| `KANTV_RELEASE_KEYSTORE_FILE` / `KANTV_RELEASE_KEYSTORE_PASSWORD` / `KANTV_RELEASE_KEY_ALIAS` / `KANTV_RELEASE_KEY_PASSWORD` | 自己的正式签名材料；需要签名时四项同时提供 |

端点由构建配置控制。没有有效构建端点的通用版本不会启用旧 Hub 偏好，也不删除旧偏好或用户已导入的 M3U 订阅。有效配对构建沿用自己的实例流程。不要把令牌、私钥或密码写入项目文件、命令历史、CI 日志或示例。

例如只注入非秘密端点：

```sh
KANTV_HUB_ENDPOINT=http://example.invalid:8420 ./gradlew :app:assembleDebug
```

这里使用不可达的示例域名；部署时替换为自己的 LAN 地址。令牌由凭据工具直接注入进程环境，不在终端输出。

Hub 需先在自己的服务端启用并配置，反馈凭据与普通列表订阅分开处理。更新与诊断上传也各自使用自己的配对；开发构建带诊断能力，正式 `release` 包始终保持 `KANTV_DIAGNOSTICS=false`。不要将嵌入 APK 的 token 当作公网访问控制的唯一边界。

需要自己的 release 包时，先按 [Android 应用签名说明](https://developer.android.com/studio/publish/app-signing) 准备并安全保管自己的签名身份，通过可信工具将四项签名变量交给构建进程，再运行 `./gradlew :app:assembleRelease`。未配置签名时生成的 release 产物未签名，不能直接安装；设置了部分签名字段时配置会被拒绝。构建 release 不会上传或发布 APK。

## FFmpeg r2 材料核验

来源清单为 [`app/libs/media3-ffmpeg-provenance.properties`](../app/libs/media3-ffmpeg-provenance.properties)。固定的当前值：

| 文件 | SHA256 |
| --- | --- |
| `media3-decoder-ffmpeg-1.11.0-ffmpeg6.0-b37bf81-repro.aar` | `82024db102de0681cda9d0816c2d4edf660a2b15a2434d4bda00ba6a223a39b5` |
| `media3-ffmpeg-1.11.0-b37bf81-corresponding-source-r2.tar.gz` | `4b9b0e60597a82048b51567dac72967fa3dff356c7587bd10f03f935f61f8596` |

从 [r2 release](https://github.com/JaHandsome/KanTV/releases/tag/ffmpeg-build-1.11.0-b37bf81-20261018) 将 `SHA256SUMS` 及其中列出的 AAR、源码包、provenance 三个文件下载到同一目录，然后核验：

```sh
# Linux
sha256sum -c SHA256SUMS
# macOS
shasum -a 256 -c SHA256SUMS
```

Windows 可用 `Get-FileHash -Algorithm SHA256 <文件路径>` 与清单逐项比较。AAR 已在 `app/libs/` 中；源码包仅在审查、复现或修改库时下载。Gradle 配置阶段会核验实际 AAR，缺失或 hash 不符时停止构建，不能用来源未明的旧库替代。

## 验证与故障排查

客户端常规验证在仓库根目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

只检查库身份可执行 `./gradlew :app:testDebugUnitTest --tests '*FfmpegBundleContractTest*'`。离线单测默认不提供真实 Hub 载荷与外部 EPG 快照；相关数据检查会跳过或需另行传入输入，不能把离线绿色当作服务端联调通过。

| 现象 | 检查方向 |
| --- | --- |
| `SDK location not found` / SDK 许可错误 | 检查自己的 `ANDROID_HOME` 或 IDE 生成的 `local.properties`，安装 SDK 并执行 `sdkmanager --licenses` |
| Java / Gradle 不兼容 | 检查终端和 Studio 各自使用的 JDK 17，用 `./gradlew --version` 确认固定发行版 |
| 依赖下载失败 | 检查上表仓库的网络可达性；首次联网成功前不要加 `--offline`，不要通过升级依赖或更换 AAR 绕过下载问题 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 根据目标设备 ABI 选用 arm64 或 v7a 单 ABI 包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 降级拒绝 | 使用独立测试环境，保留现有正式应用、数据和稳定入口 |
| 服务端启动但播放列表为空 | 先按部署说明配置自己有权使用的来源；空默认列表不是播放验证成功或抓源故障 |

完整构建与测试可能需要较多下载空间；GeckoView 原生依赖使 APK 体积较大。真实设备的持续播放、换台、恢复和音画同步仍需单独验收。

## 服务端

使用 [初始化与部署说明](../server/deploy/README.md)。`settings.example.json` 是公开合成示例；实际配置写入被忽略的独立运行目录。默认没有播放源、密钥或反馈配对。默认 Compose 的**宿主端口映射**绑定回环地址，容器内部服务监听 `0.0.0.0:8420`；裸 Python 启动也会使用配置中的监听地址，不能把它当作默认回环隔离。需要给电视访问时显式选择 LAN 监听地址，并配置访问边界。

## 发布候选

见 [发布工具说明](../tools/lan_release/README.md)。公共脚本不携带任何部署的 SSH 用户、主机、目录、证书 pin 或签名密码。家庭部署参数和取密钥步骤由本机可信配置提供；凭据仍只通过运行时环境交给工具。

脚本默认只准备版本化候选，不切换稳定入口。真实设备验收与稳定入口切换是独立步骤。
