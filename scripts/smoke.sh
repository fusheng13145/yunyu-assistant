#!/usr/bin/env bash
# ============================================================
# 云谕助手 —— 接口冒烟（v2.46）
#
# 用途：对"已经跑起来"的实例打一遍关键 HTTP 链路。单测只能证明方法行为，
#       拦截器顺序、序列化、路由、限流与握手这些只有真进程才暴露的问题靠这里。
#
# 前提（v2.36 真机收口后写死在这里）：请求体与 WebSocket 握手都**不经过 argv**——
#   Git Bash 下的 curl.exe 是原生程序，argv 里的非 ASCII 会被 MSYS 按本地代码页重编码，
#   中文到服务端就成了非法 UTF-8；而 curl 压根做不成 WS 握手（旧版 §7 因此永远 SKIP）。
#
# 边界（刻意为之）：
#   - 不碰任何消耗外部额度或不可逆的接口。/api/open/** 的鉴权与握手只在 §7.9 打**负向**
#     （无 Key/错 Key 在拦截器内返回，不进业务、不计费）；§7.10 打外呼端点时故意用空 body，
#     让它停在业务侧参数校验（400）之前就扣不到配额、拨不出网关，只用来观测 403→400 的闸门跳变。
#     检索测试与 POST /api/admin/archive/run 不调用。
#   - 写路径只写本次刚创建的数据，结尾删除（KEEP=1 可保留）。
#   - 不打印令牌：失败时只输出 HTTP 状态、业务 code 与 message 字段。
#
# 环境变量：
#   BASE            业务地址，默认 http://127.0.0.1:8080
#   MGMT_BASE       管理端口地址，默认同 BASE（MANAGEMENT_SERVER_PORT 独立时改为 http://127.0.0.1:9080）
#   SMOKE_USER      已存在的用户名；留空则注册一次性账号
#   SMOKE_PASS      配合 SMOKE_USER；留空则用随机口令
#   SMOKE_ADMIN_USER / SMOKE_ADMIN_PASS   提供时才跑第 8 节管理端只读检查与 §7.10 的跨账号/审计断言
#   SMOKE_INVITE_CODE  邀请码注册模式（REGISTRATION_MODE=invite，默认）下自建一次性账号所需的码；
#                      未提供但有管理员凭据时，脚本会自己调 /api/admin/invite-codes 发一个并用掉
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

# 目标地址只有这两个变量：BASE / MGMT_BASE。SMOKE_ORIGIN 不改写目标，它只多加一条"站点 Origin 能否握手"的断言。
# 默认 8080 在本机是**别人的服务**（v2.64 踩过：只设 SMOKE_ORIGIN 的整轮打在 jobbuddy-app 上，全 401 却仍退出码 2），
# 自验证必须显式指到自己的端口，并按 §1 的第一行"目标：…"核对打到的是谁。
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
# -D 顺手留响应头：429 的 Retry-After 只有从这里能读到（req_auth 的 pacing 依赖它）
req() {
    local method="$1" path="$2" token="${3:-}" data="${4:-}"
    local args=(-sS --max-time "$TIMEOUT" -X "$method" -o "$BODY_FILE" -D "$HDR_FILE" -w '%{http_code}'
                -H 'Accept: application/json')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    if [ -n "$data" ]; then
        send_body "$data"
        args+=(-H 'Content-Type: application/json' --data-binary "@$DATA_FILE")
    fi
    STATUS="$(curl "${args[@]}" "$BASE$path" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)" || BODY=""
}

# req_auth <同 req 参数>：认证类请求（login/register/refresh 共用 AUTH 桶 5 次/分钟）
# AUTH 桶是 60 秒滑动窗口（RateLimitInterceptor 记时间戳队列），不是固定窗口 ⇒ 只能等最旧那次出窗。
# 冒烟在邀请码模式下要发 6～7 次认证请求，超出的会被 429 吞掉——被吞掉的断言等于没跑，
# 所以这里按服务端给的 Retry-After 等到腾出名额后重打一次（最多等 2 个窗口）。
req_auth() {
    req "$@"
    local tries=0 wait_s
    while [ "$STATUS" = "429" ] && [ "$tries" -lt 2 ]; do
        tries=$((tries + 1))
        wait_s="$(hdr Retry-After)"
        case "$wait_s" in (''|*[!0-9]*) wait_s=60 ;; esac
        printf '        …AUTH 桶已满，按 Retry-After 等 %ss 后重打（第 %s 次）\n' "$wait_s" "$tries"
        sleep "$((wait_s + 1))"
        req "$@"
    done
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

# want_param <描述> <原因子串>：写侧拒绝的真实形状＝HTTP 200 + 业务码 400 + message 点名越界项。
# 只断言 HTTP 状态会永远绿（paramError 不改 HTTP 码，见手册 4.4）；只断言 code 会把"拒绝了但说不清为什么"
# 也判成通过，所以原因子串是这条判据的第二半。子串用 case 比对而不是 grep：
# Git Bash 下 grep.exe 的中文 argv 会被按本地代码页重编码，跟响应体（UTF-8）比不上。
want_param() {
    if [ "$STATUS" != "200" ]; then bad "$1" "期望 HTTP 200，实际 $(detail)"; return 1; fi
    if [ "$(jget code)" != "400" ]; then bad "$1" "期望业务码 400，实际 $(detail)"; return 1; fi
    local msg; msg="$(jget message)"
    case "$msg" in
        *"$2"*) ok "$1 — 原因：$msg" ;;
        *) bad "$1" "原因里没有「$2」— $msg" ;;
    esac
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

# 注册开放度（v2.37）：后面所有注册断言按这里的实况分支，不由脚本自己猜模式
INVITE_MODE=1
ADMIN_TOKEN=""
req GET /api/auth/register-config
if api_ok 'GET /api/auth/register-config（注册是否要求邀请码）'; then
    if printf '%s' "$BODY" | grep -q '"inviteRequired":true'; then
        INVITE_MODE=1
    elif printf '%s' "$BODY" | grep -q '"inviteRequired":false'; then
        INVITE_MODE=0
    else
        bad 'register-config 未返回 inviteRequired 布尔值' "$(detail)"
    fi
fi
printf '  注册模式：%s\n' "$([ "$INVITE_MODE" = 1 ] && echo '邀请码（invite）' || echo '开放（open）')"

if [ -n "${SMOKE_USER:-}" ]; then
    USER_NAME="$SMOKE_USER"
    req_auth POST /api/auth/login '' "$(json username "$SMOKE_USER" password "${SMOKE_PASS:-}")"
    if api_ok "POST /api/auth/login（复用账号 $SMOKE_USER）"; then
        TOKEN="$(jget data.token)"; REFRESH_TOKEN="$(jget data.refreshToken)"
    fi
