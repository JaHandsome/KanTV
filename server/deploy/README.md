# KanTV 服务端部署

本服务使用 Python 标准库，无需安装 pip 依赖。建议宿主机使用 Python 3.11 或更新版本运行初始化工具；Docker 镜像基于 Python 3.11，离线回归按 CI 使用 Python 3.12。公开默认配置不包含播放来源，首次启动时列表为空属于预期状态。以下步骤从仓库根目录开始，使用 Docker Compose 和独立的 `server/runtime/` 持久化目录。

## 首次启动

需要已安装 Docker Engine 和 Docker Compose v2 或更新版本的插件。首次构建还需要 Docker daemon 能下载 Python 基础镜像；终端能访问网站不等于 daemon 能访问镜像仓库。先克隆公开仓库，在仓库根目录生成全新的配置，再从 `server/` 启动：

```sh
git clone https://github.com/JaHandsome/KanTV.git
cd KanTV
python3 server/tools/init_config.py --output server/runtime/config
cd server
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

初始化器仅用代码中的公开默认值生成 `settings.json`、`channels.json`、`repos.json`、`rules.json` 和 `url_lists.json`。若目标目录已有这些文件之一或同名符号链接，命令会失败且不会覆盖；保留现有配置，或改用新的空目录并调整下文的 `IPTV_CONFIG_DIR`。`docker compose config --quiet` 在创建容器前检查 Compose 配置语法，不连接 Docker daemon。

Compose 默认把宿主机 `127.0.0.1:8420` 映射到容器 `8420`，容器内服务监听 `0.0.0.0:8420`。程序启动时先构建播放列表文件，再启动 HTTP 服务，所以在正常启动后 `/live.txt` 会存在，即使内容为空。检查服务和当前列表：

```sh
curl -i http://127.0.0.1:8420/healthz
curl -i http://127.0.0.1:8420/live.txt
```

`/healthz` 检查进程和调度器心跳；它成功不代表已有可播放来源。默认空配置会生成不含频道条目的播放列表；M3U 仍有文件头和 EPG 元数据。`/live.txt` 或 `/live.m3u` 返回 `404 not ready` 表示输出文件尚未生成，通常应先查看容器日志和启动错误。

## 基础镜像下载失败

默认基础镜像为 Docker Hub 的 `python:3.11-slim`。如果构建在下载镜像或访问 `auth.docker.io` 时失败，先检查 Docker daemon 所用的网络、DNS 和代理；这类错误发生在应用运行之前。不要为了绕过下载问题改服务端代码或换成来源不明的镜像。

也可以使用 [AWS ECR Public 上的 Docker 官方镜像](https://aws.amazon.com/blogs/containers/docker-official-images-now-available-on-amazon-elastic-container-registry-public/)。Dockerfile 已提供 `BASE_IMAGE` 参数；在 `server/` 目录中执行：

```sh
docker compose build --build-arg BASE_IMAGE=public.ecr.aws/docker/library/python:3.11-slim
docker compose up -d --no-build --pull never
```

这一路径不需要 AWS 登录，使用的是 [Docker 官方 Python 仓库](https://gallery.ecr.aws/docker/library/python)。它仍需要能访问 ECR Public，不能保证解决所有网络问题。`--no-build --pull never` 使用上一步生成的本地服务镜像；如果还未成功构建，启动会失败。

构建参数不会自动保存。以后需要重建镜像时，仍用上面两条命令，并重复自己需要的 `IPTV_*` 变量；直接运行 `up --build` 会重新使用默认的 Docker Hub 基础镜像。参数行为见 [Compose build 说明](https://docs.docker.com/reference/cli/docker/compose/build/)。

## 添加自己有权使用的来源

先配置要输出的目标频道，再添加来源。初始化器生成的 `server/runtime/config/channels.json` 中 `channels` 为空；仅填播放源不会自动接受所有频道。可以在该文件中配置最小目标清单，例如：

```json
{
  "channels": ["CCTV1"],
  "order": ["CCTV1"],
  "aliases": {
    "CCTV-1": "CCTV1"
  }
}
```

`channels` 是允许进入验证和输出的频道键，`order` 指定输出顺序，`aliases` 将来源中的别名映射到已列出的键。示例仅声明名称，不附带任何播放源；请按自己的来源配置目标和别名。未匹配的条目可能被发现为候选，但不会进入频道验证或播放列表。文件中省略的字段由代码默认值补齐；不要用此示例覆盖已有部署的完整频道配置。

如果已经导入来源，之后才补目标频道或别名，旧的未匹配条目不会因重复发现自动改名。以下恢复流程用于已经由当前版本正常启动、完成数据库迁移的部署。保存频道配置后，用镜像内的现有重归一化工具认领这些条目；先停止服务以避免同时更新，再预览可认领数量、执行并启动：

```sh
docker compose stop iptv-nas
docker compose run --rm --no-deps --pull never iptv-nas python3 tools/renormalize_channels.py --dry-run
docker compose run --rm --no-deps --pull never iptv-nas python3 tools/renormalize_channels.py
docker compose up -d --no-build --pull never
```

频道认领阶段只为 `channel_key` 为空的既有条目匹配当前目标，不删除来源，不改来源状态、验证结果或得分；认领后的候选由启动后的验证轮处理。`--dry-run` 仅跳过频道认领写入，CLI 仍会初始化日志并执行数据库迁移检查，不能当作对任意旧库只读。旧版本数据库先备份，再完成当前版本升级，随后使用本流程。

所有命令沿用同一组 Compose 项目、配置与数据目录变量。已有旧镜像需先按自己的基础镜像路径重建，才能使用该工具。

可以配置仓库清单或直接配置 M3U URL 清单。每个清单项的字段按服务端实际解析规则填写。以下 URL 使用保留示例域名 `example.invalid`，不会指向真实服务；使用前应替换为自己有权访问的实际来源。

仓库清单写入 `server/runtime/config/repos.json`。每项使用 `repo`（GitHub `OWNER/REPO`）、`branch`、仓库内 `path`、`channel`（`jsdelivr` 或 `ghproxy`）、`enabled` 和可选 `note`：

```json
{
  "repos": [
    {
      "repo": "OWNER/REPO",
      "branch": "main",
      "path": "playlist.m3u",
      "channel": "jsdelivr",
      "enabled": true,
      "note": "替换为自己维护或获准使用的仓库"
    }
  ]
}
```

直接 M3U URL 清单写入 `server/runtime/config/url_lists.json`。必需字段为 `id`、`url`、`enabled`；`refresh_minutes` 和 `user_agent` 可选：

```json
{
  "lists": [
    {
      "id": "my-playlist",
      "url": "https://example.invalid/playlist.m3u",
      "enabled": true,
      "refresh_minutes": 360
    }
  ]
}
```

列表应是服务可访问的 M3U 文本。服务端出站抓取仅接受 HTTP/HTTPS，默认要求清单、播放地址及重定向的 DNS 结果都是允许的公网单播地址。回环、内网、链路本地和保留网段默认会被 SSRF 防护拒绝；NAS 的可信内网媒体服务需按下一节逐条授权。这里的来源限制与下文“电视通过 LAN 访问本服务”是两个方向。

### 显式授权内网来源

在自己的 `server/runtime/config/settings.json` 中合并以下字段，保留其他配置。默认值为空列表，不会自动信任 NAS 所在网段：

```json
{
  "network": {
    "private_source_origins": [
      "http://192.168.1.10:8080",
      "https://[fd00::10]:8443"
    ]
  }
}
```

每项只授权“协议＋固定 IP＋端口”，必须显式写端口，不能带结尾斜杠、路径、查询、片段、用户名或密码。支持 IPv4 RFC1918 私网地址和 IPv6 ULA；不支持域名、CIDR、通配符、IPv6 zone 或映射地址。示例地址需替换为自己的专用媒体服务，实际清单 URL 则仍完整填写在 `url_lists.json` 中。

授权覆盖该 origin 上的全部 GET 路径。只列出自己控制的专用媒体端口，不授予 NAS 管理、Docker API 或路由器管理端口访问权限。名单只能由部署者维护，不能从播放列表、回流内容或外部请求动态生成。HLS 子清单、分片或密钥若位于另一内网 IP、端口或协议，也需要分别授权。

以下防护继续生效：

- 回环、链路本地、CGNAT、组播、保留网段及已知云元数据地址不能通过名单放行。IPv6 ULA 中也有 [AWS](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html) 和 [Google Cloud](https://docs.cloud.google.com/compute/docs/metadata/querying-metadata) 的标准元数据端点，仍予以拒绝。
- 授权固定 IP 直接绑定到确切连接地址，不经 DNS；给某个私网 IP 授权，不会允许任意域名解析到这个 IP。普通公网域名继续校验全部 DNS 结果并绑定连接，环境代理保持禁用。
- 同一内网 origin 内的路径重定向可跟随；公网跳入内网、内网跳到另一内网 origin 都拒绝，即使目标也在名单中。需要这类跨源跳转时，应在来源中直接配置最终目标。内网跳转到合规公网目标仍按普通公网策略校验。
- HTTPS 证书验证、请求体上限、总超时和并发限制不变；授权内网地址不会让自签名或不匹配的证书自动受信。

授权集合在网络客户端启动时固定，新增和删除都需要重启生效。撤销权限时先停止服务、删除对应条目，再启动；仅保存文件不会立即收回运行中客户端的旧授权。错误的名单会拒绝启动；热重载遇到无效配置时沿用上一次有效设置。配置检查只报告字段和条目序号，不输出条目内容。

撤销只阻止服务端后续抓取，不会自动删除数据库、已生成列表或客户端已有的来源 URL；停用已投递来源还需要另外整理来源与订阅。

调度器每次 tick 开始时都会重读配置；空闲等待最多 30 秒，但若当前 tick 正在执行网络任务，要等该任务结束后才会重新读取。读到配置不等于立即拉取来源。默认来源发现周期是 6 小时，验证周期 2 小时，探测周期 30 分钟。若希望配置来源后立即触发发现，可以重启服务：

```sh
docker compose restart iptv-nas
```

重启会重新开始调度，启动后的首轮会按当前配置运行到期任务；首次部署启动时也会立即开始首轮调度。若 `refresh_minutes` 阻止 URL 清单再次拉取，重启会清空该间隔的进程内计时并再次尝试。读取 `live.txt` 或 `live.m3u` 确认最终生成内容；仅在 `/healthz` 返回成功时不能判断来源是否可用。

## 局域网客户端访问

默认仅本机可访问。先按前文完成镜像构建；若要让局域网电视或其他设备访问，在 `server/` 目录显式指定运行 Docker 主机的局域网地址，并复用已构建镜像，例如：

```sh
IPTV_BIND_ADDRESS=192.168.1.10 docker compose up -d --no-build --pull never
```

将示例地址替换为该主机当前实际使用的局域网 IP。客户端订阅地址为 `http://192.168.1.10:8420/live.txt`；也可用 `/live.m3u`。先从另一台局域网设备打开该地址确认可达。不要把服务直接映射到公网。每次运行 `docker compose up` 创建或更新服务时都要带上相同的 `IPTV_BIND_ADDRESS` 赋值，否则 Compose 会按默认值改回 `127.0.0.1`。`docker compose start` 只是启动现有容器，不更改端口映射。仓库当前未忽略 `server/.env`，不要把 Compose 变量写入该文件以免误提交。

