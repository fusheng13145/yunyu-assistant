#!/usr/bin/env bash
# ============================================================
# 云谕助手 —— 接口冒烟（v2.36）
#
# 用途：对"已经跑起来"的实例打一遍关键 HTTP 链路。单测只能证明方法行为，
#       拦截器顺序、序列化、路由、限流与握手这些只有真进程才暴露的问题靠这里。
#
# 前提（v2.36 真机收口后写死在这里）：请求体与 WebSocket 握手都**不经过 argv**——
#   Git Bash 下的 curl.exe 是原生程序，argv 里的非 ASCII 会被 MSYS 按本地代码页重编码，
#   中文到服务端就成了非法 UTF-8；而 curl 压根做不成 WS 握手（旧版 §7 因此永远 SKIP）。
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
HDR_FILE="$(mktemp)"
DATA_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$HDR_FILE" "$DATA_FILE"' EXIT

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

# json <k> <v> [<k> <v>...] → 安全的 JSON 请求体
#   纯整数/小数 → JSON 数字；以 [ 或 { 开头且能解析 → 原样嵌入的 JSON 结构（tools 这类数组字段，
#   传成字符串会被服务端按 ArrayList 反序列化拒成 400）；其余 → JSON 字符串（中文按 UTF-8 输出）
# urlenc <text> →  percent 编码，供 GET 查询参数使用
if [ "$FLAVOR" = python ]; then
    json() { "$INTERP" -c 'import json,re,sys
a = sys.argv[1:]
out = {}
for k, v in zip(a[::2], a[1::2]):
    if re.fullmatch(r"-?\d+", v): out[k] = int(v)
    elif re.fullmatch(r"-?\d+\.\d+", v): out[k] = float(v)
    elif v.startswith(("[", "{")):
        try: out[k] = json.loads(v)
        except Exception: out[k] = v
    else: out[k] = v
sys.stdout.buffer.write(json.dumps(out, ensure_ascii=False).encode("utf-8"))' "$@"; }
    urlenc() { "$INTERP" -c 'import sys,urllib.parse
sys.stdout.buffer.write(urllib.parse.quote(sys.argv[1], safe="").encode())' "$1"; }
else
    json() { "$INTERP" -e 'const a=process.argv.slice(1),o={};
for(let i=0;i+1<a.length;i+=2){const v=a[i+1];
let x=v;
if(/^-?\d+$/.test(v)||/^-?\d+\.\d+$/.test(v))x=Number(v);
else if(/^[[{]/.test(v)){try{x=JSON.parse(v)}catch(e){x=v}}
o[a[i]]=x;}
process.stdout.write(JSON.stringify(o));' "$@"; }
    urlenc() { "$INTERP" -e 'process.stdout.write(encodeURIComponent(process.argv[1]))' "$1"; }
fi

# send_body <JSON> → 置 DATA_FILE 为请求体文件（去掉行尾 CR）
send_body() { printf '%s' "${1%$'\r'}" > "$DATA_FILE"; }

# req <METHOD> <PATH> [TOKEN] [JSON_BODY] → STATUS / BODY
req() {
    local method="$1" path="$2" token="${3:-}" data="${4:-}"
    local args=(-sS --max-time "$TIMEOUT" -X "$method" -o "$BODY_FILE" -w '%{http_code}'
                -H 'Accept: application/json')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    if [ -n "$data" ]; then
        send_body "$data"
        args+=(-H 'Content-Type: application/json' --data-binary "@$DATA_FILE")
    fi
    STATUS="$(curl "${args[@]}" "$BASE$path" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)" || BODY=""
}

mgmt_req() {
    STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' "$MGMT_BASE$1" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)" || BODY=""
}

# req_ct <METHOD> <PATH> <TOKEN> <CONTENT_TYPE> <BODY> → STATUS / BODY / HDR
# 与 req 的差别只有 Content-Type：第 7.5 节要故意发错它，看服务端是否按 4xx 拒绝而不是 500
req_ct() {
    local method="$1" path="$2" token="${3:-}" ctype="${4:-}" data="${5:-}"
    local args=(-sS --max-time "$TIMEOUT" -X "$method" -o "$BODY_FILE" -D "$HDR_FILE" -w '%{http_code}')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$ctype" ] && args+=(-H "Content-Type: $ctype")
    if [ -n "$data" ]; then
        send_body "$data"
        args+=(--data-binary "@$DATA_FILE")
    fi
    STATUS="$(curl "${args[@]}" "$BASE$path" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)" || BODY=""
}

# 响应头取值（大小写不敏感）：hdr <名称>
hdr() { grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }

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
    if [ "$STATUS" = "429" ]; then skip "$1" '触发限流（AUTH 桶 5 次/分钟 或 EXPENSIVE 桶 30 次/分钟），稍后重试'; return 1; fi
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

# refresh 令牌默认 7 天有效（access 只有 app.jwt.expiration 那么长），若它能当会话凭据，
# 泄露 refresh 就等于泄露整个 API；前端的静默续期还会把这类误用悄悄掩盖掉
if [ -n "${REFRESH_TOKEN:-}" ]; then
    req GET /api/assistants "$REFRESH_TOKEN"
    want_status 'refresh 令牌不能当会话凭据用于 /api/**（v2.32）' 401 401
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
    temperature 0.7 maxTokens 1024 voice 'Cherry' tools '["get_weather"]')"
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
    # 中文写后读：请求体经文件下发、库是 utf8mb4、出口再过一次 Jackson，三处任一编码不对这里就花
    [ -n "$(jget data.name)" ] && [[ "$(jget data.name)" == 冒烟助手* ]] \
        && ok '中文名称原样读回（UTF-8 进出一致，未出现 mojibake）' \
        || bad '中文名称读回变形' "实际 $(jget data.name)"
    # tools 列是「JSON 字符串存一列、对外暴露数组」，读回应为数组而非字符串
    [ "$(jget data.tools.0)" = 'get_weather' ] && ok 'tools 以数组形状读回（JSON 列的双向转换生效）' \
        || bad 'tools 未以数组读回' "实际 $(jget data.tools)"
    req GET '/api/assistants?page=1&pageSize=5' "$TOKEN"
    api_ok 'GET /api/assistants（列表）'
    req GET "/api/assistants/page?page=1&pageSize=5&keyword=$(urlenc '冒烟')" "$TOKEN"
    api_ok 'GET /api/assistants/page（分页 + 中文关键词，percent 编码）'
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
# v2.36 真机实况：旧写法只看"HTTP 200 且 code 200"就记 OK，而未配置时这两个接口**照样回 200**
# （webrtc 回 iceServers:[]，ragflow 回 endpoint:""），于是首跑就出现"语音服务商凭据已配置"的假绿。
# 改为按响应体里的配置字段判定，可达性与配置状态分成两条结论。
section '6. 外部依赖配置探测'
cfg_probe() {  # cfg_probe <路径> <配置字段> <未配置时的说明>
    req GET "$1" "$TOKEN"
    if [ "$STATUS" != "200" ] || [ "$(jget code)" != "200" ]; then
        skip "$1 配置探测" "HTTP $STATUS $(jget message)（接口未开放或未配置属预期）"; return
    fi
    if [ -n "$(jget "$2")" ]; then ok "$1 可达且已配置（$2=$(jget "$2")）"
    else skip "$1 配置状态" "接口可达但 $2 为空 ⇒ $3"; fi
}
cfg_probe /api/ragflow/config data.endpoint '知识库检索未接入（配 RAGFLOW_ENDPOINT / RAGFLOW_API_KEY）'
cfg_probe /api/webrtc/config data.iceServers.0.urls '语音通话回退公共 STUN，TURN 转发未配（配 WEBRTC_ICE_SERVERS）；语音不进 MVP，属预期'

# ---------- 7. WebSocket 握手 ----------
# v2.36 实况：旧写法用 curl 发 Upgrade 头，真机上永远拿不到响应（curl 不做 WS 握手，
# 连接被服务端按普通 HTTP 处理）⇒ 本节此前**从未真正验证过任何东西**，只是一直 SKIP。
# 改为直接用解释器开 TCP 套接字手写握手请求：只读响应状态行，收完即断，不留会话。
section '7. WebSocket 握手（只验升级链路，不做对话）'
WS_HOSTPORT="${BASE#*://}"
WS_HOST="${WS_HOSTPORT%%:*}"
WS_PORT="${WS_HOSTPORT#*:}"; [ "$WS_PORT" = "$WS_HOSTPORT" ] && WS_PORT=80
# ws_handshake <path 不含前导斜杠> [origin] → 响应状态行（失败则空）
# 路径不带前导 '/' 是必须的：Git Bash 会把形如 /ws/x 的参数按 POSIX 路径改写
# （变成 C:/Program Files/Git/ws/x），握手行就成了非法请求目标 ⇒ 400。斜杠在解释器里补回。
ws_handshake() {
    if [ "$FLAVOR" = python ]; then
        "$INTERP" -c 'import socket,base64,os,sys
host, port, path, origin = sys.argv[1], int(sys.argv[2]), "/" + sys.argv[3], sys.argv[4]
try:
    s = socket.create_connection((host, port), timeout=5)
except Exception:
    sys.exit(0)
req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
       "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: %s\r\n" % (path, host, port, base64.b64encode(os.urandom(16)).decode()))
if origin:
    req += "Origin: %s\r\n" % origin
s.sendall((req + "\r\n").encode())
data = s.recv(2048)
s.close()
print(data.split(b"\r\n", 1)[0].decode("latin-1"))' "$WS_HOST" "$WS_PORT" "$1" "${2:-}"
    else
        "$INTERP" -e 'const net=require("net"),crypto=require("crypto");
const [host,port,rawPath,origin]=process.argv.slice(1);
const path="/"+rawPath;
let done=false;
const s=net.connect(Number(port),host);
s.setTimeout(5000);
s.on("data",d=>{if(done)return;done=true;console.log(d.toString("latin1").split("\r\n")[0]);s.destroy();});
s.on("error",()=>{});s.on("timeout",()=>s.destroy());
s.once("connect",()=>s.write(`GET ${path} HTTP/1.1\r\nHost: ${host}:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ${crypto.randomBytes(16).toString("base64")}\r\n${origin?`Origin: ${origin}\r\n`:""}\r\n`));' "$WS_HOST" "$WS_PORT" "$1" "${2:-}"
    fi
}
# ws_assert <描述> <path> <origin> <期望码> <不符时的说明>
ws_assert() {
    local line; line="$(ws_handshake "$2" "$3")"
    case "$line" in
        *"$4"*) ok "$1 —— HTTP${line#HTTP}" ;;
        '')     skip "$1" '握手无响应（端口不通 / 代理不支持该探测）' ;;
        *)      bad "$1" "期望 $4，实际 ${line:-无响应} —— $5" ;;
    esac
}
ws_assert '聊天路由 /ws/{assistantId} 握手' 'ws/smoke' '' 101 '404 ⇒ 路由未注册；无 Origin 时放行，令牌走首条消息认证'
ws_assert '语音信令路由 /ws-voice/{id} 握手' 'ws-voice/smoke' '' 101 '404 ⇒ 语音路由未注册（第二阶段做语音时这条是链路前提）'
if [ -n "${SMOKE_ORIGIN:-}" ]; then
    ws_assert "带站点 Origin（$SMOKE_ORIGIN）握手" 'ws/smoke' "$SMOKE_ORIGIN" 101 \
        "403 ⇒ CORS_ALLOWED_ORIGINS（app.cors.allowed-origins）未含 $SMOKE_ORIGIN —— 浏览器 WS 握手必带 Origin，聊天与语音都会断"
    # 反向断言：白名单必须真的在拒。只验放行等于把"配置成 * 或全放行"也判成通过。
    ws_assert '白名单外 Origin 握手被拒' 'ws/smoke' 'https://smoke-not-allowed.invalid' 403 \
        "101 ⇒ 来源白名单未生效（可能被改成通配），跨站页面可直接连 WS"