else
    USER_NAME="smoke-$(date +%m%d%H%M%S)-$$"
    USER_PASS="Sm$RANDOM$RANDOM-x1"
    if [ "$INVITE_MODE" = "1" ]; then
        INVITE_CODE="${SMOKE_INVITE_CODE:-}"
        # 没预置码就自己发一个：发码走管理端（不计 AUTH 桶），且这个码会被下面的正常注册用掉，
        # 所以一次冒烟不会在台账里留下未使用行
        if [ -z "$INVITE_CODE" ] && [ -n "${SMOKE_ADMIN_USER:-}" ]; then
            req_auth POST /api/auth/login '' "$(json username "$SMOKE_ADMIN_USER" password "${SMOKE_ADMIN_PASS:-}")"
            if api_ok "POST /api/auth/login（管理员发码 $SMOKE_ADMIN_USER）"; then
                ADMIN_TOKEN="$(jget data.token)"
                req POST /api/admin/invite-codes "$ADMIN_TOKEN" "$(json count 1)"
                if api_ok 'POST /api/admin/invite-codes（本次冒烟自造 1 个码）'; then
                    INVITE_CODE="$(jget data.codes.0)"
                fi
            fi
        fi
        if [ -z "$INVITE_CODE" ]; then
            echo '邀请码模式下自建账号需要一个可用码：设 SMOKE_INVITE_CODE=<未使用的码>，'
            echo '或提供 SMOKE_ADMIN_USER / SMOKE_ADMIN_PASS 让脚本自己发码（库里第一个码见手册 5.10）。'
            exit 2
        fi
        # 闸门实况：缺码必须被拒；且拒绝发生在领取之前 —— 下一步同码注册成功即为"码没被烧掉"的证据
        req_auth POST /api/auth/register '' "$(json username "smoke-nocode-$$" password "$USER_PASS")"
        want_status '邀请码模式：缺码注册 → 400（同码随后仍可用 ⇒ 判定未被提前消费）' 400 400
        req_auth POST /api/auth/register '' "$(json username "$USER_NAME" password "$USER_PASS" inviteCode "$INVITE_CODE")"
    else
        req_auth POST /api/auth/register '' "$(json username "$USER_NAME" password "$USER_PASS")"
    fi
    if api_ok "POST /api/auth/register（一次性账号 $USER_NAME）"; then
        TOKEN="$(jget data.token)"; REFRESH_TOKEN="$(jget data.refreshToken)"
        if [ "$INVITE_MODE" = "1" ]; then
            # 一次性：注册成功后同码换人重放必须 400（used_by 已定，不退还不覆盖）
            req_auth POST /api/auth/register '' "$(json username "smoke-replay-$$" password "$USER_PASS" inviteCode "$INVITE_CODE")"
            want_status '同码重放注册 → 400（一码一号，消费不可逆）' 400 400
        fi
    else
        echo "注册失败，后续检查无法继续：$(detail)"
        echo "  429 ⇒ 限流按 IP 计 5 次/分钟，等 1 分钟再跑"
        echo "  400 + '邀请码无效或已被使用' ⇒ 给的 SMOKE_INVITE_CODE 已用过，换一个或改由脚本发码"
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
    # 判据自 v2.48 起从"值为空"改为"键不存在"：手写 setPassword(null) 也会给出 "password":null，
    # 而 jget 把 null 与缺失压成同一个空串，测不出这条差别（实体护栏的形态只有扫原文才看得见）
    printf '%s' "$BODY" | grep -qF '"password"' \
        && bad 'me 仍带 password 键（实体级抑制未生效）' "$(printf '%s' "$BODY" | head -c 120)" \
        || ok 'me 响应体无 password 键（抑制在实体上，不靠出口手写）'
fi

if [ -n "$REFRESH_TOKEN" ]; then
    OLD_REFRESH="$REFRESH_TOKEN"
    req_auth POST /api/auth/refresh '' "$(json refreshToken "$OLD_REFRESH")"
    if api_ok 'POST /api/auth/refresh（换发新令牌）'; then
        NEW_TOKEN="$(jget data.token)"
        REFRESH_TOKEN="$(jget data.refreshToken)"
        [ "$NEW_TOKEN" != "$TOKEN" ] && ok '刷新做了轮换（新 access 与旧 access 不同）' \
            || bad '刷新未轮换' '返回的 token 与旧值相同'
        [ -n "$(jget data.role)" ] && ok '刷新响应带 role（前端账号态依赖）' \
            || bad '刷新响应缺少 role' "$(detail)"
        TOKEN="$NEW_TOKEN"
    fi
    req_auth POST /api/auth/refresh '' "$(json refreshToken "$OLD_REFRESH")"
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

# ---------- 3.5 助手级成本参数越界拒绝（v2.54 · 候选 ㉔） ----------
# 配额按"条数"计量，而单条真实开销由助手配置决定：模型、最大输出 Token、每条消息都注入的人设提示词。
# 这里只打**写侧**（validateForWrite）：越界必须回业务码 400 并点名上限，且不产生任何写库动作。
# 读侧的回落/截断（runtime()）由单测覆盖：冒烟若要观测它就得真跑一次对话，那会真调外部模型、白烧额度。
# 四条负向都走 POST 且刻意只带被测字段：校验排在配额与归属解析之前，所以它们既不进库也不占助手配额。
section '3.5 助手成本参数越界拒绝（不进库）'
req POST /api/assistants "$TOKEN" "$(json name '越界-maxTokens' maxTokens 999999)"
want_param 'POST 最大输出 999999 → 拒绝并报出上限' '8192'
req POST /api/assistants "$TOKEN" "$(json name '越界-模型' modelName 'gpt-4-32k-forever')"
want_param 'POST 清单外模型名 → 拒绝并报出可选清单' '不在可用清单内'
req POST /api/assistants "$TOKEN" "$(json name '越界-温度' temperature 2.5)"
want_param 'POST 温度 2.5 → 拒绝并报出合法域' '2.0'
LONG_PERSONALITY="$(printf 'a%.0s' {1..5000})"
req POST /api/assistants "$TOKEN" "$(json name '越界-人设' personality "$LONG_PERSONALITY")"
want_param 'POST 人设 5000 字 → 拒绝并报出上限' '4000'
if [ -n "$ASSISTANT_ID" ]; then
    # 反向锚点：合法值必须原样落库。缺了它，"把一切都钳到默认值"的实现同样能让上面四条一直绿——
    # 而那才是用户真正会感知的故障（配置界面填了等于没填）。
    req PUT /api/assistants "$TOKEN" "$(json id "$ASSISTANT_ID" modelName 'deepseek-chat' temperature 1.5 maxTokens 4096)"
    api_ok 'PUT 合法成本参数（deepseek-chat / 1.5 / 4096）'
    req GET "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    [ "$(jget data.modelName)" = 'deepseek-chat' ] && ok '清单内模型未被改写' \
        || bad '合法模型名被钳制改写' "实际 $(jget data.modelName)"
    [ "$(jget data.maxTokens)" = '4096' ] && ok '合法 maxTokens 未被钳到上限或默认' \
        || bad '合法 maxTokens 被改写' "实际 $(jget data.maxTokens)"
    # 拒绝先于写库：越界 PUT 之后回读，库里得还是刚写进去的 4096，而不是 999999
    req PUT /api/assistants "$TOKEN" "$(json id "$ASSISTANT_ID" maxTokens 999999)"
    want_param 'PUT 越界 maxTokens → 拒绝（鉴权已过、校验仍先于写库）' '8192'
    req GET "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    [ "$(jget data.maxTokens)" = '4096' ] && ok '越界 PUT 未改动库内值' \
        || bad '越界 PUT 仍写进了库' "实际 $(jget data.maxTokens)"
else
    skip 'PUT 侧成本参数锚点' '未创建出助手（见第 3 节）'
