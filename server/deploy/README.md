# KanTV 服务端部署

本服务使用 Python 标准库，无需安装 pip 依赖。建议宿主机使用 Python 3.11 或更新版本运行初始化工具；Docker 镜像基于 Python 3.11，离线回归按 CI 使用 Python 3.12。公开默认配置不包含播放来源，首次启动时列表为空属于预期状态。以下步骤从仓库根目录开始，使用 Docker Compose 和独立的 `server/runtime/` 持久化目录。

## 首次启动

需要已安装 Docker Engine 和 Docker Compose 插件。先克隆公开仓库，在仓库根目录生成全新的配置，再从 `server/` 启动：

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

`/healthz` 检查进程和调度器心跳；它成功不代表已有可播放来源。默认空配置会生成空播放列表。`/live.txt` 或 `/live.m3u` 返回 `404 not ready` 表示输出文件尚未生成，通常应先查看容器日志和启动错误。

## 添加自己有权使用的来源

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

列表应是服务可访问的 M3U 文本。调度器每次 tick 开始时都会重读配置；空闲等待最多 30 秒，但若当前 tick 正在执行网络任务，要等该任务结束后才会重新读取。读到配置不等于立即拉取来源。默认来源发现周期是 6 小时，验证周期 2 小时，探测周期 30 分钟。若希望配置来源后立即触发发现，可以重启服务：

```sh
docker compose restart iptv-nas
```

重启会重新开始调度，启动后的首轮会按当前配置运行到期任务；首次部署启动时也会立即开始首轮调度。若 `refresh_minutes` 阻止 URL 清单再次拉取，重启会清空该间隔的进程内计时并再次尝试。读取 `live.txt` 或 `live.m3u` 确认最终生成内容；仅在 `/healthz` 返回成功时不能判断来源是否可用。

## 局域网客户端访问

默认仅本机可访问。若要让局域网电视或其他设备访问，在 `server/` 目录启动时显式指定运行 Docker 主机的局域网地址，例如：

```sh
IPTV_BIND_ADDRESS=192.168.1.10 docker compose up -d --build
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
docker compose up -d --build
```

`stop` 停止容器但保留目录和数据；`start` 重新启动已有容器；`down` 移除容器和 Compose 网络，宿主机 bind mount 的 `runtime/config` 与 `runtime/data` 仍保留；拉取或切换仓库代码后用 `up -d --build` 重建镜像并启动。首次部署或升级后查看 `docker compose ps` 和日志，再检查 `/healthz`、`/live.txt`。不要用会删除卷或目录的命令清理运行数据。

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
