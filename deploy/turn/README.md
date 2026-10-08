# TURN (coturn) 部署指引

云谕助手语音通话（WebRTC）依赖 STUN 穿越；**对称 NAT 环境必须部署 TURN 中继**才能打通。本目录提供 coturn 的容器部署物料，按以下步骤在**公网服务器**（建议开启 UDP 透传）执行。

## 1. 前置

- 一台公网服务器（建议 2C/2G+），开放防火墙/安全组端口：
  - `3478/udp + 3478/tcp`（TURN 信令）
  - `49152-65535/udp`（媒体中继）
- 已安装 Docker + Docker Compose。
- 一个指向该服务器的域名（如 `turn.example.com`）或使用服务器公网 IP。

## 2. 配置并启动

```bash
cd deploy/turn

# 1) 生成共享密钥并替换 turnserver.conf
openssl rand -base64 32     # 输出替换到 static-auth-secret=

# 2) 替换 realm 为你自己的域名
sed -i 's/REPLACE_WITH_YOUR_DOMAIN/turn.example.com/g' turnserver.conf

# 3) 启动
docker compose up -d

# 4) 健康检查：日志应出现 relay 分配
docker logs -f yunyu-turn
```

## 3. 生成临时凭据

coturn 侧支持 **TURN REST API（`use-auth-secret`）** 方案：凭据形如 `username = 过期时间戳` + HMAC(secret)，到期由 coturn 自动拒绝，无需在 `turnserver.conf` 里配置固定用户名密码。

> ✅ **自 v2.88 起本后端就是那个签发方**（见项目手册 5.9 / 7.5 v2.88）：把 `TURN_STATIC_AUTH_SECRET` 与 `TURN_REALM` 配对配齐，`GET /api/webrtc/config` 会在静态条目之外**按当前登录用户现签**两条候选（`turn:<realm>:3478?transport=udp` 与 `?transport=tcp`，共用一份 `username=<到期Unix秒>:<userId>`、`credential=base64(HMAC-SHA1(secret, username))`），并在响应里回 `turn:{signed:true, realm, expiresAt, ttlSec}`。两个键**只配一个**时后端打一条具名 WARN 且**不签发**（宁可不给 TURN 候选，也不给一份 coturn 验不过的假凭据）。
>
> 因此下面的 shell 命令**只剩两个用途**：① coturn 装好后先"不起后端"验一把（用 `turnutils_uclient` 或浏览器 `trickle` 页）；② 线上排障时对照"后端签出来的凭据是否与手工算的一致"。若把它生成的值写进 `WEBRTC_ICE_SERVERS` 当静态配置，就要接受 v2.38 登记过的两条性质：**过期时刻一到 TURN 就静默失效**（前端不报错，只是不再产生 `relay` 候选，得人工重算并重启）、且该 JSON 里的 `credential` 会下发给**任何已登录用户**——按"已公开"来设配额与 ACL。

生成单次凭据（手工对照用；有效期 1 小时）：

```bash
SECRET='<刚才生成的 static-auth-secret>'
USERNAME=$(($(date +%s) + 3600))          # 过期时间戳 ≈ 1 小时后
CREDENTIAL=$(echo -n "$USERNAME" | openssl dgst -hmac "$SECRET" -sha1 -binary | base64)
echo "username=$USERNAME"
echo "credential=$CREDENTIAL"
```

> **v2.88 已实现（原"进阶"）**：签发服务就是本后端自己（`service/TurnCredentialService` + `GET /api/webrtc/config`），算法与上面的 shell 一致，差别只有两点——username 用 `<到期秒>:<userId>` 形式（coturn 允许在时间戳后带尾巴，便于中继日志追人），以及每次调用重算到期、按登录用户各签一份。~~部署自建端点或经网关支持该能力~~ 不再需要。

## 4. 对接云谕助手后端

**推荐路径（v2.88）**：`WEBRTC_ICE_SERVERS` 只放静态 STUN（或干脆留空，前端会回退默认 Google STUN），TURN 交给下面三个变量：

```bash
TURN_STATIC_AUTH_SECRET='<与 turnserver.conf 的 static-auth-secret 一字不差>'
TURN_REALM='<与 turnserver.conf 的 realm 一字不差，同时会用作签发候选的 host>'
TURN_CREDENTIAL_TTL_SEC=7200
```

- `realm` **必须与 coturn 端一致**：后端把签发候选拼成 `turn:<realm>:3478?transport=udp|tcp`，所以"中继主机名 ≠ realm"的拓扑不走这条路，得回到下面的静态 JSON 路径手工填两条带凭据的条目。
- `TURN_CREDENTIAL_TTL_SEC` 要 **≥ `VOICE_MAX_CALL_SEC`**（单通墙钟上限），否则通话中途凭据过期；过期不报错，只是不再产生 `relay` 候选。
- 前端自 v2.88 起**每次建链重新拉取**该端点（旧版在页面内缓存整页生命周期，会让上面的过期风险复发）。

**静态路径**（固定账密或"不起后端先验一把"时才用）：把候选写入 `application.yaml` 的 `app.webrtc.ice-servers` → `WEBRTC_ICE_SERVERS`：

```json
[
  { "urls": "turn:turn.example.com:3478?transport=udp", "username": "1770000000", "credential": "BASE64_CREDENTIAL" },
  { "urls": "turn:turn.example.com:3478?transport=tcp", "username": "1770000000", "credential": "BASE64_CREDENTIAL" },
  { "urls": "stun:stun.l.google.com:19302" }
]
```

`.env` 中注入 —— ⚠️ **JSON 值必须整体用单引号包住**：本仓与 `docs/DEPLOYMENT.md` 的加载方式是 `set -a; . ./.env; set +a`，未加引号时 bash 会剥掉内层双引号，后端拿到的是非法 JSON，而 `parseIceServers()` 按设计**静默降级空数组**（v2.88 取证第一轮就因此丢掉一条静态条目）：

```bash
WEBRTC_ICE_SERVERS='[{"urls":"turn:turn.example.com:3478?transport=udp","username":"...","credential":"..."},{"urls":"stun:stun.l.google.com:19302"}]'
```

> 配了签发后 `GET /api/webrtc/config` 会同时带 `turn` 段；`curl -H "Authorization: Bearer <token>" .../api/webrtc/config` 看到 `data.turn.signed == true` 且 `iceServers` 里多出两条带 `credential` 的条目，就说明后端这一侧已经在工作——**这不代表 coturn 接受了它**，第 5 节的验证才是。

## 5. 验证

```bash
# 服务端侧：监听端口
ss -lunp | grep 3478

# 客户端侧：在线 WebRTC TURN 检测工具（如 trickle-ice 网页）填入候选，确认 relay 候选出现且 pair 成功
```

验证标准：浏览器发起通话时 `RTCPeerConnection` 内部出现 `relay` 类型候选，且双方在对称 NAT 下可正常通话。

## 6. 安全与运维

- `static-auth-secret` 属于长期密钥，务必保存到服务器环境/密钥管理，**勿入库或提交**；定期轮换（配合临时凭据过期，旧凭据自然失效）。
- 仅开放 `3478` 与中继端口段；`refused-private-peer-ip` 视网络拓扑决定是否开启。
- 用 `user-quota/total-quota` 限制单用户中继配额，防止 TURN 被滥用转流量。
- 证书可配 TLS（`5349`）使信令加密；媒体（DTLS-SRTP）本身已加密。