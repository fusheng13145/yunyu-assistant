#!/usr/bin/env bash
# 本地开发凭据脚手架（v2.34）
# 目的：把"照抄 .env.example 起不来"变成"一条命令补齐可自动生成的项"。
# - .env 不存在时从 .env.example 复制；
# - JWT_SECRET 为空或仍是模板占位标记时，生成强随机值写入（不回显）；
# - 其余需真实账号/额度的项只列名字提醒人工填写，脚本不猜测、不打印值。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT/.env.example"
TARGET="$ROOT/.env"

if [ ! -f "$TEMPLATE" ]; then
  echo "[gen-dev-env] 缺少模板 $TEMPLATE" >&2
  exit 1
fi

if [ ! -f "$TARGET" ]; then
  cp "$TEMPLATE" "$TARGET"
  echo "[gen-dev-env] 已从 .env.example 创建 .env（模板副本，含占位标记）"
fi

jwt_value="$(sed -n 's/^JWT_SECRET=//p' "$TARGET" | tail -n1)"
if [ -z "$jwt_value" ] || [[ "$jwt_value" == *"[REQUIRED]"* ]]; then
  secret="$(openssl rand -base64 48 | tr -d '\n' | tr -d '/+=' | cut -c1-44)"
  tmp="$(mktemp)"
  awk -v s="$secret" '
    /^JWT_SECRET=/ { print "JWT_SECRET=" s; next }
    { print }
  ' "$TARGET" > "$tmp" && mv "$tmp" "$TARGET"
  echo "[gen-dev-env] JWT_SECRET 已生成随机值并写入 .env（长度 ${#secret} 字节，不回显）"
else
  echo "[gen-dev-env] JWT_SECRET 已是非占位值，跳过（不覆盖现有密钥）"
fi

remaining="$(awk -F= '/^[A-Z][A-Z0-9_]*=/ && /\[REQUIRED\]/ { printf "%s ", $1 }' "$TARGET" | sed 's/ $//; s/ /、/g')"
if [ -n "$remaining" ]; then
  echo "[gen-dev-env] 仍需人工填写真实值（未填时后端启动会被凭据守卫阻断并点名）："
  echo "            $remaining"
  echo "            语音项（ASR/TTS 腾讯云凭据）在网关侧配置，本地纯文本链路可留空。"
else
  echo "[gen-dev-env] .env 已无占位项。"
fi