else
    skip '带站点 Origin 握手' '未提供 SMOKE_ORIGIN；正式部署必须带，否则 CORS_ALLOWED_ORIGINS 漏配无人发现'
fi

# ---------- 7.5 请求形状错误：客户端用错不能记成服务端故障 ----------
# v2.32 实测背景：修前这几类请求全落进 GlobalExceptionHandler 的 Exception 兜底 ⇒ 500 + 带栈 ERROR 日志，
# 于是 404/405 级别的流量被计入服务端错误率，爬虫每探测一次就多刷一条日志。
# 405 由 DispatcherServlet 在 handler mapping 阶段抛出，早于鉴权拦截器与数据库，四项里只有它不受库是否可用影响。
section '7.5 请求形状错误（客户端用错不该变成服务端 500）'
req_ct DELETE /api/models '' ''
if [ "$STATUS" = "405" ]; then
    if printf '%s' "$(hdr Allow)" | grep -qi 'GET'; then ok '未支持的方法 → 405，且带 Allow 头（RFC 9110 要求）'
    else bad '405 未带 Allow 头' "$(detail)"; fi
else
    want_status '未支持的方法 → 405' 405 -
fi
req_ct POST /api/assistants "$TOKEN" 'text/plain' '{"name":'
want_status '给 JSON 接口发 text/plain → 415' 415 415
req POST /api/assistants "$TOKEN" '{"name":'
case "$STATUS" in
    4*) ok "畸形 JSON → HTTP $STATUS（未炸成 500）" ;;
    *)  bad '畸形 JSON' "$(detail) ⇒ 缺 HttpMessageNotReadable 级别的处理，被兜底成 5xx" ;;
