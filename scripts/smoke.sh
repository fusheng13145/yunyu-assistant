#!/usr/bin/env bash
# ============================================================
# 云谕助手 —— 接口冒烟（v2.30）
#
# 用途：对"已经跑起来"的实例打一遍关键 HTTP 链路。单测只能证明方法行为，
#       拦截器顺序、序列化、路由、限流与握手这些只有真进程才暴露的问题靠这里。
#
# 边界（刻意为之）：
#   - 不碰任何消耗外部额度或不可逆的接口：/api/open/**、语音链路、检索测试、POST /api/admin/archive/run 均不调用。
#   - 写路径只写本次刚创建的数据，结尾删除（KEEP=1 可保留）。
#   - 不打印令牌：失败时只输出 HTTP 状态、业务 code 与 message 字段。
#
# 环境变量：
#   BASE            业务地址，默认 http://127.0.0.1:8080
#   MGMT_BASE       管理端口地址，默认同 BASE（MANAGEMENT_SERVER_PORT 独立时改为 http://127.0.0.1:9080）
#   SMOKE_USER      已存在的用户名；留空则注册一次性账号
#   SMOKE_PASS      配合 SMOKE_USER；留空则用随机口令
#   SMOKE_ADMIN_USER / SMOKE_ADMIN_PASS   提供时才跑第 8 节管理端只读检查
#   SMOKE_ORIGIN    正式部署的站点来源（如 https://yunyu.example.com）；用于校验 WS 跨域白名单
#   SMOKE_MODEL     创建助手使用的模型 id，默认 qwen-turbo
#   KEEP=1          保留本次创建的助手与会话
#   TIMEOUT         单请求超时秒数，默认 10
#
# 用法：
#   BASE=http://127.0.0.1:8081 scripts/smoke.sh
#   SMOKE_USER=demo SMOKE_PASS='demo-pass-1' scripts/smoke.sh
# 退出码：0=无失败（允许 SKIP）；1=有 FAIL；2=前置不满足（服务不可达 / 缺依赖 / 拿不到令牌）
# ============================================================
set -uo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
MGMT_BASE="${MGMT_BASE:-$BASE}"
TIMEOUT="${TIMEOUT:-10}"
KEEP="${KEEP:-0}"

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

PASS=0
FAIL=0
SKIP=0

ok()    { PASS=$((PASS+1));  printf '  [ OK ] %s\n' "$1"; }
bad()   { FAIL=$((FAIL+1));  printf '  [FAIL] %s — %s\n' "$1" "$2"; }
skip()  { SKIP=$((SKIP+1));  printf '  [SKIP] %s — %s\n' "$1" "$2"; }
section() { printf '\n%s\n' "$1"; }

command -v curl >/dev/null 2>&1 || { echo '缺少依赖：curl'; exit 2; }
# JSON 解析/拼装需要一个解释器：按 python3 → python → node 顺序探测，
# 这样脚本在开发机（Git Bash，只有 python / node）和裸 Linux 服务器（只有 python3）上都能直接跑。
if command -v python3 >/dev/null 2>&1; then FLAVOR=python; INTERP=python3
elif command -v python >/dev/null 2>&1; then FLAVOR=python; INTERP=python
elif command -v node >/dev/null 2>&1; then FLAVOR=node; INTERP=node
else echo '缺少依赖：python3 / python / node（JSON 解析与拼装都要用）'; exit 2; fi
printf '目标：业务 %s ｜ 管理 %s ｜ JSON 解释器 %s\n' "$BASE" "$MGMT_BASE" "$INTERP"

