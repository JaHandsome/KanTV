# 通用本地服务样例

服务端只使用 Python 3 标准库，无 pip 依赖。公开镜像只包含程序、初始化工具和合成默认配置；
不复制本机 `config/`、来源清单、种子、数据库、令牌、日志或运维文档。Hub 默认关闭且 token 为空，
不会因为缺少配置而启用反馈接口。空配置启动后没有可播放线路，这是预期行为。

## 新部署

从仓库根目录生成独立配置，然后启动容器。初始化工具只使用代码默认值，不读取当前家庭部署配置；
目标已有任一同名配置文件或符号链接时拒绝覆盖。

```sh
python3 server/tools/init_config.py --output server/runtime/config
cd server
docker compose up -d --build
```

默认访问地址为 `http://127.0.0.1:8420/healthz`，空订阅为 `/live.txt`。
也可以在 `server/` 目录运行 `python3 app/main.py`；该方式读取 `server/config/`，
因此不要在已有私有配置的工作目录上用它试运行公开默认值。

`server/config/settings.example.json` 是由 `DEFAULT_SETTINGS` 合成的完整公开样例，
可以用于了解设置字段。修改自己新建的 `runtime/config/` 配置，添加有权使用的来源到
`repos.json` 或 `url_lists.json`，并按需配置频道和规则。公开默认值不携带任何来源。
已有部署应继续使用自己的外置配置与 compose，不用本样例覆盖。

## 端口、路径与局域网访问

Compose 支持以下非秘密环境变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `IPTV_BIND_ADDRESS` | `127.0.0.1` | 宿主绑定地址；局域网访问时显式填该主机的局域网地址 |
| `IPTV_PORT` | `8420` | 宿主映射端口，容器内部仍监听 8420 |
| `IPTV_CONFIG_DIR` | `./runtime/config` | 自己的配置目录 |
| `IPTV_DATA_DIR` | `./runtime/data` | 自己的数据库、种子和输出目录 |
| `TZ` | `UTC` | 容器时区 |

修改 `runtime/config/settings.json` 的 `http.snippet_host` 为客户端可访问的主机名后，
生成的 tvbox snippet 才适用于远端客户端。snippet 使用内部 `http.port`，因此需要该功能时
保持宿主端口为 8420；若单独修改 `IPTV_PORT`，可直接使用自己的 `http://主机:端口/live.txt` 订阅地址。
不要直接映射公网；本服务的部分订阅与状态路由没有全局登录门槛，Hub token 只保护契约约定的路由。

启用 Hub 是单独的部署决定：在自己的运行配置中显式设置 `hub.enabled`，由既有安全工具提供 token，
并通过工具将同一配对端点和 token 注入客户端构建环境。不要在样例、镜像或仓库保存真实 token。
镜像不附带现场 URL-key 向量文件；需要契约向量端点时，仅在自己的运行环境中将权威文件只读挂载到
`/app/docs/urlkey_vectors.json`，并遵守服务端契约的鉴权与验证要求。

## 本地验证

```sh
python3 -m unittest discover -s server/tests -p test_public_config.py
cd server
docker compose config --quiet
docker build -t kantv-hub:local .
```

运行数据与配置保存在自己的运行目录。停止容器不会删除这些文件；保留版本与数据备份后再进行升级。
