# 隔离验证方法与边界

`generate_fixtures.py` 使用本机 FFmpeg / ffprobe 生成 997Hz、48kHz、双声道、2 秒的合成音调，覆盖 AAC、AC3、EAC3、MP2、MP3。输出含媒体文件、逐包数据、初始化数据、生成器版本和媒体哈希；不使用家庭清单、录音或外部媒体。

`FfmpegDecodeInstrumentation` 属于 androidTest，不进入正式包。它检查：

- JNI 实际加载，五种格式可用，未选用的 FLAC 不可用；
- 五种音频逐包解码至 EOS，输出长度、采样率、声道、音量与音调过零数；
- flush 后再次解码，完整输出长度不变，保存两轮 PCM 哈希；
- 用现有 `AudioOnlyExtensionRenderersFactory` 在测试中强制优先选 FFmpeg，实际完成 AAC → MP3 音频列表，检查暂停、恢复和 EOS。

AAC、AC3、EAC3 的旧库对照在 flush 后可能产生不同 PCM 哈希，因此不要求同一实例重置前后的哈希完全相同。比较旧库和候选时，按相同的首轮、flush 后轮次分别比较；这些合成样例的对照结果记录在验收报告中。

## 重跑

默认构建已使用来源清单绑定的可追溯 AAR。需要重建时先按 README 构建该库。准备两个来自已提交源码的可丢弃目录；只用 Git 跟踪文件，不复制家庭配置、默认清单、签名和工作记录。例如在仓库内：

```sh
review_root="$PWD/.scratch/ffmpeg-review"
mkdir -p "$review_root/control" "$review_root/default"
git archive HEAD | tar -x -C "$review_root/control"
git archive HEAD | tar -x -C "$review_root/default"
python3 tools/ffmpeg/generate_fixtures.py "$review_root/assets/ffmpeg"
```

在已启动的隔离模拟器上确认实际 ABI，并使用相同设置依次检查 control 和 default。构建前移除家庭的 `KANTV_*` 环境设置，构建脚本会拒绝继承它们。保留正常的 `JAVA_HOME`、`ANDROID_HOME` 和工具 PATH。

每次为对应目录构建时显式设置：

| 环境设置 | 含义 |
| --- | --- |
| `KANTV_FFMPEG_REVIEW_LABEL` | `control` 或 `default` |
| `KANTV_FFMPEG_REVIEW_ABI` | 模拟器实际 ABI |
| `KANTV_FFMPEG_REVIEW_FIXTURES` | 上述 `assets` 目录的绝对路径 |
| `KANTV_FFMPEG_REVIEW_AAR` | control 必填，私有保留的旧 AAR 绝对路径；default 必须不设置，禁止覆盖默认依赖 |
| `KANTV_FFMPEG_REVIEW_BUILD_TYPE` | `debug`（默认）或 `release` |

在对应可丢弃目录运行 Gradle，并通过 `-I` 指向本仓 `tools/ffmpeg/isolated-review.gradle` 的绝对路径。debug 运行 `:app:assembleDebug :app:assembleDebugAndroidTest`；release 运行 `:app:assembleRelease :app:assembleReleaseAndroidTest`。

init 配置只在这次构建中设置包名与 runner；default 直接使用仓库依赖且禁止 AAR override，control 显式换入私有保留的旧库。两类库均检查对应 hash，防止两组误用同一制品。默认构建的来源校验不会关闭，两个源码副本都必须保留已验证默认 AAR 及其来源清单。control/default 的应用 ID 分别固定为 `com.kantv.app.ffmpegcontrol`、`com.kantv.app.ffmpegdefault`；测试包为其后加 `.test`。重诊断关闭。release 保留压缩和混淆，仅对上述隔离应用使用测试签名，不读正式签名。直接跨 APK 调用的解码 API 可能已被目标 R8 内联或删除，因此隔离 release 将这个有限测试 runner 一起编入目标包，让 R8 同时处理调用关系；只保留 Instrumentation 入口，FFmpeg/JNI 仍使用原始 consumer 规则。测试 APK 排除重复 runner。这种包含测试代码的包不能称为正式发布包。

只在指定模拟器安装两种构建各自的应用和测试 APK，再运行：

```sh
adb -s "$review_serial" shell am instrument -w \
  com.kantv.app.ffmpegcontrol.test/com.kantv.app.review.FfmpegDecodeInstrumentation
adb -s "$review_serial" shell am instrument -w \
  com.kantv.app.ffmpegdefault.test/com.kantv.app.review.FfmpegDecodeInstrumentation
```

必须检查 JSON 中 `status=PASS`；不能仅凭 adb 退出码认定通过。历史 candidate 报告记录的是当时显式换入候选库的验证；接入默认依赖后使用 default 模式，不能把历史报告当成默认接入验收。还需比较 APK 内 `lib/<ABI>/libffmpegJNI.so` 与候选 AAR 的对应库哈希，证明打包和测试确实使用新库。

## 验收限制

这套测试不清空家庭应用数据，不连接家庭 NAS，不修改更新清单；control 的依赖覆盖只发生在可丢弃副本内。它是有限的合成解码与播放器集成检查，不是原始签名 v53 对照，不覆盖真实直播、所有编码参数、多声道透传、设备音频输出或可听音画同步。候选成为家庭稳定包前，仍需按 `docs/PLAYBACK_STABILITY.md` 完成真实设备验收。
