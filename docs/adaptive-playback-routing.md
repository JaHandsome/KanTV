# 通用播放内核识别与学习

> **状态：历史设计记录（2026-09-28 标注）** —— 本文的自动选核设计以“Media3 + LibVLC 双内核”为前提；LibVLC 已移除，当前客户端为 Media3 单内核（网页频道走 GeckoView 镜像）。保留作研究记录，不代表当前架构。

## 为什么不能按频道或编码格式写死

当前 70 个源的实测结果中，H.264 同时出现在 Media3 优先、LibVLC 优先、两个都通过和两个都失败四类结果里。HLS 单层清单、25fps、1080p 等单一特征也没有形成可靠分界。因此播放器不使用频道名称或一张固定名单决定内核。

直播是否稳定由多层因素共同决定：

- 网络和服务器：连接、重定向、分片响应、鉴权和限速。
- 清单和封装：HLS 主清单或媒体清单、MPEG-TS 或 fMP4、分片时长。
- 时间轴：PTS/DTS、时间戳跳变、回绕、关键帧和音视频起始位置。
- 轨道：视频编码、profile/level、分辨率、帧率、色彩、音频编码和多音轨。
- 设备：电视厂商的 MediaCodec 实现、可用硬件解码器和系统版本。
- 播放器路径：Media3 或 LibVLC 的清单解析、解复用、时间戳修正和送帧方式。

## 当前实现

播放器采用“能力筛选、运行时验证、设备本地学习”三步：

1. 新源默认交给 Media3。Media3 根据清单和轨道格式选择 Android 解码器，并启用同类型解码器回退。
2. 播放器观察首帧、视频帧持续推进、缓冲和错误。常规内核超时后会在同一个源上切换另一内核；兼容内核最多等待 45 秒，以便慢速 HLS 先建立连续缓存。首帧后六秒没有新视频帧同样会触发切换。
3. Media3 与 LibVLC 都使用独立内部会话。切换后，旧内核迟到的事件不能改变当前频道状态。
4. 离线、401/403 和直播窗口落后不会错误地降低某个内核的兼容评分；这类失败优先走对应恢复逻辑。
5. 连续稳定播放一分钟后，应用保存该源的最佳内核。记录只存在当前设备，键是播放请求的 SHA-256 指纹，不保存明文地址或请求头。
6. 下次遇到相同源时优先使用这台设备上次验证成功的内核。优先内核失败后仍会尝试另一个内核；两个都失败后才进入频道的下一条备用源。

Media3 使用 `VideoFrameMetadataListener` 判断视频帧是否继续送出。LibVLC 每秒读取 `displayedPictures` 和 `decodedVideo`，只在显示画面计数增长时确认画面推进；统计不可用时不会把音频时钟冒充视频帧。这比只看 `READY`、首帧或音频是否继续更接近用户实际看到的画面。

对普通、未加密且使用 MPEG-TS 分片的 HLS，兼容内核会并行预取分片，确认连续顺序后，通过仅本机可访问的连续 TS 数据流交给 LibVLC。这样播放器从预存开头消费，不会直接跳到下载追不上的直播边沿。主清单、加密 HLS、fMP4 初始化片段和字节范围分片不走这条链路；它们保持原始播放方式。

当前 LibVLC 兼容路径明确请求关闭硬件视频解码，走软件解码路径，以避开模拟器上两个内核实际共用同一个 Android H.264 解码器的问题。目标电视仍需分别验证：软件解码会增加 CPU 和发热，真机硬件解码如果可靠，通常更省资源。

## 播放前能知道什么

Media3 解析媒体后可得到容器 MIME、视频和音频 MIME、codec 字符串、profile/level、码率、分辨率、帧率、采样率、DRM 和设备声明的格式支持状态。还可以记录实际初始化的 Android 解码器和解码器错误。

这些信息适合做明显的不支持格式筛选和设置页诊断，但不能保证成功播放。设备可能错误声明能力；合法的 H.264 也可能因为 TS 时间戳、关键帧或分片异常而只显示一帧。最终选择必须保留运行时验证。

## 已实现的播放诊断

设置中的“播放诊断”页已经显示当前频道和源序号、当前内核、内核选择或切换原因、容器提示、视频/音频/字幕轨道、实际视频解码器、首帧时间、累计掉帧与最近错误码。页面不显示播放地址、请求头或凭据。容器字段同时标明来自播放器识别还是按地址后缀推断，避免把推断值当成实测值。

Media3 可以通过 Android 播放器回调报告实际初始化的解码器名称。当前 LibVLC 路径由应用明确请求软件解码，因此诊断页显示“已请求 LibVLC 软件解码（具体模块不可用）”；这表示应用选择的路径，不冒充 LibVLC 内部更细的实际模块名。

## 后续增强

- 将稳定标准从一分钟无错误扩展为首帧速度、缓冲次数、丢帧、音画同步和连续帧推进的综合评分。
- 对短期网络错误降低惩罚，只有可重复的解析、解码或帧停滞才改变长期偏好。
- 在 TCL Q10M Pro 和极光盒子 5X 分别建立本机画像；模拟器结论不复制为真机固定名单。

## 官方能力依据

- [Media3 支持的格式](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Media3 Tracks](https://developer.android.com/reference/androidx/media3/common/Tracks)
- [Media3 Format](https://developer.android.com/reference/androidx/media3/common/Format)
- [Media3 AnalyticsListener](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener)
- [Media3 DecoderCounters](https://developer.android.com/reference/androidx/media3/exoplayer/DecoderCounters)
- [Media3 VideoFrameMetadataListener](https://developer.android.com/reference/androidx/media3/exoplayer/video/VideoFrameMetadataListener)
