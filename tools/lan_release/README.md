# 通用局域网候选准备与受控切换

`release_local.sh` 构建、核验并向显式指定的部署目标保存不可变候选，默认不替换稳定更新入口。
只有用户完成真实设备验收且明确要求切换时，才使用 `--activate`。本地检查、候选上传与真实设备播放验收分别记录。
脚本不会发现家庭地址、读取固定 Keychain 项目或从固定 NAS 路径取令牌。

## 准备与配置

使用 JDK 17、Android SDK 和自己的既有签名文件。先通过已有可信渠道核对目标 hostname、
SSH known_hosts、包名和正式证书 SHA-256；不能把本次未知连接返回的身份自动当作期望值。
不关闭 SSH 主机密钥检查。候选只能高于两个现有通道清单的版本；此入口用于已有双通道发布目录，
不自动创建第一份稳定清单或变更签名身份。

以下变量均须显式设置。脚本先在本地检查必填项，再开始构建：

| 变量 | 含义 |
| --- | --- |
| `KANTV_RELEASE_SSH_HOST` | 已核实的 SSH 目标或本机 SSH 配置别名 |
| `KANTV_RELEASE_DIR` | 已有双通道发布目录的远端绝对路径 |
| `KANTV_NAS_HOSTNAME` | 从可信渠道核实的目标 hostname |
| `KANTV_RELEASE_APPLICATION_ID` | 既有 Android applicationId |
| `KANTV_RELEASE_CERT_SHA256` | 可信正式签名证书的 64 位十六进制 SHA-256 |
| `KANTV_RELEASE_KEYSTORE_FILE` | 自己的现有 keystore 文件 |
| `KANTV_RELEASE_KEY_ALIAS` | 既有签名别名 |
| `KANTV_RELEASE_KEYSTORE_PASSWORD` / `KANTV_RELEASE_KEY_PASSWORD` | 由调用工具直接注入进程环境的密码 |

`ANDROID_HOME` 等构建环境由调用者配置。更新功能的 `KANTV_UPDATE_ENDPOINT` / `KANTV_UPDATE_TOKEN`、
Hub 的 `KANTV_HUB_ENDPOINT` / `KANTV_HUB_FEEDBACK_TOKEN` 必须分别成对注入；每对同时留空则不配置该功能。
诊断配置同样由现有构建环境提供，正式包重诊断保持关闭。不要把令牌或密码写入配置、命令行参数、日志、清单或仓库，也不要启用 shell 跟踪。

发布入口只接受环境变量，不读取或执行配置文件。由调用者的既有工具或仓库外的私有 wrapper
先注入环境，再调用本入口；密码和 token 只经工具注入环境，不保存为配置字段。
仓库不提供家庭参数或凭据读取默认值。

```sh
# 调用者已显式设置上表环境变量，由已有工具注入密码与可选 token 后执行：
bash tools/lan_release/release_local.sh
```

每次在 `app/build/outputs/release-staging/candidate.*` 留存独立候选，不清空既有 staging、版本 APK 或基线。
远端需有 Python 3、标准库和支持硬链接及 `flock` 的本地文件系统，不安装第三方依赖。

## 激活已验收的同一候选

使用保存的 staging 目录，避免重新构建导致同版 APK 字节变化。以下仅为命令格式；满足真实设备验收和用户明确切换要求后才执行。

```sh
python3 tools/lan_release/publish_dual.py \
  --staging '<已保存的候选目录>' \
  --ssh-host '<已核实的 NAS SSH 目标>' \
  --release-dir '<既有 NAS 发布目录>' \
  --expect-nas-hostname '<已核实的 hostname>' \
  --expect-application-id '<既有 applicationId>' \
  --expect-signing-cert-sha256 '<既有正式证书 SHA-256>' \
  --activate
```

不加 `--activate` 则只准备候选。清单仍严格使用现有 8 个字段，不增加凭据、地址或发布控制字段。

## 写入边界与失败处理

- 本地先执行现有 `verify-dual`，核验包内身份、签名、hash、size 和单 ABI；远端发布不接受缺少 aapt2 时的兼容跳过路径。
- SSH 标准输入只落在发布目录内的唯一临时目录。完整上传并核验前，不写任何版本 APK 或稳定清单；断流与坏 hash 会清理本次临时文件。
- 远端持有 `.release.lock` 后重新严格读取两个当前清单。候选版本必须大于两个当前版本，包名和证书必须符合固定期望。既有回滚 APK 必须存在，size/hash/ABI 必须匹配。
- NAS 不要求 Android SDK。远端既有 APK 的证书信任依据是固定期望证书与当前清单一致，且 APK 的完整 hash 与该清单绑定；本地候选签名由 Android 工具实际读取核验。
- 两个版本 APK 都先检查同名冲突，再经唯一临时文件、hash 校验与硬链接原子发布。既有同名同 hash 可复用，同名异 hash 或符号链接直接拒绝。其他历史文件不删除。
- `candidates/v<version>-<唯一后缀>/` 保存候选清单和本次切换前的 `rollback-*.json` 快照。默认 `PREPARED`，两个稳定清单保持原内容。
- 显式激活时先替换 v7a、再替换 legacy 清单。可捕获的切换异常会尝试恢复两份原清单，恢复失败返回 `ROLLBACK_FAILED`，快照保留。不得在该状态下继续发布；先用快照恢复并复核两个清单及其 APK。
- 两个独立清单无法通过一次文件系统操作共同替换，切换间可能短暂跨通道不同版；每一通道始终引用完整 APK。进程被强杀或断电不保证自动恢复，此时用保存的快照人工恢复。所有写入者必须遵守同一锁；旧脚本或人工并发写入不受此锁约束。

`atomic_copy_file` 默认拒绝覆盖既有异 hash 文件，适用于发布产物；只有 `build_dual_abi.stage_apk`
的可重复构建工作输出显式使用 `replace=True`。旧式单通道 `pack` 仍默认使用固定文件名，后续不同内容会拒绝；需要保留历史时使用 `--abi` 或显式的新 `--apk-name`。

## 离线回归

```sh
python3 -m unittest discover -s tools/lan_release
bash -n tools/lan_release/release_local.sh
```

`test_release_local.py` 用完整替身截断构建和认证工具，覆盖必填参数、配对端点、默认候选与显式激活，并验证含工具命令的配置文件不会执行。
`test_publish_dual.py` 用隔离目录、假 APK 和本机 Python 子进程代替 SSH；覆盖同版、降版、同名异 hash、断流、坏 hash、回滚 APK 损坏、身份不符、默认准备、显式激活、第二清单切换失败恢复和实际 bootstrap/tar 入口。测试不连接 NAS、不读取 Keychain、不签名构建。
