# APK 候选、公开分发与家庭更新

源码公开、APK 下载和家庭应用更新是三个独立步骤。当前准备的是 `0.2.41-rc1` / versionCode `54`，包名 `com.kantv.app`。候选使用既有正式签名身份，分别生成 arm64-v8a 和 armeabi-v7a 两个单 ABI 包；不会因版本号增加而替换 v53 家庭稳定入口。

## 从同一提交准备发行包

先提交已经审查过的通用源码，并通过该提交的 CI。正式签名由自己的可信工具注入以下进程环境变量；不要把密码或令牌写进命令、源码、报告或 GitHub Actions 日志：

- `KANTV_RELEASE_KEYSTORE_FILE`
- `KANTV_RELEASE_KEYSTORE_PASSWORD`
- `KANTV_RELEASE_KEY_ALIAS`
- `KANTV_RELEASE_KEY_PASSWORD`

在 JDK 17、已配置 Android SDK 和 Android 签名工具的环境执行：

```sh
python3 tools/apk_release/prepare_release.py \
  --output .scratch/apk-candidate-unique \
  --expect-signing-cert-sha256 '<已从可信渠道核实的正式证书SHA256>'
```

输出目录必须是尚不存在的 Git 忽略目录。脚本拒绝有未提交公开改动的工作树，从指定 HEAD 的 Git archive 构建，避免复制本机忽略的家庭配置与默认播放列表。它从子进程环境移除全部家庭配对注入，只保留签名和明确绑定的源码版本、构建时间，核对生成的所有默认端点/令牌为空且正式诊断关闭。

输出包括对应源码归档、双 ABI Release APK、双通道清单、完整构建和校验日志，以及绑定提交与 APK SHA256 的 `PREPARATION.json`。脚本复用现有构建、打包和签名/ABI核验工具，不执行上传或激活。`SIGNED_BUILD_VERIFIED` 仅表示构建与静态核验完成；还须核对最终 APK 的分发材料和安装运行。

APK 分发材料按[第三方说明](../THIRD_PARTY_NOTICES.md)准备；不能把 FFmpeg r2 库源码包当成整个 APK 的全部分发材料。分发时把最终应用对应源码、许可和告知、依赖与源码获取记录、FFmpeg r2 及重组说明与 APK 同时提供。不得分发签名私钥或家庭配置。最终 APK 应在全新隔离环境核对签名、安装、首开、官网目录和基本遥控操作；测试版说明须如实标出尚未完成的真实设备与播放验收。

## NAS 为什么没有提示更新

家庭应用的“检查更新”读取已配对 NAS 更新服务的稳定清单。它不会检查 GitHub 源码提交，也不会自动选择 NAS 上仅保存的候选目录。NAS 服务端列表程序升级也不会自动升级电视 APK。

必须同时满足：清单可访问、版本号高于已安装版本、包名和签名证书一致、下载大小及 SHA256 正确，且 ABI 对应。否则不能作为可安装的新版本。诊断时先只读核对清单和 APK，不能为了出现更新提示而跳过身份/完整性检查，或把未验收候选切成稳定版本。

## NAS 保存候选与切换稳定入口

完成本地检查后，可按[受控发布工具说明](../tools/lan_release/README.md)把已保存的同一双 ABI staging 准备到明确授权的 NAS 目标。`publish_dual.py` 默认只保存候选、保留稳定清单和回滚快照；不加 `--activate`。NAS 上有候选仍不会让家庭应用收到更新，这是稳定入口保护的预期行为。

只有在真实设备完成要求的播放验收，且用户明确要求切换后，才能对已经验收的同一 staging 使用 `--activate`。不得重新构建不同字节的同版本包冒充已验收产物。发布前后核对稳定清单和回滚 APK，保留 v53 原件与配置。公开 GitHub 测试版下载也不授权切换家庭稳定入口。

## 私有资料的保存

家庭 wrapper 和部署参数保存在忽略的 `.private/`，签名文件与密码保存在忽略的 `.signing/`，家庭历史和备份保存在 `.archive/`、`.scratch/`、`deliveries/` 等忽略目录。已有服务端配置、运行数据和私有默认播放列表也有专门忽略规则；不会因生成公开包而删除它们。

`.gitignore` 用于避免新文件被加入 Git，不能撤销已被跟踪的文件或历史。发布前必须同时运行 `tools/check_public_tree.py`、完整历史的 `tools/check_public_history.py`，并检查实际 APK 和源码归档。不要使用 `git add -A`、`push --all` 或 `push --mirror`，也不要把整个本机项目目录直接压缩后上传。