fi

# ---------- 3.6 助手模型清空：缺字段＝不改 / 空串＝显式清空（v2.60 · 候选 ㊸） ----------
# MyBatis-Plus 的 updateById 跳过 null 列 ⇒ 界面选了"默认模型"却发 undefined 时，SQL 里根本没有 model_name 这一列。
# 两条锚点互为反向：先证明空串真的能清空（改前会把 "" 归成 null ⇒ 库内仍是 deepseek-chat）；
# 再证明"不带这一列"不会顺带清空（否则保存人设/音色就会把助手配置一起抹掉）。
section '3.6 助手模型清空（空串＝显式清空）'
if [ -n "$ASSISTANT_ID" ]; then
    req PUT /api/assistants "$TOKEN" "$(json id "$ASSISTANT_ID" modelName '')"
    api_ok 'PUT modelName="" → 接受（空串不是越界值）'
    req GET "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    [ -z "$(jget data.modelName)" ] && ok '清空生效：模型已不是 deepseek-chat' \
        || bad '选"默认模型"没清空库内模型' "实际 $(jget data.modelName)"
    # 空串与 null 都必须能分辨：""＝显式清空、null＝从未指定，折成一个态就退回 ㊸
    case "$BODY" in
        *'"modelName":""'*) ok '读回是空串形状（不是 null／不是缺键）' ;;
        *) bad '清空后读回不是空串' "$(printf '%s' "$BODY" | head -c 120)" ;;
    esac
    req PUT /api/assistants "$TOKEN" "$(json id "$ASSISTANT_ID" modelName 'deepseek-chat')"
    api_ok 'PUT 换回清单内模型 deepseek-chat'
    req PUT /api/assistants "$TOKEN" "$(json id "$ASSISTANT_ID" name '冒烟助手-只改名')"
    api_ok 'PUT 只改名（请求体不带 modelName）'
    req GET "/api/assistants/$ASSISTANT_ID" "$TOKEN"
    [ "$(jget data.modelName)" = 'deepseek-chat' ] && ok '只改名的 PUT 未清空库内模型' \
        || bad '只改名把模型一起清了（缺字段被当成清空）' "实际 $(jget data.modelName)"
else
    skip '助手模型清空锚点' '未创建出助手（见第 3 节）'
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
for p in /api/models /api/voices /api/tools /api/orgs /api/openapi/apps /api/call-records '/api/stats/usage?range=day'; do
    req GET "$p" "$TOKEN"
    api_ok "GET $p" || true
done

# v2.67 S-12 收口：用量统计的对外数字口径改了（只计已结算、消息数取自 records、并读归档表、可按组织）。
# 冒烟不自造通话行，所以这里只钉**形状、窗口与授权边界**：数值口径由 UsageStatsServiceTest 与
# 一次性真机差分背书，本节的职责是"端点别再悄悄回到旧形状、越权请求别再返回一份零用量报告"。
req GET '/api/stats/usage?range=day' "$TOKEN"
if api_ok 'GET /api/stats/usage?range=day（新口径的响应形状）'; then
    for k in range since scope callCount totalDurationSec messageCount archivedCalls excludedCalls days; do
        [ -n "$(jget "data.$k")" ] && ok "usage 含 $k" || bad "usage 缺少 $k" "$(detail)"
    done
    for k in failed ongoing undated unknown outsideWindow; do
        [ -n "$(jget "data.excludedCalls.$k")" ] && ok "usage 点名未计入的 $k" || bad "usage excludedCalls 缺少 $k" "$(detail)"
    done
    [ "$(jget data.scope)" = "self" ] && ok 'usage 默认作用域是 self' || bad 'usage 默认作用域异常' "实际 $(jget data.scope)"
    # 旧实现在这里外发一个恒为 null 的 orgId；摘掉它才能区分"没请求组织"与"请求了但读不到"
    [ -z "$(jget data.orgId)" ] && ok 'self 作用域不回显 orgId' || bad 'self 作用域仍外发 orgId' "$(jget data.orgId)"
    [ -n "$(jget data.days.0.date)" ] && [ -z "$(jget data.days.1.date)" ] \
        && ok 'range=day 的窗口补零到 1 天' || bad 'range=day 的窗口长度不对' "days.0=$(jget data.days.0.date) days.1=$(jget data.days.1.date)"
fi
req GET '/api/stats/usage?range=nonsense' "$TOKEN"
if api_ok 'GET /api/stats/usage?range=nonsense（未知值按 7 天而不是报错）'; then
    [ "$(jget data.range)" = "nonsense" ] && ok '未知 range 原样回显（不静默改写）' || bad '未知 range 被改写' "实际 $(jget data.range)"
    [ -n "$(jget data.days.6.date)" ] && [ -z "$(jget data.days.7.date)" ] \
        && ok '未知 range 的窗口是 7 天' || bad '未知 range 的窗口长度不对' "days.6=$(jget data.days.6.date) days.7=$(jget data.days.7.date)"
fi
req GET '/api/stats/usage?range=month' "$TOKEN"
if api_ok 'GET /api/stats/usage?range=month（30 天窗口）'; then
    [ -n "$(jget data.days.29.date)" ] && [ -z "$(jget data.days.30.date)" ] \
        && ok 'range=month 的窗口补零到 30 天' || bad 'range=month 的窗口长度不对' "days.29=$(jget data.days.29.date) days.30=$(jget data.days.30.date)"
fi
req GET '/api/stats/usage?range=day&orgId=smoke-no-such-org' "$TOKEN"
want_status 'usage 组织作用域按成员校验：非成员或不存在 → 403（不是回一份零用量）' 403 403

# v2.53：/api/knowledges 一组端点整体下线（候选 ㉖ 后半）。留两条 404 锚点而不是悄悄删掉遍历项——
# 这张本地授权表是 /api/ragflow 唯一的授权依据（转发用共享 Key），谁能接口化地往里写一行，
# 谁就把别人的知识库变成自己的可见集；改前真机实测过：植入一行后 documents 由 403 变 500、
# retrieval-test 由 403 变 200。锚点的职责是：以后有人把这类"可编程写入授权表"的接口加回来时，这里先响。
req GET /api/knowledges "$TOKEN"
[ "$STATUS" = "404" ] && ok 'GET /api/knowledges 已下线（404，不再有本地授权表的读口）' \
    || bad 'GET /api/knowledges 期望 404' "实际 HTTP $STATUS：$(detail)"
req POST /api/knowledges "$TOKEN" '{"name":"锚点","datasetId":"smoke-anchored-dataset"}'
[ "$STATUS" = "404" ] && ok 'POST /api/knowledges 已下线（404，授权表不可由请求体写入）' \
    || bad 'POST /api/knowledges 期望 404' "实际 HTTP $STATUS：$(detail)"
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