esac
# multipart 缺 file 部分：前端漏 append、或请求被代理截断时的真实形状
STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
          -H "Authorization: Bearer $TOKEN" -F 'note=without-file' \
          "$BASE/api/call-records/smoke-probe/recording" 2>/dev/null)" || STATUS="000"
BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
want_status '上传缺 file 字段 → 400 且点名缺哪个字段' 400 400
skip '上传超体积 → 413' '需要 54MB 真实上行流量，冒烟不做；已在 .scratch/probe_chain.sh 实测线上形状'

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

# ---------- 8.5 限流桶 — 高成本接口（EXPENSIVE 30 次/分钟） ----------
# v2.35 起 429 从 SKIP 变成断言：限流扩面后"能触发 429"本身就是被测行为。
# 打录音下载（路径变量 + 纯本地）而非检索测试：后者每次真调外部 RAGFlow，白烧额度。
# 拦截器在 preHandle 计数、认证在其后 ⇒ 404 同样计入桶，不必先造一条录音。
section '8.5 限流桶 — 高成本接口'
NON429=0
EXP_429=0
i=0
while [ "$i" -lt 31 ]; do
    i=$((i + 1))
    req GET '/api/call-records/smoke-rate-probe/recording' "$TOKEN"
    if [ "$STATUS" = "429" ]; then EXP_429=1; break; fi
    NON429=$((NON429 + 1))