如果使用生成的 TVBox snippet，需将 `server/runtime/config/settings.json` 中 `http.snippet_host` 改成客户端可访问的主机名或 IP。snippet 使用 `http.port`（默认 8420），因此改变宿主映射端口时请直接配置客户端使用 `http://主机:端口/live.txt`，不要依赖 snippet。

普通 M3U 订阅不需要启用 Hub、配对或重编客户端。Hub 是独立的进阶接口，默认关闭且 token 为空；只有在了解既有 [Hub 契约](../docs/HUB_CONTRACT_V1.md)并完成独立的安全配置后才应启用。不要把 token 写入公开仓库、镜像或示例文件。

## 数据、配置与日常操作

Compose 把 `server/runtime/config` 和 `server/runtime/data` 分别挂载到容器的 `/app/config` 和 `/app/data`。数据目录保存数据库、种子和生成文件；配置与数据都留在仓库本机的 `server/runtime/` 下。不要把真实来源 URL、凭据、日志或运行数据提交到仓库。

从仓库根目录进入 `server/` 后，按需单独执行下列操作；实时日志用 Ctrl+C 退出查看，不会停止容器。创建或更新 LAN 服务时，仍需按上节重复同组 Compose 变量。

```sh
cd server
docker compose logs --tail=100 -f iptv-nas
docker compose stop
docker compose start
docker compose down
```