# ---------- 7.9 开放平台：Key 鉴权与握手闸门（v2.45） ----------
# 全部是负向断言：无 Key / 错 Key 在 OpenApiAuthInterceptor 内就返回，不进业务、不计费、不建会话；
# 握手侧故意不带 Key，用来证明 OPEN_WS 桶排在"验 Key 之前"（否则永远只会 401，打不出 429）。
# 放在 §8.5 之前：本节的 /api/open/chat 也落 EXPENSIVE 桶，先跑才不会互相污染。
section '7.9 开放平台鉴权与握手闸门'
# 必须带 Accept: text/event-stream：/api/open/chat 的 produces 只有这一个媒体类型，Accept 写成
# application/json 会在**路由阶段**就不匹配（Spring 抛 HttpMediaTypeNotAcceptableException），
# 拦截器根本没跑——那样断言到的不是鉴权行为，而是一个与此无关的 406（v2.49 起该 406 本身也被断言，见下方两条）。
STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
          -H 'Accept: text/event-stream' -H 'Content-Type: application/json' -d '{}' \
          "$BASE/api/open/chat" 2>/dev/null)" || STATUS="000"
BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
want_status '无 X-API-Key 调 /api/open/chat → 401' 401 401
# v2.49 · C-108：下面两条在 v2.45~v2.48 是一条登记性 SKIP（实测 HTTP 500）。
# 形状 (a)：Accept 只写 application/json ⇒ 406 且客户端接受 JSON，响应体写得出，业务码同为 406。
STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
          -H 'Accept: application/json' -H 'Content-Type: application/json' -d '{}' \
          "$BASE/api/open/chat" 2>/dev/null)" || STATUS="000"
BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
want_status 'Accept 与端点 produces 不符 → 406（不再谎报 500）' 406 406
# 形状 (b)：Accept: text/plain ⇒ 406 的固有形状，正文写不出去（客户端不收 JSON），故只判状态码。
# 这条不是冗余：兜底一旦被改回 500，(a) 会红；若有人为 406 补"强制写 JSON 正文"的兼容，(b) 会红。
STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
          -H 'Accept: text/plain' -H 'Content-Type: application/json' -d '{}' \
          "$BASE/api/open/chat" 2>/dev/null)" || STATUS="000"
BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
want_status 'Accept: text/plain 调 SSE 端点 → 仍是 406（正文允许为空）' 406 -
STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
          -H 'Accept: text/event-stream' \
          -H 'X-API-Key: smoke-not-a-real-key-00000000000000000000000000' \
          -H 'Content-Type: application/json' -d '{}' "$BASE/api/open/chat" 2>/dev/null)" || STATUS="000"
BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
want_status '错 Key 调 /api/open/chat → 401（不是 403：Key 压根没通过校验）' 401 401
ws_assert '开放语音握手无 Key → 401' 'api/open/ws-voice/smoke' '' 401 \
    '101 ⇒ 握手拦截器未生效，任何人无需凭据即可建立第三方语音会话'
OPEN_WS_429=0
OPEN_WS_TRIES=0
while [ "$OPEN_WS_TRIES" -lt 15 ] && [ "$OPEN_WS_429" = "0" ]; do
    OPEN_WS_TRIES=$((OPEN_WS_TRIES + 1))
    case "$(ws_handshake 'api/open/ws-voice/smoke' '')" in
        *429*) OPEN_WS_429=1 ;;
    esac
done
if [ "$OPEN_WS_429" = "1" ]; then
    ok "握手限流生效：$OPEN_WS_TRIES 次无 Key 握手内出现 429（OPEN_WS 桶排在验 Key 之前）"
else
    bad '开放语音握手未触发限流' '连续 15 次握手全走完成鉴权链路 ⇒ 握手不经 RateLimitService，可被用来暴力试 Key'
fi

# ---------- 7.10 能力变更：改完下一个请求即生效（v2.46 · 候选 ㉗） ----------
# 判据取 /api/open/call 的 403↔400 跳变：403 只可能来自能力闸门（它在业务之前），
# 400 只可能来自业务侧的空参校验（assistantId 不能为空，且排在扣配额与拨网关之前）。
# 于是"闸门换了答案"被演成一次可观测的状态跳变，而这条链路既不真拨号也不产生费用。
# 仍排在 §8.5 之前：本节的 3 次 open/call 与 §7.9 同族，落 EXPENSIVE 桶。
section '7.10 开放平台能力变更'

# open_call <API Key> → STATUS / BODY：body 故意留空，让"已开通 call"那一支停在 400 而不是真外呼
open_call() {
    STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
              -H 'Accept: application/json' -H "X-API-Key: $1" \
              -H 'Content-Type: application/json' -d '{}' "$BASE/api/open/call" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
}

# 跨账号与审计两条断言需要管理员令牌；第 8 节本来就会登录一次，这里提前登录并留给它复用
if [ -z "$ADMIN_TOKEN" ] && [ -n "${SMOKE_ADMIN_USER:-}" ]; then
    req_auth POST /api/auth/login '' "$(json username "$SMOKE_ADMIN_USER" password "${SMOKE_ADMIN_PASS:-}")"
    if api_ok "POST /api/auth/login（管理员 $SMOKE_ADMIN_USER，供 §7.10 跨账号断言）"; then
        ADMIN_TOKEN="$(jget data.token)"
    fi
fi

SCOPE_APP_ID=''
SCOPE_APP_KEY=''
req POST /api/openapi/apps "$TOKEN" "$(json appName "冒烟能力应用 $(date +%H%M%S)" webhookUrl '' scopes 'chat')"
if api_ok 'POST /api/openapi/apps（scopes=chat）'; then
    SCOPE_APP_ID="$(jget data.id)"
    SCOPE_APP_KEY="$(jget data.appKey)"
    [ "$(jget data.scopes)" = 'chat' ] \
        && ok '创建回显 scopes=chat：勾了什么就是什么' \
        || bad '创建回显 scopes 漂移' "$(jget data.scopes)"
    # 凭据载荷口径（v2.64 · C-131）：抑制落在实体上，所以"该给的那一次给得到"必须由出口显式构造来保证，
    # 而"库里存的东西给不出"是这条判据的另一半——两半都只跑真链路才算数（单测里载荷是桩造的）
    printf '%s' "$BODY" | grep -qF '"webhookSecret"' \
        && ok '创建响应仍交付 webhookSecret（v2.17 漏过这一步，签名恒不生效且无人报错）' \
        || bad '创建响应丢了 webhookSecret' "$(detail)"
    printf '%s' "$BODY" | grep -qE '"appKeyHash"|"isDeleted"|"userId"' \
        && bad '创建响应带出了库内部列' "$(detail)" \
        || ok '创建响应只带显式白名单字段（整颗实体不再当响应载荷）'
fi

if [ -n "$SCOPE_APP_KEY" ]; then
    open_call "$SCOPE_APP_KEY"
    want_status '只有 chat 能力调 /api/open/call → 403（能力闸门排在业务之前）' 403 403
    printf '%s' "$BODY" | grep -qF '未开通此能力' \
        && ok '403 报文点名缺哪个能力（不引导调用方去换一把同样没权限的 Key）' \
        || bad '403 报文未说明缺的能力' "$(detail)"

    req PUT "/api/openapi/apps/$SCOPE_APP_ID/scopes" "$TOKEN" "$(json scopes 'chat,call')"
    if api_ok 'PUT /api/openapi/apps/{id}/scopes（放开 call）'; then
        [ "$(jget data.scopes)" = 'chat,call' ] \
            && ok 'PUT 返回改后全量能力串（整串替换，不是"新增了哪几项"）' \
            || bad 'PUT 返回值不是全量能力串' "$(jget data.scopes)"
    fi
    open_call "$SCOPE_APP_KEY"
    want_status '同一 Key 同一请求 → 400（403→400 跳变即"下一个请求就生效"，无需重启或等待）' 400 400
    printf '%s' "$BODY" | grep -qF 'assistantId' \
        && ok '400 来自业务侧空参校验而非能力闸门（未扣配额、未拨网关）' \
        || bad '400 报文形状漂移' "$(detail)"

    req PUT "/api/openapi/apps/$SCOPE_APP_ID/scopes" "$TOKEN" "$(json scopes 'chat')"
    api_ok 'PUT scopes=chat（收回 call）'
    open_call "$SCOPE_APP_KEY"
    want_status '收回后同请求再 → 403（收回与放开走同一条路，不是单向门）' 403 403
