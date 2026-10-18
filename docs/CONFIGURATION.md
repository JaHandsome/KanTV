# 构建与实例配置

公共构建默认没有 Hub、更新或诊断配对。用户可直接使用官网目录或导入自己的清单，无需提供 NAS。

## 客户端

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

## 服务端

使用 [初始化与部署说明](../server/deploy/README.md)。`settings.example.json` 是公开合成示例；实际配置写入被忽略的独立运行目录。默认没有播放源、密钥或反馈配对，默认绑定回环地址。需要给电视访问时显式选择 LAN 监听地址，并配置访问边界。

## 发布候选

见 [发布工具说明](../tools/lan_release/README.md)。公共脚本不携带任何部署的 SSH 用户、主机、目录、证书 pin 或签名密码。家庭部署参数和取密钥步骤由本机可信配置提供；凭据仍只通过运行时环境交给工具。

脚本默认只准备版本化候选，不切换稳定入口。真实设备验收与稳定入口切换是独立步骤。