# json <k> <v> [<k> <v>...] → 安全的 JSON 请求体（纯数字值转 JSON 数字，其余按字符串）
if [ "$FLAVOR" = python ]; then
    json() { "$INTERP" -c 'import json,re,sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
a = sys.argv[1:]
out = {}
for k, v in zip(a[::2], a[1::2]):
    out[k] = int(v) if re.fullmatch(r"-?\d+", v) else (float(v) if re.fullmatch(r"-?\d+\.\d+", v) else v)
print(json.dumps(out, ensure_ascii=False))' "$@"; }
else
    json() { "$INTERP" -e 'const a=process.argv.slice(1),o={};
for(let i=0;i+1<a.length;i+=2){const v=a[i+1];
o[a[i]]=/^-?\d+$/.test(v)?Number(v):(/^-?\d+\.\d+$/.test(v)?Number(v):v);}
console.log(JSON.stringify(o));' "$@"; }
fi

# req <METHOD> <PATH> [TOKEN] [JSON_BODY] → STATUS / BODY
req() {
    local method="$1" path="$2" token="${3:-}" data="${4:-}"
    local args=(-sS --max-time "$TIMEOUT" -X "$method" -o "$BODY_FILE" -w '%{http_code}'
                -H 'Accept: application/json')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$data" ] && args+=(-H 'Content-Type: application/json' -d "$data")
    STATUS="$(curl "${args[@]}" "$BASE$path" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)" || BODY=""
}

mgmt_req() {
    STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' "$MGMT_BASE$1" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)" || BODY=""
}

# jget <dotted.path>：读最后一次响应里的字段（数组用数字下标）；缺失/null/False 一律输出空串
if [ "$FLAVOR" = python ]; then
    jget() { "$INTERP" -c 'import json,sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
try:
    node = json.load(open(sys.argv[2], encoding="utf-8"))
except Exception:
    node = None
for key in sys.argv[1].split("."):
    try:
        node = node[int(key)] if isinstance(node, list) else node.get(key)
    except (ValueError, IndexError, AttributeError, TypeError):
        node = None
        break
if node is None or node is False or node == "":
    print("")
else:
    print(node if isinstance(node, str) else json.dumps(node, ensure_ascii=False))' "$1" "$BODY_FILE"; }
else
    jget() { "$INTERP" -e 'const fs=require("fs");let d;
try{d=JSON.parse(fs.readFileSync(process.argv[2],"utf8"));}catch(e){d=null;}
for(const k of process.argv[1].split(".")){
  try{d=Array.isArray(d)?d[Number(k)]:(d&&typeof d==="object"?d[k]:undefined);}catch(e){d=undefined;break;}
}
if(d===undefined||d===null||d===false||d===""){console.log("");}
else{console.log(typeof d==="string"?d:JSON.stringify(d));}' "$1" "$BODY_FILE"; }
fi

detail() {
    local msg
    msg="$(jget message)"
    [ -z "$msg" ] && msg="$(printf '%s' "$BODY" | head -c 100)"
    printf 'HTTP %s / code=%s / %s' "$STATUS" "$(jget code)" "$msg"
}

# api_ok <描述>：HTTP 200 且业务 code==200；命中登录类限流记为 SKIP（属正常保护，非故障）
api_ok() {
    if [ "$STATUS" = "429" ]; then skip "$1" '触发限流（登录/注册 5 次/分钟），稍后重试'; return 1; fi
    if [ "$STATUS" != "200" ]; then bad "$1" "$(detail)"; return 1; fi
    if [ "$(jget code)" != "200" ]; then bad "$1" "$(detail)"; return 1; fi
    ok "$1"
}

# want_status <描述> <期望HTTP> <期望业务code，- 表示不校验>
want_status() {
    if [ "$STATUS" != "$2" ]; then bad "$1" "期望 HTTP $2，实际 $(detail)"; return 1; fi
    if [ "$3" != "-" ] && [ "$(jget code)" != "$3" ]; then bad "$1" "期望 code $3，实际 $(detail)"; return 1; fi
    ok "$1"
}

mgmt_req '/actuator/health'
if [ "$STATUS" = "000" ]; then
    echo "服务不可达：$MGMT_BASE/actuator/health —— 后端没启动，还是端口/地址不对？"
    exit 2
fi

TOKEN=""
REFRESH_TOKEN=""
USER_NAME=""
ASSISTANT_ID=""
SESSION_ID=""

# ---------- 1. 运维探针与对外暴露面 ----------
section '1. 运维探针与暴露面（actuator）'
if [ "$STATUS" = "200" ] && [ "$(jget status)" = "UP" ]; then
    ok 'GET /actuator/health → UP（DB / Redis 指示器全绿）'
else
    # 明细被 HEALTH_SHOW_DETAILS=never 隐藏（公网实例不应把依赖地址外泄），故只指路不看值
    bad 'GET /actuator/health' "$(detail)；哪个依赖红了看启动日志，不用改 show-details"
fi
mgmt_req '/actuator/metrics'
if [ "$STATUS" = "200" ]; then ok 'GET /actuator/metrics 已暴露（容量与告警取数点）'; else bad 'GET /actuator/metrics' "$(detail)"; fi
mgmt_req '/actuator/env'
if [ "$STATUS" = "404" ]; then ok 'GET /actuator/env 未暴露（404：配置值与凭据不通过接口外泄）'
else bad 'GET /actuator/env 应 404' "实际 HTTP $STATUS —— 检查 management.endpoints.web.exposure.include"; fi
mgmt_req '/actuator/mappings'
if [ "$STATUS" = "404" ]; then ok 'GET /actuator/mappings 未暴露（404：路由表不外泄）'
else bad 'GET /actuator/mappings 应 404' "实际 HTTP $STATUS"; fi
# 未匹配路径必须是 404，不能被全局兜底处理器降级成 500（v2.31 真实首跑发现：
# Spring 6 的 NoResourceFoundException 落进 Exception.class 兜底 ⇒ 浏览器每次请求
# /favicon.ico 都写一条带栈 ERROR 日志、并被计入服务端错误率）
req GET '/smoke-not-a-real-path'
if [ "$STATUS" = "404" ] && [ "$(jget code)" = "404" ]; then ok '未匹配路径 → 404（HTTP 与业务 code 一致，未被降级成 500）'
else bad '未匹配路径应 404' "$(detail)"; fi
# 独立管理端口（v2.30 MANAGEMENT_SERVER_PORT）分离断言：分离后业务端口不应再挂 actuator。
# 注意管理端口的监听地址不继承 server.address，须同时配 MANAGEMENT_SERVER_ADDRESS（见手册 5.7 第⑤条）
if [ "$MGMT_BASE" != "$BASE" ]; then
    req GET /actuator/health
    if [ "$STATUS" = "404" ]; then ok '业务端口取不到 /actuator（管理端口已分离）'
    else bad '业务端口应取不到 /actuator' "实际 HTTP $STATUS —— 已设 MANAGEMENT_SERVER_PORT 但业务端口仍在暴露"; fi
else
    skip '业务端口 /actuator 分离断言' 'MGMT_BASE 与 BASE 相同＝未启用独立管理端口，靠反代 deny 兜底（见 5.7）'
fi

# ---------- 2. 认证与鉴权链路 ----------
section '2. 认证与鉴权'
req GET /api/assistants
want_status '无令牌访问业务接口 → 401' 401 401
req GET /api/assistants 'not-a-jwt'
want_status '乱码令牌 → 401' 401 401

if [ -n "${SMOKE_USER:-}" ]; then
    USER_NAME="$SMOKE_USER"
    req POST /api/auth/login '' "$(json username "$SMOKE_USER" password "${SMOKE_PASS:-}")"
    if api_ok "POST /api/auth/login（复用账号 $SMOKE_USER）"; then
        TOKEN="$(jget data.token)"; REFRESH_TOKEN="$(jget data.refreshToken)"
    fi
else
    USER_NAME="smoke-$(date +%m%d%H%M%S)-$$"
    req POST /api/auth/register '' "$(json username "$USER_NAME" password "Sm$RANDOM$RANDOM-x1")"
    if api_ok "POST /api/auth/register（一次性账号 $USER_NAME）"; then
        TOKEN="$(jget data.token)"; REFRESH_TOKEN="$(jget data.refreshToken)"
    else
        echo "注册失败，后续检查无法继续：$(detail)"
        echo "  429 ⇒ 限流按 IP 计 5 次/分钟，等 1 分钟再跑"
        echo "  400/请求处理失败 ⇒ 多半是数据库连不上或凭据不对（看 /actuator/health 与启动日志的 Access denied / Communications link failure）"
        exit 2
    fi
fi
[ -n "$TOKEN" ] || { echo '未取得访问令牌，终止。'; exit 2; }

req GET /api/auth/me "$TOKEN"
if api_ok 'GET /api/auth/me'; then
    [ "$(jget data.username)" = "$USER_NAME" ] \
        && ok 'me 的用户名与登录账号一致（身份未错发）' \
        || bad 'me 用户名不一致' "期望 $USER_NAME，实际 $(jget data.username)"
    [ -z "$(jget data.password)" ] && ok 'me 不回传密码字段' || bad 'me 泄漏密码字段' 'data.password 非空'
fi

if [ -n "$REFRESH_TOKEN" ]; then
    OLD_REFRESH="$REFRESH_TOKEN"
    req POST /api/auth/refresh '' "$(json refreshToken "$OLD_REFRESH")"
    if api_ok 'POST /api/auth/refresh（换发新令牌）'; then
        NEW_TOKEN="$(jget data.token)"
        REFRESH_TOKEN="$(jget data.refreshToken)"
        [ "$NEW_TOKEN" != "$TOKEN" ] && ok '刷新做了轮换（新 access 与旧 access 不同）' \
            || bad '刷新未轮换' '返回的 token 与旧值相同'
        [ -n "$(jget data.role)" ] && ok '刷新响应带 role（前端账号态依赖）' \
            || bad '刷新响应缺少 role' "$(detail)"
        TOKEN="$NEW_TOKEN"
    fi
    req POST /api/auth/refresh '' "$(json refreshToken "$OLD_REFRESH")"
    want_status '旧 refresh 重放被拒（黑名单生效）' 200 400
fi

req GET /api/admin/overview "$TOKEN"
want_status '普通账号访问管理端 → 403' 403 403

# alg=none 的"自造签名"令牌必须被拦截器挡住（jjwt 校验签名/算法 → validateToken false）
b64url() { printf '%s' "$1" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '='; }
FORGED="$(b64url '{"alg":"none","typ":"JWT"}').$(b64url "{\"sub\":\"00000000-0000-0000-0000-000000000000\",\"exp\":$(( $(date +%s) + 3600 ))}")."
req GET /api/assistants "$FORGED"
want_status 'alg=none 自造令牌 → 401' 401 401

# ---------- 3. 助手 CRUD（写后读一致） ----------
section '3. 助手 CRUD'
req POST /api/assistants "$TOKEN" "$(json name "冒烟助手 $(date +%H%M%S)" description 'scripts/smoke.sh 创建，跑完即删' \
    personality '你是一个用于部署自检的助手。' modelName "${SMOKE_MODEL:-qwen-turbo}" \
    temperature 0.7 maxTokens 1024 voice 'Cherry' tools '[]')"
if api_ok 'POST /api/assistants（创建）'; then
    ASSISTANT_ID="$(jget data.id)"
    if [ -n "$ASSISTANT_ID" ]; then
        ok "助手已落库（id=${ASSISTANT_ID:0:8}…）"
        [ -n "$(jget data.userId)" ] && ok '创建时归属 userId 由服务端回填（不信任请求体）' \
            || bad '创建未回填 userId' "$(detail)"
    else
        bad '创建未返回 id' "$(detail)"; ASSISTANT_ID=""
    fi
fi

if [ -n "$ASSISTANT_ID" ]; then
    req GET "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    api_ok 'GET /api/assistants/{id}（本人可读）'
    req GET '/api/assistants?page=1&pageSize=5' "$TOKEN"
    api_ok 'GET /api/assistants（列表）'
    req GET '/api/assistants/page?page=1&pageSize=5&keyword=冒烟' "$TOKEN"
    api_ok 'GET /api/assistants/page（分页 + 关键词）'
    req PUT /api/assistants "$TOKEN" "$(json id "$ASSISTANT_ID" name '冒烟助手-改名')"
    api_ok 'PUT /api/assistants（改名）'
    req GET "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    [ "$(jget data.name)" = '冒烟助手-改名' ] && ok '写后读一致' || bad '写后读不一致' "实际 $(jget data.name)"
else
    skip '助手读写链路' '未创建出助手（配额 403？见上一条）'
fi

# ---------- 4. 会话与历史 ----------
section '4. 会话与消息'
if [ -n "$ASSISTANT_ID" ]; then
    req POST /api/sessions "$TOKEN" "$(json assistantId "$ASSISTANT_ID" title '冒烟会话')"
    api_ok 'POST /api/sessions（创建）' && SESSION_ID="$(jget data.id)"
else
    skip 'POST /api/sessions' '无可用助手'
fi

if [ -n "$SESSION_ID" ]; then
    req GET /api/sessions "$TOKEN"
    api_ok 'GET /api/sessions（列表）'
    req PUT "/api/sessions/$SESSION_ID" "$TOKEN" '{"title":"冒烟会话-改名","isPinned":true}'
    api_ok 'PUT /api/sessions/{id}（标题 + 置顶）'
    req GET "/api/sessions/$SESSION_ID/messages?page=1&pageSize=10" "$TOKEN"
    if api_ok 'GET /api/sessions/{id}/messages（空会话的分页结构）'; then
        [ -n "$(jget data.total)" ] && ok '消息分页返回 total' || bad '消息分页缺少 total' "$(detail)"
    fi
    if [ "$KEEP" = "1" ]; then skip 'DELETE /api/sessions/{id}' 'KEEP=1'
    else req DELETE "/api/sessions/$SESSION_ID" "$TOKEN"; api_ok 'DELETE /api/sessions/{id}'; fi
else
    skip '会话读写链路' '未创建出会话'
fi

# ---------- 5. 字典 / 配额 / 统计 ----------
section '5. 字典、配额与统计'
for p in /api/models /api/voices /api/tools /api/knowledges /api/orgs /api/openapi/apps /api/call-records '/api/stats/usage?range=day'; do
    req GET "$p" "$TOKEN"
    api_ok "GET $p" || true
done
req GET /api/billing/usage "$TOKEN"
if api_ok 'GET /api/billing/usage（配额 + 用量聚合）'; then
    for k in quota current remaining; do
        [ -n "$(jget "data.$k")" ] && ok "billing 含 $k 段" || bad "billing 缺少 $k 段" "$(detail)"
    done
    [ -n "$(jget data.quota.assistantLimit)" ] && ok 'billing 的配额四项非 NULL（可直填前端表单）' \
        || bad 'billing 配额存在 NULL 维度' "$(detail)"
fi

# ---------- 6. 外部依赖只探测，不消耗额度 ----------
section '6. 外部依赖配置探测'
req GET /api/ragflow/config "$TOKEN"
if [ "$STATUS" = "200" ] && [ "$(jget code)" = "200" ]; then ok 'GET /api/ragflow/config（RAGFlow 已配置）'
else skip 'GET /api/ragflow/config' "HTTP $STATUS $(jget message)（未配置 RAGFlow 属预期）"; fi
req GET /api/webrtc/config "$TOKEN"
if [ "$STATUS" = "200" ] && [ "$(jget code)" = "200" ]; then ok 'GET /api/webrtc/config（语音服务商凭据已配置）'
else skip 'GET /api/webrtc/config' "HTTP $STATUS $(jget message)（未配置语音服务商属预期）"; fi

# ---------- 7. WebSocket 握手 ----------
section '7. WebSocket 握手（只验升级链路，不做对话）'
WS_BASE="${BASE/http/ws}"
# 16 字节随机数 → base64（WebSocket 握手用），只用 coreutils，不依赖解释器
b64rand() { head -c 16 /dev/urandom 2>/dev/null | base64 | tr -d '\n'; }
ws_handshake() {  # ws_handshake <path> [origin] → 响应状态行
    local key args=(-sS -i --http1.1 --max-time 5 -o -
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13')
    key="$(b64rand)"
    [ -n "$key" ] && args+=(-H "Sec-WebSocket-Key: $key")
    [ -n "${2:-}" ] && args+=(-H "Origin: $2")
    curl "${args[@]}" "$WS_BASE$1" 2>/dev/null | head -n 1
}
WS_LINE="$(ws_handshake '/ws/smoke')"
case "$WS_LINE" in
    *101*) ok 'GET /ws/{assistantId} 握手 101（无 Origin 时放行，令牌走首条消息认证）' ;;
    '')    skip 'WS /ws/*' '握手无响应（代理/服务器不支持该探测）' ;;
    *)     bad 'WS /ws/* 握手失败' "$WS_LINE —— 期望 101；404 说明路由未注册" ;;
esac
if [ -n "${SMOKE_ORIGIN:-}" ]; then
    WS_LINE="$(ws_handshake '/ws/smoke' "$SMOKE_ORIGIN")"
    case "$WS_LINE" in
        *101*) ok "带站点 Origin（$SMOKE_ORIGIN）握手通过" ;;
        *403*) bad '带站点 Origin 握手被拒 403' "CORS_ALLOWED_ORIGINS（app.cors.allowed-origins）未含 $SMOKE_ORIGIN —— 浏览器 WS 握手必带 Origin，聊天与语音都会断" ;;
        *)     skip '带站点 Origin 握手' "${WS_LINE:-无响应}" ;;
    esac