fi

if [ -n "$SCOPE_APP_ID" ]; then
    # 管理侧的三条拒绝都是 **HTTP 200 + 业务 code 400**：`ApiResponse.paramError` 只改响应体不改状态行，
    # 与同控制器的 create/revoke 一致；而上面 /api/open/call 的 400/403 是**真状态码**（开放侧走
    # ResponseEntity 与拦截器 writeForbidden）。两种写法在本仓并存（4.4 只登记了"异常→状态"那一半），
    # 断言时必须按各自实际的出口形状写，否则就是一次假红。
    req PUT "/api/openapi/apps/$SCOPE_APP_ID/scopes" "$TOKEN" "$(json scopes 'chat,teleport')"
    want_status '未知能力值 → code 400（整体拒绝，不静默丢掉不认识的那一项）' 200 400
    printf '%s' "$BODY" | grep -qF '未知的能力' \
        && ok '拒绝文案点名那个不认识的值（运维不必猜是哪一项没生效）' \
        || bad '未知值拒绝文案未点名' "$(detail)"
    req PUT "/api/openapi/apps/$SCOPE_APP_ID/scopes" "$TOKEN" "$(json scopes '')"
    want_status '空集 → code 400（要全停请吊销，不放给用户没点过的能力）' 200 400
    printf '%s' "$BODY" | grep -qF '吊销' \
        && ok '空集拒绝给出出口（指向吊销，而不是让人对着"必填"再试一次）' \
        || bad '空集拒绝未给出出口' "$(detail)"
    req GET /api/openapi/apps "$TOKEN"
    if api_ok 'GET /api/openapi/apps（复核两次拒绝没动库）'; then
        # 列表是"手写白名单字段"的出口形状（v2.64 起凭据另由实体级注解兜底，这里锁可观测结果）
        printf '%s' "$BODY" | grep -qE '"webhookSecret"|"appKey"' \
            && bad '列表响应带出了凭据键' "$(detail)" \
            || ok '列表响应不含 appKey/webhookSecret（明文只在创建那一次交付）'
        ROW=0
        ROW_SCOPE=''
        while true; do
            ROW_ID="$(jget "data.$ROW.id")"
            [ -z "$ROW_ID" ] && break
            if [ "$ROW_ID" = "$SCOPE_APP_ID" ]; then
                ROW_SCOPE="$(jget "data.$ROW.scope")"
                break
            fi
            ROW=$((ROW + 1))
        done
        if [ -z "$ROW_SCOPE" ]; then
            bad '列表里找不到本次自造应用' "data[] 中没有 id=${SCOPE_APP_ID:0:8}… 的行"
        elif [ "$ROW_SCOPE" = 'chat' ]; then
            ok '两次拒绝后库里 scopes 仍为 chat（原子拒绝，无部分写入）'
        else
            bad '被拒绝的能力变更写进了库' "实际 scopes=$ROW_SCOPE"
        fi
    fi
    # 明文 Key 自 v2.45 起不可回读，所以"吊销后重建"不再是可行的调整路径；这条是它的前置保障
    open_call "$SCOPE_APP_KEY"
    want_status '两次被拒的 PUT 之后 Key 仍是有效凭据（403 而非 401：只缺能力，没掉认证）' 403 403

    if [ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "$TOKEN" ]; then
        req PUT "/api/openapi/apps/$SCOPE_APP_ID/scopes" "$ADMIN_TOKEN" "$(json scopes 'chat,call')"
        want_status '非属主（管理端令牌）改他人应用能力 → 与"不存在"同形，不透露归属' 200 400
        req GET '/api/admin/audit-logs?page=1&pageSize=50' "$ADMIN_TOKEN"
        if api_ok 'GET /api/admin/audit-logs（审计能回看能力变更）'; then
            printf '%s' "$BODY" | grep -qF '"action":"API_APP_SCOPES_UPDATE"' \
                && ok '审计已记录 API_APP_SCOPES_UPDATE' \
                || bad '审计缺 API_APP_SCOPES_UPDATE' '最近 50 条里没有该 action'
            # detail 是 JSON **字符串列**，随响应下发时被整体转义 ⇒ 匹配的是 \"to\":\"chat,call\" 这一形状
            printf '%s' "$BODY" | grep -qF '\"to\":\"chat,call\"' \
                && ok '审计详情带 from→to（能回答"谁把外呼能力放开了"，不只是"改过"）' \
                || bad '审计详情未记变更前后值' 'detail 里没有 to=chat,call'
            printf '%s' "$BODY" | grep -qF "\"targetId\":\"$SCOPE_APP_ID\"" \
                && ok '审计 targetId 指到本次应用（能指认具体是哪一个）' \
                || bad '审计 targetId 未指向本次应用' 'targetId 不匹配'
            # 逐行数本应用的 SCOPES_UPDATE 按 result 分桶：拒绝 ≥3（两类非法入参 + 一次非属主）、
            # 成功 ≥2。前者证"被拒绝不再记成成功"，后者证判据没被反向改成"一律失败"。
            # 边界：只扫上面取回的第一页（pageSize=50，新→旧），前提是本次五笔变更仍在最近 50 行内；
            # 共享库上有别人并发写审计时会挤出该页而伪红（手册 6.6 v2.47 条）。
            IDX=0
            REJECTED=0
            ACCEPTED=0
            REJECT_REASONS=''
            while true; do
                ACTION="$(jget "data.list.$IDX.action")"
                [ -z "$ACTION" ] && break
                if [ "$ACTION" = 'API_APP_SCOPES_UPDATE' ] \
                   && [ "$(jget "data.list.$IDX.targetId")" = "$SCOPE_APP_ID" ]; then
                    if [ "$(jget "data.list.$IDX.result")" = '0' ]; then
                        REJECTED=$((REJECTED + 1))
                        REJECT_REASONS="$REJECT_REASONS$(jget "data.list.$IDX.detail")"$'\n'
                    else
                        ACCEPTED=$((ACCEPTED + 1))
                    fi
                fi
                IDX=$((IDX + 1))
            done
            if [ "$REJECTED" -ge 3 ]; then
                ok "被拒绝的能力变更记 result=0（本次 $REJECTED 条：两类非法入参 + 一次非属主）"
            else
                bad '被拒绝的能力变更仍记成成功' "result=0 的行只有 $REJECTED 条，期望 ≥3 ⇒ 判据又退回'只看有没有抛异常'"
            fi
            if [ "$ACCEPTED" -ge 2 ]; then
                ok "成功的能力变更仍记 result=1（$ACCEPTED 条）⇒ 判据不是'一律失败'"
            else
                bad '成功变更未记 result=1' "result=1 的行只有 $ACCEPTED 条，期望 ≥2"
            fi
            # 三条拒绝各点名自己的原因，且**与行的返回顺序无关**（v2.54 实测撞出的脆弱写法：原先只取
            # 扫到的第一条 result=0 行的 detail 去匹配"非属主"那句，而这三条的 created_at 常落在同一秒，
            # `ORDER BY created_at DESC` 对同秒行不设二级排序 ⇒ 谁在前由存储顺序决定，同一个断言会随机红）。
            MISSING=''
            for r in '未知的能力' '至少要保留一项能力' '应用不存在或无操作权限'; do
                printf '%s' "$REJECT_REASONS" | grep -qF "$r" || MISSING="$MISSING「$r」"
            done
            [ -z "$MISSING" ] \
                && ok '拒绝行的 detail 逐条带业务侧原因（不只"失败了"，还答得出"为什么"）' \
                || bad '有拒绝行只有"失败了"没有"为什么"' "缺少$MISSING"
        fi
    else
        skip '非属主改能力 + 审计详情' '没有独立于业务账号的管理员令牌（提供 SMOKE_ADMIN_USER / SMOKE_ADMIN_PASS 后可测）'
    fi