`stop` 停止容器但保留目录和数据；`start` 重新启动已有容器；`down` 移除容器和 Compose 网络，宿主机 bind mount 的 `runtime/config` 与 `runtime/data` 仍保留。代码没有变化时，用 `docker compose up -d --no-build --pull never` 重新创建容器。

拉取或切换代码后需要重建镜像：默认路径用 `docker compose up -d --build`；使用 ECR 备用路径时，重复 [基础镜像下载失败](#基础镜像下载失败) 中带 `BASE_IMAGE` 的构建与本地启动两步。两种路径都要保留原来的 `IPTV_*` 变量。首次部署或升级后查看 `docker compose ps` 和日志，再检查 `/healthz`、`/live.txt`。不要用会删除卷或目录的命令清理运行数据。

可在 `server/` 启动命令前设置以下变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `IPTV_BIND_ADDRESS` | `127.0.0.1` | 宿主监听地址；局域网访问时显式指定主机的局域网 IP |
| `IPTV_PORT` | `8420` | 宿主机端口；容器内仍使用 8420 |
| `IPTV_CONFIG_DIR` | `./runtime/config` | 宿主配置目录 |
| `IPTV_DATA_DIR` | `./runtime/data` | 宿主数据目录 |
| `TZ` | `UTC` | 容器时区 |

Compose 文件中的相对挂载路径以 `server/`（`docker-compose.yml` 所在目录）为基准；从仓库根目录用 `-f server/docker-compose.yml` 启动时也仍使用 `server/runtime/`。使用其他配置目录时，先通过初始化器为其生成全新配置，再设置 `IPTV_CONFIG_DIR` 指向它。

## 常见问题

- **`/live.txt` 返回 `404 not ready`：** 正常启动会在开放 HTTP 服务前生成列表文件，因此此响应通常表示启动构建失败或输出目录挂载/权限异常。查看 `docker compose logs --tail=100 iptv-nas`。若列表返回 `200` 但为空，才表示当前没有可输出线路；配置自己有权使用的来源后重启服务立即触发首轮发现，或等待发现周期。
- **局域网设备连接失败：** 检查 `IPTV_BIND_ADDRESS` 是否是 Docker 主机的局域网 IP、端口映射是否生效，以及主机防火墙是否允许该局域网端口。默认 `127.0.0.1` 只接受本机访问。
- **来源日志出现 `blocked non-public address`：** 默认拒绝私网解析结果；NAS 媒体源需按 [内网授权](#显式授权内网来源) 配置固定 IP、协议和端口并重启。回环、链路本地、保留网段等禁用地址仍不可授权，域名也不能借用某个 IP 的授权。
- **已发现来源但列表仍为空：** 先确认 `channels.json` 的目标清单不是空的，来源频道名能匹配目标或别名；发现候选不等于验证成功。再查看验证结果，只有符合输出条件的已激活线路才会进入列表。
- **初始化提示目标已有配置：** 这是保护现有配置的行为。不要删除或覆盖未知数据；为公开部署选择新的空目录并相应设置 `IPTV_CONFIG_DIR`。
- **`tvbox_lives_snippet.json` 中的地址在客户端不可用：** 按上文设置 `http.snippet_host`，并确认客户端能访问该主机名和内部端口 8420。
- **`docker compose` 命令不可用：** 安装并启用 Docker Compose 插件，再从 `server/` 目录运行命令。

## 本地配置检查

从仓库根目录执行以下命令，只验证样例和 Compose 配置，不连接远端服务：

```sh
python3 -m unittest discover -s server/tests -p test_public_config.py
cd server
docker compose config --quiet
```