else
    skip '带站点 Origin 握手' '未提供 SMOKE_ORIGIN；正式部署必须带，否则 CORS_ALLOWED_ORIGINS 漏配无人发现'
fi

# ---------- 8. 管理端只读 ----------
if [ -n "${SMOKE_ADMIN_USER:-}" ]; then
    section '8. 管理端只读检查'
    ADMIN_TOKEN=""
    req POST /api/auth/login '' "$(json username "$SMOKE_ADMIN_USER" password "${SMOKE_ADMIN_PASS:-}")"
    if api_ok "POST /api/auth/login（管理员 $SMOKE_ADMIN_USER）"; then
        ADMIN_TOKEN="$(jget data.token)"
        [ "$(jget data.role)" = 'admin' ] && ok '登录响应 role=admin' || bad '管理员账号 role 非 admin' "$(jget data.role)"
    fi
    if [ -n "$ADMIN_TOKEN" ]; then
        for p in /api/admin/overview '/api/admin/users?page=1&pageSize=1' '/api/admin/audit-logs?page=1&pageSize=1' \
                 /api/admin/quotas /api/admin/quotas/defaults /api/admin/archive/overview; do
            req GET "$p" "$ADMIN_TOKEN"
            api_ok "GET $p" || true
        done
        req GET /api/admin/quotas/defaults "$ADMIN_TOKEN"
        [ -n "$(jget data.assistantLimit)" ] && ok '兜底配额可直接注入管理页表单' || bad '兜底配额缺少 assistantLimit' "$(detail)"
        req GET /api/admin/users "$ADMIN_TOKEN"
        [ -z "$(jget data.list.0.password)" ] && ok '用户列表已脱敏（password 不外泄）' || bad '用户列表泄漏密码字段' 'data.list[0].password 非空'
        skip 'POST /api/admin/archive/run' '归档会物理删除源表数据，冒烟不触发'
    else
        skip '管理端接口' '管理员登录失败'
    fi
