#!/bin/bash
# 通用候选准备：显式配置 → 双 ABI 正式构建 → 核验 → 不可变候选。
# 不内置 SSH 目标、签名身份、凭据来源或家庭地址。默认不切稳定更新入口。
set -euo pipefail

ACTIVATE=
if [ "${1:-}" = "--activate" ] && [ "$#" -eq 1 ]; then
  ACTIVATE=--activate
elif [ "$#" -ne 0 ]; then
  echo "用法：$0 [--activate]；配置只接受环境变量" >&2
  exit 2
fi

# 先检查所有参数，再创建候选目录或调用构建/SSH；错误只报告变量名。
for NAME in KANTV_RELEASE_SSH_HOST KANTV_RELEASE_DIR KANTV_NAS_HOSTNAME \
  KANTV_RELEASE_APPLICATION_ID KANTV_RELEASE_CERT_SHA256 \
  KANTV_RELEASE_KEYSTORE_FILE KANTV_RELEASE_KEYSTORE_PASSWORD \
  KANTV_RELEASE_KEY_ALIAS KANTV_RELEASE_KEY_PASSWORD; do
  if [ -z "${!NAME:-}" ]; then
    echo "缺少必要配置：$NAME" >&2
    exit 2
  fi
done
if [[ ! "$KANTV_RELEASE_CERT_SHA256" =~ ^[[:xdigit:]]{64}$ ]]; then
  echo "KANTV_RELEASE_CERT_SHA256 必须为可信签名证书的 64 位十六进制 SHA-256" >&2
  exit 2
fi
if [[ "$KANTV_RELEASE_DIR" != /* ]] || [[ "$KANTV_RELEASE_SSH_HOST" = -* ]]; then
  echo "KANTV_RELEASE_DIR 必须为远端绝对路径，SSH 目标不得以 - 开头" >&2
  exit 2
fi
if [ ! -f "$KANTV_RELEASE_KEYSTORE_FILE" ]; then
  echo "KANTV_RELEASE_KEYSTORE_FILE 必须指向调用者的现有签名文件" >&2
  exit 2
fi
if { [ -n "${KANTV_UPDATE_ENDPOINT:-}" ] && [ -z "${KANTV_UPDATE_TOKEN:-}" ]; } || \
   { [ -z "${KANTV_UPDATE_ENDPOINT:-}" ] && [ -n "${KANTV_UPDATE_TOKEN:-}" ]; }; then
  echo "KANTV_UPDATE_ENDPOINT 与 KANTV_UPDATE_TOKEN 必须成对设置或同时留空" >&2
  exit 2
fi
if { [ -n "${KANTV_HUB_ENDPOINT:-}" ] && [ -z "${KANTV_HUB_FEEDBACK_TOKEN:-}" ]; } || \
   { [ -z "${KANTV_HUB_ENDPOINT:-}" ] && [ -n "${KANTV_HUB_FEEDBACK_TOKEN:-}" ]; }; then
  echo "KANTV_HUB_ENDPOINT 与 KANTV_HUB_FEEDBACK_TOKEN 必须成对设置或同时留空" >&2
  exit 2
fi
export KANTV_RELEASE_KEYSTORE_FILE KANTV_RELEASE_KEYSTORE_PASSWORD
export KANTV_RELEASE_KEY_ALIAS KANTV_RELEASE_KEY_PASSWORD
export KANTV_UPDATE_ENDPOINT KANTV_UPDATE_TOKEN KANTV_HUB_ENDPOINT KANTV_HUB_FEEDBACK_TOKEN
cd "$(dirname "$0")/../.."

mkdir -p app/build/outputs/release-staging
STAGING=$(mktemp -d app/build/outputs/release-staging/candidate.XXXXXXXX)
# 保留每次候选和回滚证据，不清空其他构建或稳定基线。
echo "== [1/4] 构建双 ABI 正式包 =="
python3 tools/lan_release/build_dual_abi.py --out-dir "$STAGING/build"
echo "== [2/4] pack-dual 生成双通道清单 =="
python3 tools/lan_release/lan_release.py pack-dual \
  --arm64-apk "$STAGING/build/app-release-arm64-v8a.apk" \
  --v7a-apk "$STAGING/build/app-release-armeabi-v7a.apk" \
  --release-dir "$STAGING"
echo "== [3/4] verify-dual 发布前自检 =="
python3 tools/lan_release/lan_release.py verify-dual \
  --release-dir "$STAGING" \
  --expect-application-id "$KANTV_RELEASE_APPLICATION_ID" \
  --expect-signing-cert-sha256 "$KANTV_RELEASE_CERT_SHA256"
echo "== [4/4] 准备候选（默认保留稳定入口） =="
python3 tools/lan_release/publish_dual.py \
  --staging "$STAGING" \
  --ssh-host "$KANTV_RELEASE_SSH_HOST" \
  --release-dir "$KANTV_RELEASE_DIR" \
  --expect-nas-hostname "$KANTV_NAS_HOSTNAME" \
  --expect-application-id "$KANTV_RELEASE_APPLICATION_ID" \
  --expect-signing-cert-sha256 "$KANTV_RELEASE_CERT_SHA256" \
  ${ACTIVATE:+"$ACTIVATE"}
echo "== 候选准备/受控切换完成；本地候选保留于 $STAGING =="