done
if [ "$EXP_429" = "1" ]; then
    if [ "$NON429" -eq 0 ]; then
        skip 'EXPENSIVE 桶容量' '首个请求即 429 ⇒ 1 分钟窗口内已被上一轮打满，本轮无法判容量（等 60 秒重跑）'
    elif [ "$NON429" -ge 31 ]; then
        bad 'EXPENSIVE 桶容量应为 30/分钟' '放行 '"$NON429"' 次才见 429，超出容量'
    else
        ok "EXPENSIVE 桶生效：放行 $NON429 次后第 $((NON429 + 1)) 次返回 429"
    fi
else
    bad 'EXPENSIVE 桶未生效' '连续 31 次请求高成本接口未见 429（检查 RateLimitInterceptor 的 Tier 与 AppConfig 注册）'
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

# ---------- 10. 限流桶 — 认证（AUTH 5 次/分钟，login/refresh/password 共用） ----------
# 本区段故意打错误口令，跑完后 1 分钟内再冒烟会在 §2 登录处触发 429——
# api_ok 已把 429 记成 SKIP（保护生效，不算故障），无需处理。
# 放在 §9 之后：此时业务断言已全部做完，错误凭据不会再干扰前序区段。
section '10. 限流桶 — 认证'
AUTH_429=0
AUTH_SHAPE_CHECKED=0
LOGIN_TRIES=0
i=0
while [ "$i" -lt 8 ] && [ "$AUTH_429" = "0" ]; do
    i=$((i + 1))
    req POST /api/auth/login '' "$(json username 'smoke-no-such-user' password 'wrong-password-123')"
    LOGIN_TRIES=$((LOGIN_TRIES + 1))
    case "$STATUS" in
        429) AUTH_429=1 ;;
        # v2.36 真机实况：登录失败由 UserService 抛 RuntimeException，走全局兜底 ⇒ HTTP 400 + code 400，
        # 不是 401。本项目里 401 专指"会话凭据缺失/失效"（前端据此触发静默续期/跳登录），
        # 把凭据错误也返 401 会让登录页把自己判成"登录态过期"。断言按产品契约，不按 RFC。
        # 形状只记一次：循环打的是同一个不变响应，重复计数会把一项断言刷成四项。
        400) if [ "$AUTH_SHAPE_CHECKED" = "0" ]; then
                 AUTH_SHAPE_CHECKED=1
                 [ "$(jget message)" = '用户名或密码错误' ] \
                     && ok '错误口令 → HTTP 400 + 反枚举文案（不区分用户名/密码，避免用户名枚举）' \
                     || bad '错误口令响应文案漂移' "$(detail)"
             fi ;;
        *) bad '错误口令登录应 HTTP 400（项目兜底）' "$(detail)" ;;
    esac