else
    section '8. 管理端只读检查（跳过）'
    skip '管理端接口' '未提供 SMOKE_ADMIN_USER / SMOKE_ADMIN_PASS'
fi

# ---------- 9. 登出与服务端失效 ----------
section '9. 登出与令牌失效'
if [ "$KEEP" = "1" ]; then
    skip 'DELETE /api/assistants/{id}' 'KEEP=1'
elif [ -n "$ASSISTANT_ID" ]; then
    req DELETE "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    api_ok 'DELETE /api/assistants/{id}'
else
    skip 'DELETE /api/assistants/{id}' '无助手'
fi
req POST /api/auth/logout "$TOKEN" "$(json refreshToken "$REFRESH_TOKEN")"
api_ok 'POST /api/auth/logout'
req GET /api/assistants "$TOKEN"
want_status '登出后原 access 令牌访问业务接口 → 401' 401 401
req GET /api/auth/me "$TOKEN"
want_status '登出后放行路径 /api/auth/me 同样失效（黑名单不只管拦截器）' 200 400

# ---------- 汇总 ----------
printf '\n----------------------------------------\n'
printf '冒烟结果：%d 项通过 ｜ %d 项失败 ｜ %d 项跳过\n' "$PASS" "$FAIL" "$SKIP"
printf '本次账号：%s ｜ 数据：%s\n' "$USER_NAME" \
    "$([ "$KEEP" = "1" ] && echo '保留（KEEP=1）' || echo '助手/会话已清理；一次性账号留在 users 表，需手工删除')"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
