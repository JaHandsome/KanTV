# TiviMate 官方安装包分析记录

日期：2026-09-08；范围：本地下载、静态分析和原版首次启动观察。

## 样本身份

- 官方入口：https://tivimate.com/apk （[官方产品页](https://tivimate.com/dashboard/subscription)提供）。
- 保存位置：`reference/tivimate/tivimate.apk`，只在本机保留，已被项目 `.gitignore` 排除。
- 包名：`ar.tvplayer.tv`。
- 版本：5.3.3；versionCode：1000005332。
- SHA-256：`beaebb25bf818450bf496d4cefb16885b190f156815dea280c31eaae0909ee6c`。
- 安装包声明最低 Android API 23、目标 API 34；这是参考应用信息，不能当作 kantv 的兼容承诺。
- apksigner 验证通过 v1、v2、v3；未做独立证书来源交叉验证。

## 实际分析结果

1. APK 内有 3 个 DEX 和 ARM 32/64、x86 32/64 四种架构原生库。
2. 发现 AndroidX Media3/ExoPlayer 类及 FFmpeg JNI、SQLite 相关库。这只能证明依赖存在，不能推断完整播放策略或全部格式支持。
3. 发现 `libdexprotector.so`、受保护应用入口和本地方法桥接；设置 XML 输出仍为不可直接读取的二进制内容。
4. 使用项目本地 JADX 1.5.6 尝试解析，配置/缓存同样放在 `.tools/` 中；处理进度 4,882 个项目，最终输出 6,541 个 Java 文件，退出码 3、错误数 55。多数文件属于依赖或混淆内容，不能视为完整业务源码。
5. Manifest 可读，包含播放列表向导、节目单源管理、备份恢复、频道分组和录制存储等入口线索。
6. 默认键位、页面切换与异常恢复无法据此确认；均保持“待核实”。

## 原版运行观察

- 在现有 Television_1080p（Android TV API 34、arm64）基础上启动本轮独立端口 5580 的临时只读会话，禁用快照保存。
- 官方原始 APK 安装成功；启动后显示使用条款。
- 条款包含禁止反向工程或提取源码的限制。发现后停止继续还原代码，未修改 APK、未解除保护、未绕过付费功能。
- 用户已明确授权接受条款并正常操作原版，随后进入初始配置、导入和播放界面。一次含糊授权被自动审批拒绝，取得明确授权后才执行接受。
- 本轮生成本机测试画面和三个虚构频道，没有导入真实频道或凭据。最初 MP4 样例被原版识别成影片；改为 TS 样例后识别为三个电视频道。短样例自然结束会干扰观察，正式按键观察改用 600 秒合成 TS。
- 已正常显示合成视频画面，并用频道侧栏切换到另一虚构频道；这是原版模拟器观察，不是 kantv 或真实直播验收。
- 免费状态下添加第二列表、更新所有列表等控件禁用；未购买、登录或绕过限制。
- 已核实行为见 `remote-behavior.md`；图片与 UI 结构保存在 `reference/tivimate/ui/`。

## 本机证据

| 文件 | 用途 |
|---|---|
| `reference/tivimate/badging.txt` | 版本、包名和兼容声明 |
| `reference/tivimate/signature.txt` | 安装包签名校验 |
| `reference/tivimate/package-inventory.json` | DEX、原生库和资源清单 |
| `reference/tivimate/manifest.txt` | 原始 Manifest 静态解析 |
| `reference/tivimate/jadx.log` | 解析结束状态及错误计数 |
| `reference/tivimate/decoded/` | 有局限的解析输出，仅研究，不作为 kantv 实现 |
| `reference/tivimate/ui/01-launch.xml` | 首次启动条款页的 UI 结构 |
| `reference/tivimate/ui/01-launch.png` | 首次启动屏幕证据 |

## 后续实现边界

kantv 完全从零编写。参考材料、第三方素材和解析结果不进入应用源码或发布包。先核实使用行为、形成自己的交互契约，再实现可操作原型；未核实项不得标称与 TiviMate 完全一致。

当前不具备两台真实设备系统版本、真实播放源和设备实测结果；不宣称秒切、全格式兼容或电视端通过。