else
    skip '能力变更全链' '应用创建失败，后续断言无法进行'
fi

# ---------- 7.11 拒绝台账与凭据通道（v2.50 · 候选 ㉙ + ㉜） ----------
# 两件事一起收口：①（C-110）把**有效** Key 只写进 URL 查询参数打 REST 侧，必须仍回 401——
# 长期凭据一旦进 URL 就会落进反代访问日志与浏览器历史，所以"能通"从来不是可选项；
# ②（C-111）§7.9/§7.10 攒下的 401/403/429 必须查得到，且属主只看到自己应用的行 +
#   全体可见的 unknown 格（无 Key / 错 Key / 握手限流都不知归属，把它们藏进"仅管理员"等于没做）。
# 排在 §7.10 之后：本节完全依赖前两节造成的拒绝事件，且此刻自造应用还没被吊销（吊销后行会退出读数）。
section '7.11 开放平台拒绝台账'

if [ -n "$SCOPE_APP_KEY" ]; then
    # 有效 Key + 正确的 Accept，唯一变量是"Key 放在哪"：查询参数不被读取 ⇒ 401
    STATUS="$(curl -sS --max-time "$TIMEOUT" -o "$BODY_FILE" -w '%{http_code}' -X POST \
              -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
              -d '{}' "$BASE/api/open/chat?apiKey=$SCOPE_APP_KEY" 2>/dev/null)" || STATUS="000"
    BODY="$(tr -d '\0' <"$BODY_FILE" 2>/dev/null)"
    want_status '有效 Key 只写在 URL 查询参数打 REST → 仍 401（HTTP 侧从不读 query，凭据不进日志面）' 401 401
else
    skip '有效 Key 只写在 URL 查询参数打 REST → 仍 401' '§7.10 未拿到自造应用的有效 Key'
fi

# 先取回"我这个账号名下的应用 id 清单"，作为下一条读数断言的判据（不能只断言"没有别人的行"，
# 那需要先知道别人是谁；改成"每一行要么 unknown、要么在我自己的清单里"是可判定的同强形式）
OWNED=''
if [ -n "$TOKEN" ]; then
    req GET /api/openapi/apps "$TOKEN"
    if api_ok 'GET /api/openapi/apps（取归属清单，供 §7.11 校验拒绝读数的可见性）'; then
        ROW=0
        while true; do
            ROW_ID="$(jget "data.$ROW.id")"
            [ -z "$ROW_ID" ] && break
            OWNED="$OWNED $ROW_ID "
            ROW=$((ROW + 1))
        done
    fi
fi

MY_APP_ROWS=''
UNKNOWN_KINDS=''
if [ -n "$TOKEN" ]; then
    req GET '/api/openapi/denials?hours=24' "$TOKEN"
    if api_ok 'GET /api/openapi/denials?hours=24（拒绝台账读数）'; then
        [ "$(jget data.windowHours)" = '24' ] \
            && ok '窗口按请求回显（前端不必猜服务端截断成了多少）' \
            || bad 'windowHours 回显漂移' "实际 $(jget data.windowHours)"
        IDX=0
        FOREIGN=''
        SCOPE_DENIED_COUNT=0
        while true; do
            KIND="$(jget "data.rows.$IDX.kind")"
            [ -z "$KIND" ] && break
            APP="$(jget "data.rows.$IDX.app")"
            if [ -z "$APP" ]; then
                UNKNOWN_KINDS="$UNKNOWN_KINDS $KIND "
            else
                case "$OWNED" in
                    *" $APP "*)
                        # 自己名下的应用：本节只关心 §7.10 那几次能力拒绝有没有落到按应用分的格子
                        [ "$KIND" = 'SCOPE_DENIED' ] && [ "$APP" = "$SCOPE_APP_ID" ] \
                            && SCOPE_DENIED_COUNT="$(jget "data.rows.$IDX.count")" ;;
                    *) FOREIGN="$FOREIGN $APP" ;;
                esac
            fi
            IDX=$((IDX + 1))
        done
        [ -z "$FOREIGN" ] \
            && ok '读数不含非本账号应用的行（别人的 Key 被拒过多少次都不透露）' \
            || bad '拒绝台账泄漏了他人的应用 id' "$FOREIGN"
        # 前两节的三类"我的"事件：§7.10 用只有 chat 的 Key 打了 3 次 /api/open/call
        [ "${SCOPE_DENIED_COUNT:-0}" -ge 3 ] \
            && ok "§7.10 的 403 已按应用入账（SCOPE_DENIED ×$SCOPE_DENIED_COUNT）" \
            || bad '§7.10 造成的能力拒绝未进台账' "SCOPE_DENIED count=$SCOPE_DENIED_COUNT，期望 ≥3"
        for EXPECTED in KEY_MISSING KEY_INVALID RATE_LIMITED; do
            case "$UNKNOWN_KINDS" in
                *" $EXPECTED "*|"$EXPECTED"*)
                    ok "无法归属的 $EXPECTED 全体可见（爆破不针对某个应用，锁在管理端等于看不见）" ;;
                *)
                    bad "台账缺 unknown 格的 $EXPECTED" "unknown 行=$UNKNOWN_KINDS" ;;
            esac
        done
    fi

    # 吊销即退出读数：这是"台账不泄漏已删对象"的口径，顺带承担 §7.10 自造应用的清理
    if [ -n "$SCOPE_APP_ID" ]; then
        if [ "$KEEP" = '1' ]; then
            skip 'DELETE + 吊销后该行退出读数' 'KEEP=1'
        else
            req DELETE "/api/openapi/apps/$SCOPE_APP_ID" "$TOKEN"
            api_ok 'DELETE /api/openapi/apps/{id}（清理本次自造应用）'
            req GET '/api/openapi/denials?hours=24' "$TOKEN"
            if api_ok 'GET /api/openapi/denials（吊销后再读一次）'; then
                printf '%s' "$BODY" | grep -qF "$SCOPE_APP_ID" \
                    && bad '吊销后仍能查到该应用的拒绝行' '读数未按当前归属过滤' \
                    || ok '吊销应用的行随归属查询一起退出（unknown 格不受影响）'
            fi
        fi
    fi
