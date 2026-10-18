# 第三方组件

项目自身许可证不改变下列组件的许可。分发构建产物时须保留相应告知、许可与适用的源代码提供义务。

| 组件 | 来源与许可记录 |
| --- | --- |
| Gradle Wrapper launcher | 仓库内 `gradle/wrapper/gradle-wrapper.jar` 与官方 Gradle v7.4.2 tag 的 JAR 字节一致（7.4/7.4.1/7.4.2 共用该 hash）；Apache-2.0。完整原版 LICENSE、NOTICE 与来源记录见 `third_party/gradle-wrapper/`。launcher 版本与它启动的 Gradle 8.11.1 distribution 分开记录，不将二者混称。 |
| AndroidX / Media3 | Gradle 依赖与 `app/src/main/assets/licenses/AndroidX-Media3-Apache-2.0.txt` |
| Media3 FFmpeg 集成与 FFmpeg | `app/src/main/assets/licenses/media3-ffmpeg-notice.txt` 与 `FFmpeg-LGPL-2.1.txt`；本地 AAR 为独立第三方构建产物 |
| GeckoView | `app/build.gradle.kts` 中的 Mozilla Maven 依赖；遵守对应 Mozilla 组件许可 |
| Kotlin、协程和其他 Android 库 | 各自上游制品中的许可与告知，精确版本见 Gradle 配置 |
| Floating UI core 1.7.3 / DOM 1.7.4 | 原型页面外部运行时引用；MIT，完整文本见 `third_party/floating-ui/LICENSE`。不随 Android APK 打包。 |
| Lucide 1.17.0 | 原型页面外部运行时引用；完整告知见 `third_party/lucide/LICENSE`，包含 Lucide 的 ISC、Feather 派生图标清单和 Cole Bemis 的 MIT 告知，不能只归为 ISC。不随 Android APK 打包。 |

原型页面声明的第三方库未在仓库内复制其实现；精确下载地址、包 hash 与许可文件 hash 见 `third_party/prototype-components.json`。网络引用不会使项目 MIT 替代这些组件的许可。Gradle distribution 自带的许可与告知也应保留。

历史本地 AAR 的原始构建材料仍无法确认，不能仅凭文件名或二进制字符串断言其构建许可。它与 v53 私有保留，不把它冒称为新构建。

默认构建已使用来源明确的新 AAR，完整对应源码、补丁、工具链、固定依赖与重链接材料和制品身份一起记录，见 [默认接入记录](docs/FFMPEG_DEFAULT_20261015.md) 和 [构建配方](tools/ffmpeg/README.md)。拟公开历史不包含旧 AAR，历史家庭包私有保留。分发新库时须同时提供其对应源码材料；不能把新库来源记录用于证明旧 AAR。APK 的完整分发材料与真实播放验收另行完成。

当前仓库直接携带的二进制为上述 Wrapper JAR 与 FFmpeg AAR，没有随仓库分发 GeckoView 或其他 Maven 依赖的二进制，也没有图片、字体二进制资源。FFmpeg 对应材料中的上游完整源码归档保留各自原版许可。

以后分发 APK 时须按该 APK 的实际依赖整理完整运行时许可与告知，包括 NDK 静态运行库的告知；提供修改 FFmpeg 后重新组合、安装应用所需材料，并为 GeckoView 的 MPL 覆盖源码提供明确、可实际取得的来源说明。仅有 Maven 坐标、库源码包或模拟器通过都不能代替该 APK 的分发材料和真实设备验收。本次未公开分发 APK。

现有许可文本和告知予以保留；项目 MIT 不对第三方组件重新授权。