done
if [ "$AUTH_429" = "1" ]; then
    if [ "$LOGIN_TRIES" -le 1 ]; then
        skip 'AUTH 桶容量' '首个请求即 429 ⇒ 1 分钟窗口内已被上一轮（或 §2/§8 的正常认证请求）打满，本轮无法判容量'
    elif [ "$LOGIN_TRIES" -ge 9 ]; then
        bad 'AUTH 桶容量应为 5/分钟' '放行 '"$LOGIN_TRIES"' 次才见 429，超出容量'
    else
        ok "AUTH 桶生效：$LOGIN_TRIES 次（含前序区段已用量）后返回 429"
    fi
else
    bad 'AUTH 桶未生效' '8 次错误口令登录未见 429（§8 管理员登录已消耗 1 次，5 次容量下第 5～8 次必触发）'
fi
# 共用同桶的直接证据：login 打满后，refresh 这条从未被请求过的路径也应 429。
# 若两桶独立，refresh 会走到业务层对乱码令牌报 401——429/401 的区分度就是本断言的价值。
# 仅在确认已见 429 后才断言（上一项 bad 时桶状态不可知，重复判同一件事只会有两条噪声）
if [ "$AUTH_429" = "1" ]; then
    req POST /api/auth/refresh '' "$(json refreshToken 'smoke-not-a-real-token')"
    want_status 'refresh 与 login 共用同桶（429 而非 401）' 429 -
else
    skip 'refresh 与 login 共用同桶' 'AUTH 桶未确认打满'
fi

# ---------- 汇总 ----------
printf '\n----------------------------------------\n'
printf '冒烟结果：%d 项通过 ｜ %d 项失败 ｜ %d 项跳过\n' "$PASS" "$FAIL" "$SKIP"
printf '本次账号：%s ｜ 数据：%s\n' "$USER_NAME" \
    "$([ "$KEEP" = "1" ] && echo '保留（KEEP=1）' || echo '助手/会话已清理；一次性账号留在 users 表，需手工删除')"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