fi

# 握手侧"URL Key 通道默认关闭"这条不在真机重复：§7.9 的限流断言已把本机 IP 的 OPEN_WS 桶打到 429，
# 此后握手一律 429，再也分辨不出"401 因关闸"还是"401 因没 Key"；而开关本身要重启才换。
# 判定与两种台账落点（URL_KEY_REJECTED / URL_KEY_USED）在 OpenApiWebSocketAuthInterceptorTest 用真台账断言。
skip '握手侧 URL 查询参数通道默认关闭' '§7.9 已耗尽同 IP 的 OPEN_WS 桶 ⇒ 真机只能测出 429；开关需重启，判定与台账落点走单测'

# ---------- 8. 管理端只读 ----------
if [ -n "${SMOKE_ADMIN_USER:-}" ]; then
    section '8. 管理端只读检查'
    # 第 2 节为发码已经登录过同一个管理员：直接复用令牌，不再占一次 AUTH 桶（容量 5 次/分钟）
    if [ -z "$ADMIN_TOKEN" ]; then
        req_auth POST /api/auth/login '' "$(json username "$SMOKE_ADMIN_USER" password "${SMOKE_ADMIN_PASS:-}")"
        if api_ok "POST /api/auth/login（管理员 $SMOKE_ADMIN_USER）"; then
            ADMIN_TOKEN="$(jget data.token)"
            [ "$(jget data.role)" = 'admin' ] && ok '登录响应 role=admin' || bad '管理员账号 role 非 admin' "$(jget data.role)"
        fi
    else
        ok '管理员令牌复用本轮已建立的登录会话（第 2 节发码或 §7.10，未重复消耗 AUTH 桶）'
    fi
    if [ -n "$ADMIN_TOKEN" ]; then
        for p in /api/admin/overview '/api/admin/users?page=1&pageSize=1' '/api/admin/audit-logs?page=1&pageSize=1' \
                 /api/admin/quotas /api/admin/quotas/defaults /api/admin/archive/overview \
                 '/api/admin/invite-codes?page=1&pageSize=1'; do
            req GET "$p" "$ADMIN_TOKEN"
            api_ok "GET $p" || true
        done
        req GET /api/admin/quotas/defaults "$ADMIN_TOKEN"
        [ -n "$(jget data.assistantLimit)" ] && ok '兜底配额可直接注入管理页表单' || bad '兜底配额缺少 assistantLimit' "$(detail)"
        req GET /api/admin/users "$ADMIN_TOKEN"
        # 同上：管理端一次要扫多行，判据也只能落在"键不存在"上（值为 null 是旧的手写脱敏形态）
        printf '%s' "$BODY" | grep -qF '"password"' \
            && bad '用户列表仍带 password 键（实体级抑制未生效）' "$(printf '%s' "$BODY" | head -c 120)" \
            || ok '用户列表整页响应体无 password 键'
        # 未使用的邀请码等同"一个可注册的凭据"，台账必须在管理端鉴权之后才可见
        req GET '/api/admin/invite-codes?page=1&pageSize=1'
        want_status '无令牌读邀请码台账 → 401（未使用的码不对外可枚举）' 401 401
        req GET '/api/admin/invite-codes?page=1&pageSize=1' "$ADMIN_TOKEN"
        if [ "$STATUS" = "200" ] && printf '%s' "$BODY" | grep -q '"list"' \
                && printf '%s' "$BODY" | grep -q '"total"'; then
            ok '邀请码台账返回 list/total（管理页可直接分页渲染）'
        else
            bad '邀请码台账结构漂移' "$(detail)"
        fi
        # ---- 8.1 配额写入侧边界（v2.57 · C-124）----
        # 判据只在 QuotaPolicy：四项都限定在 0~上界，0 的语义是"关闭该维度"（既有口径，见手册 2.11）。
        # 这一批过去只有界面半边有判据（Admin.vue 校验"不小于 0 的整数"），服务端全链透传 ⇒ curl 就能把
        # 一个作用域静默锁死。用**哨兵作用域**：产品没有删配额行的接口，跑完只会留一行永不生效的配置，
        # 而 getEffective 只按真实 org_id/user_id 查，所以它不参与本轮其余断言（清理按精确主键删）。
        # 判据口径是"越界值没进库"而不是"哨兵行不存在"：冒烟可重复跑，第二轮起上一轮写入的合法行本来就在
        # 库里（v2.57 真机首跑恰好是第二轮，写成"不存在"当场伪红一次）。
        Q_SENTINEL='smoke-quota-sentinel'
        req PUT /api/admin/quotas "$ADMIN_TOKEN" "$(json scopeType user scopeId "$Q_SENTINEL" dailyMsgLimit -5)"
        want_param 'PUT 单日消息量 -5 → 拒绝并讲清 0 与留空的语义' '不能为负数'
        req PUT /api/admin/quotas "$ADMIN_TOKEN" "$(json scopeType user scopeId "$Q_SENTINEL" dailyCallSecLimit 86401)"
        want_param 'PUT 单日通话时长 86401 秒 → 拒绝并报出一天的秒数' '86400'
        # 反向锚点：0 与合法值都必须原样落库。只写"越界被拒"时，"把可疑值一律改回兜底值"的实现同样全绿，
        # 而那是把管理员刚做的关闸悄悄撤销——正是本批要防的形状。
        req PUT /api/admin/quotas "$ADMIN_TOKEN" "$(json scopeType user scopeId "$Q_SENTINEL" assistantLimit 0 dailyMsgLimit 1234)"
        api_ok 'PUT 哨兵作用域（助手 0＝关闭、消息 1234）'
        # quotas 有 uk_quota_scope(scope_type,scope_id) 唯一约束 ⇒ 哨兵作用域至多一行，
        # 所以这里"取第一行"是确定的（与 C-122 那条"同秒行没有二级排序"不同形）
        req GET /api/admin/quotas "$ADMIN_TOKEN"
        QUOTA_ROW="$(printf '%s' "$BODY" | tr -d ' \n' | grep -o "\"scopeId\":\"$Q_SENTINEL\"[^}]*}" | head -1)"
        [ -n "$QUOTA_ROW" ] && ok '哨兵作用域可读回（0 与 1234 那行确实进了库）' \
            || bad '哨兵配额行读不到' "$(printf '%s' "$BODY" | head -c 160)"
        case "$QUOTA_ROW" in
            *'"assistantLimit":0,'*) ok '关闭值 0 原样落库（未被抬回兜底值）' ;;
            *) bad '关闭值 0 被改写' "$QUOTA_ROW" ;;
        esac
        case "$QUOTA_ROW" in
            *'"dailyMsgLimit":1234,'*) ok '合法值 1234 原样落库（未被钳到默认或上界）' ;;
            *) bad '合法值 1234 被改写' "$QUOTA_ROW" ;;
        esac
        case "$QUOTA_ROW" in
            *'"dailyMsgLimit":-5,'*|*'"dailyCallSecLimit":86401,'*)
                bad '越界值进了库（校验没排在写动作之前）' "$QUOTA_ROW" ;;
            *) ok '两次越界 PUT 未改动库内值（拒绝先于写）' ;;
        esac
        # 超出整型域的值走的是消息转换层（HttpMessageNotReadableException 是 RuntimeException 子类），
        # 判据是"不许退化成 500"：管理员多数数量级手滑长这样，报 500 就等于让人以为是服务坏了
        req PUT /api/admin/quotas "$ADMIN_TOKEN" "{\"scopeType\":\"user\",\"scopeId\":\"$Q_SENTINEL\",\"dailyMsgLimit\":99999999999}"
        if [ "$STATUS" = '400' ] && [ "$(jget code)" = '400' ]; then
            ok 'PUT 单日消息量 1e11（超出整型域）→ HTTP 400 + 业务码 400（不是 500）'
        else
            bad 'PUT 超出整型域的配额值' "期望 HTTP 400/code 400，实际 HTTP $STATUS：$(detail)"
        fi
        # 逐字段不变而不是"看有没有报错"：转换层拒绝之后仍然要回读，否则"HTTP 400 但半行已写"读不出来
        req GET /api/admin/quotas "$ADMIN_TOKEN"
        QUOTA_ROW2="$(printf '%s' "$BODY" | tr -d ' \n' | grep -o "\"scopeId\":\"$Q_SENTINEL\"[^}]*}" | head -1)"
        [ -n "$QUOTA_ROW2" ] && [ "$QUOTA_ROW2" = "$QUOTA_ROW" ] \
            && ok '超出整型域的 PUT 之后库内值逐字段不变' \
            || bad '超出整型域的写入改动了库内值' "$QUOTA_ROW2"
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
# 先清窗：AUTH 桶是 60 秒**滑动**窗口，而 §2 的注册/登录/刷新已经占掉若干名额。
# 不等干净就断言会踩到一个真实过的假失败：桶里混着一条临界旧条目时，"第 5 次 login 见 429"
# 并不能证明此刻桶满 —— 那条旧条目可能在紧接着的 refresh 之前刚好出窗，于是断言 429 收到 400。
# 睡满 61 秒让全部旧条目出窗，本节才是"自己放行了 5 次 → 第 6 次必 429"的闭环证据。
printf '        …等待 61 秒让 AUTH 滑动窗口清空（前序区段占用的名额出窗）\n'
sleep 61
AUTH_429=0
AUTH_SHAPE_CHECKED=0
AUTH_ALLOWS=0
LOGIN_TRIES=0
BAD_LOGIN_BODY="$(json username 'smoke-no-such-user' password 'wrong-password-123')"
i=0
while [ "$i" -lt 8 ] && [ "$AUTH_429" = "0" ]; do
    i=$((i + 1))
    # 只有第一次等桶：否则本区段可能一条 400 也收不到，形状与容量两项断言一起失去证据。
    # 后续故意不等 —— 本节要的就是把 5 次/分钟打满。
    if [ "$i" -eq 1 ]; then
        req_auth POST /api/auth/login '' "$BAD_LOGIN_BODY"
    else
        req POST /api/auth/login '' "$BAD_LOGIN_BODY"
    fi
    LOGIN_TRIES=$((LOGIN_TRIES + 1))
    case "$STATUS" in
        429) AUTH_429=1 ;;
        # v2.36 真机实况：登录失败由 UserService 抛 RuntimeException，走全局兜底 ⇒ HTTP 400 + code 400，
        # 不是 401。本项目里 401 专指"会话凭据缺失/失效"（前端据此触发静默续期/跳登录），
        # 把凭据错误也返 401 会让登录页把自己判成"登录态过期"。断言按产品契约，不按 RFC。
        # 形状只记一次：循环打的是同一个不变响应，重复计数会把一项断言刷成四项。
        # 只有**放行**才往滑窗里写时间戳（429 不计数），所以放行次数就是本区段自己造出的条目数——
        # 下面的共用桶断言要用它判断"桶满"是不是还成立。
        400) AUTH_ALLOWS=$((AUTH_ALLOWS + 1))
             if [ "$AUTH_SHAPE_CHECKED" = "0" ]; then
                 AUTH_SHAPE_CHECKED=1
                 [ "$(jget message)" = '用户名或密码错误' ] \
                     && ok '错误口令 → HTTP 400 + 反枚举文案（不区分用户名/密码，避免用户名枚举）' \
                     || bad '错误口令响应文案漂移' "$(detail)"
             fi ;;
        *) bad '错误口令登录应 HTTP 400（项目兜底）' "$(detail)" ;;
    esac
