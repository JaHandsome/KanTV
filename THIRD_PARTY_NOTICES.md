# 第三方组件

项目自身许可证不改变下列组件的许可。分发构建产物时须保留相应告知、许可与适用的源代码提供义务。

| 组件 | 来源与许可记录 |
| --- | --- |
| Gradle Wrapper launcher | 仓库内 `gradle/wrapper/gradle-wrapper.jar` 与官方 Gradle v7.4.2 tag 的 JAR 字节一致（7.4/7.4.1/7.4.2 共用该 hash）；Apache-2.0。完整原版 LICENSE、NOTICE 与来源记录见 `third_party/gradle-wrapper/`。launcher 版本与它启动的 Gradle 8.11.1 distribution 分开记录，不将二者混称。 |
| AndroidX / Media3 | Gradle 依赖与 `app/src/main/assets/licenses/AndroidX-Media3-Apache-2.0.txt` |
| Media3 FFmpeg 集成与 FFmpeg | `app/src/main/assets/licenses/media3-ffmpeg-notice.txt` 与 `FFmpeg-LGPL-2.1.txt`；本地 AAR 为独立第三方构建产物 |
| GeckoView 146.0.20251217121356 / geckoview-exoplayer2 | MPL-2.0；POM 和内置 buildconfig 均对应 Mozilla revision `86bb7f6af6312ba3c0161085f854bcdff68f1a91`，完整源码为官方 Firefox 146.0.1 源包；APK 内保留完整 Gecko 第三方告知和 MPL 原文。 |
| Kotlin、协程和其他 Android 库 | 精确解析版本与制品/POM hash 见 `third_party/apk-runtime/runtime-components.json`（103 Maven + 1 本地 AAR）；原始告知随 APK assets 保留。DataStore 外部 protobuf 4.28.2 为 BSD-3-Clause；Gecko 传递的四个 Google Play services 组件声明 Android SDK License，不能概括为 Apache。 |
| Floating UI core 1.7.3 / DOM 1.7.4 | 原型页面外部运行时引用；MIT，完整文本见 `third_party/floating-ui/LICENSE`。不随 Android APK 打包。 |
| Lucide 1.17.0 | 原型页面外部运行时引用；完整告知见 `third_party/lucide/LICENSE`，包含 Lucide 的 ISC、Feather 派生图标清单和 Cole Bemis 的 MIT 告知，不能只归为 ISC。不随 Android APK 打包。 |

原型页面声明的第三方库未在仓库内复制其实现；精确下载地址、包 hash 与许可文件 hash 见 `third_party/prototype-components.json`。网络引用不会使项目 MIT 替代这些组件的许可。Gradle distribution 自带的许可与告知也应保留。

历史本地 AAR 的原始构建材料仍无法确认，不能仅凭文件名或二进制字符串断言其构建许可。它与 v53 私有保留，不把它冒称为新构建。

默认构建已使用来源明确的新 AAR，完整对应源码、补丁、工具链、固定依赖与重链接材料和制品身份一起记录，见 [默认接入记录](docs/FFMPEG_DEFAULT_20261015.md) 和 [构建配方](tools/ffmpeg/README.md)。拟公开历史不包含旧 AAR，历史家庭包私有保留。分发新库时须同时提供其对应源码材料；不能把新库来源记录用于证明旧 AAR。APK 的完整分发材料与真实播放验收另行完成。

当前仓库直接携带的二进制为上述 Wrapper JAR 与 FFmpeg AAR，没有随仓库分发 GeckoView 或其他 Maven 依赖的二进制，也没有图片、字体二进制资源。FFmpeg 对应材料中的上游完整源码归档保留各自原版许可。

APK 运行时材料已按实际 `releaseRuntimeClasspath` 整理，包含原始制品告知、精确 POM、完整 NDK r28c libc++ 告知、补充 BSD/MPL/Google SDK 许可、Gecko 对应完整源码入口和 FFmpeg 重链接后的 APK 重组合/签名说明，见 [APK 分发材料](tools/apk_distribution/README.md)。每个 APK 发行仍须绑定其精确应用源码快照、r2 包、APK 与材料 hash，并通过轻量运行时完整性门禁；本次材料准备不代表已公开 APK 或完成设备播放验收。

现有许可文本和告知予以保留；项目 MIT 不对第三方组件重新授权。
