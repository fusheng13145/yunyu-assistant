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

> ⚠️ **本后端当前不做签发**（v2.38 校正，见项目手册 5.9 / 6.6）：`GET /api/webrtc/config` 只是把环境变量 `WEBRTC_ICE_SERVERS` 的静态 JSON 原样回显给已登录用户，**没有** REST 凭据签发端点、不按用户绑定、不轮换。因此下面的命令是**由运维手工执行**、把结果写进环境变量；由此带来两条必须接受的性质：① 临时凭据一旦放进静态配置就**不再"临时"**——**过期时刻一到 TURN 就静默失效**（前端不报错，只是不再产生 `relay` 候选），需要定期重新生成并重启后端；② 该 JSON 里的 `credential` 会下发给**任何已登录用户**，应按"已公开"来设定 coturn 侧配额与 ACL。要去掉这两条，就得做下方"进阶"里的真签发服务。

生成单次凭据（可直接用在 `WEBRTC_ICE_SERVERS`，有效期 1 小时）：

```bash
SECRET='<刚才生成的 static-auth-secret>'
USERNAME=$(($(date +%s) + 3600))          # 过期时间戳 ≈ 1 小时后
CREDENTIAL=$(echo -n "$USERNAME" | openssl dgst -hmac "$SECRET" -sha1 -binary | base64)
echo "username=$USERNAME"
echo "credential=$CREDENTIAL"
```

> 进阶（**当前未实现，属语音二期**）：部署真正的 TURN REST API 凭据签发（自建端点，或经支持该能力的网关），把上述计算封装为 `GET /turn?expires=3600` 一类接口，让前端登录后动态获取、凭据与用户绑定并随会话过期 —— 这样 `WEBRTC_ICE_SERVERS` 不再写死静态凭据，上面 ①② 两条性质同时消失。

## 4. 对接云谕助手后端

将生成的 TURN/STUN 候选写入后端环境变量（`application.yaml` 的 `app.webrtc.ice-servers` → `WEBRTC_ICE_SERVERS`），前端经 `GET /api/webrtc/config` 自动拉取：

```json
[
  { "urls": "turn:turn.example.com:3478?transport=udp", "username": "1770000000", "credential": "BASE64_CREDENTIAL" },
  { "urls": "turn:turn.example.com:3478?transport=tcp", "username": "1770000000", "credential": "BASE64_CREDENTIAL" },
  { "urls": "stun:stun.l.google.com:19302" }
]
```

`.env` 中注入：

```bash
WEBRTC_ICE_SERVERS=[{"urls":"turn:turn.example.com:3478?transport=udp","username":"...","credential":"..."},{"urls":"stun:stun.l.google.com:19302"}]
```

> 若前端已登录且凭据由 REST 接口动态下发，可仅放 STUN 静态候选，TURN 凭据由业务接口补充。

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