done
if [ "$AUTH_429" = "1" ]; then
    if [ "$LOGIN_TRIES" -ge 9 ]; then
        bad 'AUTH 桶容量应为 5/分钟' '尝试 '"$LOGIN_TRIES"' 次才见 429，超出容量'
    elif [ "$AUTH_ALLOWS" = "5" ]; then
        ok "AUTH 桶容量精确：清窗后放行 5 次，第 6 次 429（5 次/分钟/来源）"
    else
        skip 'AUTH 桶容量精确为 5' "清窗后放行 $AUTH_ALLOWS 次即见 429 ⇒ 同来源还有别的流量在占桶（例如 REDIS_ENABLED=true 且多个实例共桶），本轮不判精确容量"
    fi
else
    bad 'AUTH 桶未生效' '8 次错误口令登录未见 429（清窗后连 5 次容量都打不满，等于不限流）'
fi
# 形状断言只在真的收到 400 时才执行；若进本区段前桶已被 §2 的邀请码断言打满，第一条就是 429，
# 少一条断言要显式登记成 SKIP，不能静默消失
[ "$AUTH_SHAPE_CHECKED" = "1" ] \
    || skip '错误口令响应形状' '本区段未见 400 ⇒ AUTH 桶进本节前后即 429（前序认证请求已占满 5 次/分钟）'
# 共用同桶的直接证据：login 打满后，refresh 这条从未被请求过的路径也应 429。
# 若两桶独立，refresh 会走到业务层对乱码令牌报 code 400（"刷新令牌无效或已过期"）——
# 429 与 400 的区分度就是本断言的价值：它证明限流按**档位**而不是按路径各数各的。
# 额外要求 AUTH_ALLOWS==5：只有"本区段自己放行了满 5 条新条目"才证明此刻桶真满；
# 靠旧条目凑满的 429 会在下一秒出窗，v2.45 实测就是这样把本断言打成假失败（断 429 收到 400）。
if [ "$AUTH_429" = "1" ] && [ "$AUTH_ALLOWS" = "5" ]; then
    req POST /api/auth/refresh '' "$(json refreshToken 'smoke-not-a-real-token')"
    want_status 'refresh 与 login 共用同桶（429 而非业务 400）' 429 -
else
    skip 'refresh 与 login 共用同桶' '未确认"本区段放行满 5 条新条目后仍 429" ⇒ 桶状态不可知，不断言共用'
fi

# ---------- 汇总 ----------
printf '\n----------------------------------------\n'
printf '冒烟结果：%d 项通过 ｜ %d 项失败 ｜ %d 项跳过\n' "$PASS" "$FAIL" "$SKIP"
printf '本次账号：%s ｜ 数据：%s\n' "$USER_NAME" \
    "$([ "$KEEP" = "1" ] && echo '保留（KEEP=1）' || echo '助手/会话已清理；一次性账号留在 users 表，需手工删除')"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
