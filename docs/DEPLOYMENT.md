# 部署说明（DEPLOYMENT）

> 本文件回答"部署形态是什么、上线顺序有什么硬约束、怎么确认它真的生效了"。
> **逐步操作序列在[手册 5.10 公网试验部署清单](云谕助手项目手册.md#510-公网试验部署清单v229)**，
> 反代配置样例见手册 5.7，验收清单见手册 5.8——本文不重复命令，避免两处漂移。

## 0. 为什么不是 Cloudflare / 边缘托管

模板里的"构建与 Cloudflare 部署说明"在本仓不适用，原因是架构性的：

| 需要 | 现状 | 结论 |
|---|---|---|
| 常驻 JVM 进程（Spring Boot 单体） | 有 | 需要一台长驻主机（或容器），Serverless 边缘函数跑不了 |
| 双向 WebSocket 长连接（`/ws`、`/ws-voice`、网关侧 OkHttp WS） | 有 | 需要支持 WS 升级与不缓冲的反代；边缘平台需专用隧道 |
| 关系型 MySQL + 迁移账本 | 有 | 需要可达的数据库，且**迁移顺序由人控制** |
| 定时任务（归档、外呼超时、Webhook 重试、未认证连接回收） | 有 | 进程内调度 ⇒ 多实例时归档靠分布式锁防重 |
| 本地文件系统存录音 | 有（`RECORDING_DIR`） | 需要持久卷；多实例时录音不共享（登记在手册 6.6） |
| 语音中继 TURN（coturn） | 物料齐（`deploy/turn/`） | 需要公网可达的 UDP 端口段 |

已拍板的部署位置：**国内云 + ICP 备案**（备案未下来时的可行做法与代价见手册 5.10① 的"换非标准端口"一行）。
静态托管 + 外置后端的组合**没有**在本仓验证过，也不建议拿它当默认路径。

## 1. 目标拓扑

```
公网 ──443──> nginx（终结 HTTPS/WSS，同域托管前端 dist）
                ├─ /            静态资源
                ├─ /api         后端 127.0.0.1:8080
                ├─ /ws /ws-voice  同上（Upgrade + 不缓冲）
                └─ /actuator    deny（或独立管理端口 + 只绑回环）
后端 ──> MySQL 127.0.0.1:3306
      ──> 可选 Redis 127.0.0.1:6379（多实例共享状态）
      ──> 出网：LLM / RAGFlow / RustPBX / 搜索 / 天气 / Webhook 目标
```

**"业务端口不出机器"由内核保证**：公网实例必须设 `SERVER_ADDRESS=127.0.0.1`，让安全组成为第二道防线而非唯一屏障。
管理端口与业务端口分离时，`MANAGEMENT_SERVER_PORT` 与 `MANAGEMENT_SERVER_ADDRESS` **必须成对给**
（只给端口会被 `ManagementAddressGuard` 拒绝启动，因为 Boot 在独立管理端口上不继承 `server.address`）。

## 2. 构建产物与放置

| 单元 | 命令 | 放置 |
|---|---|---|
| 后端 jar | `cd backend && ./mvnw -DskipTests clean package` | `backend/target/backend-0.0.1-SNAPSHOT.jar` → `/opt/yunyu/bin/` |
| 前端 | `cd frontend && npm ci && npm run build` | `frontend/dist/` → `/var/www/yunyu` |
| 配置 | 复制 `.env.example` → `.env`，`chmod 600` | `/opt/yunyu/.env`，由 systemd `EnvironmentFile=` 注入 |
| 录音 | — | `RECORDING_DIR=/opt/yunyu/data/recordings`（持久卷） |

⚠️ **`.env` 不会被自动读取**（项目无 dotenv 依赖，Spring/JVM/Maven 都不解析它）：
手工启动用 `set -a && . ./.env && set +a`，服务用 systemd 的 `EnvironmentFile=`。
`start-backend.bat` 同样不读 `.env`，只在变量缺失时给一组开发默认值。

## 3. 上线顺序（硬约束）

```
1. 备份：scripts/backup-mysql.sh（改动前必做，含 0006 前的整表备份）
2. 迁移：scripts/db/db-migrate.sh --check   → 看清待应用项 → scripts/db/db-migrate.sh
3. 停服：systemctl stop yunyu
4. 替换：jar（+ dist，若有前端改动）
5. 起服：systemctl start yunyu && journalctl -u yunyu -n 50
6. 验收：手册 5.8 清单 + scripts/smoke/smoke.sh 真机一轮
```

**为什么"先迁移、再上新代码"**：实体已含新列而库没有 ⇒ 相关查询整体报 `Unknown column`。
两个方向的失败不对称，逐条见 [REGISTRY.md 第 4 节](REGISTRY.md#4-数据库迁移账本)：

- `0005_user_token_version.sql` 漏跑 ⇒ 签发抛异常（登录 500）且校验侧 fail-closed ⇒ **全站 401**；
- `0006_api_key_hash.sql` 跑过之后**无法回退**到 v2.44 及更早的 jar（它删掉了旧代码要读的明文列，且明文回填不出来）。

回滚预案的边界：**列可加可不加，删过的列回不来**。回退代码版本前先确认该版本读的列还在。

## 4. 首次启动的失败模式（都是设计如此，不是 bug）

| 现象 | 原因 | 处置 |
|---|---|---|
| 启动即失败，点名 `DB_USER` / `DB_PASSWORD` / `OPENAI_API_KEY` | 凭据未注入/为空/仍是模板占位值（`CredentialPlaceholderGuard` 在最早期拦） | 填真值；本地想快速起跑用 `scripts/gen-dev-env.sh` |
| 启动即失败：缺 `JWT_SECRET` | 无默认值，且要求 ≥32 字节强随机 | 生成一个真随机值，别复用示例 |
| 启动即失败：`ManagementAddressGuard` 拒绝 | 只设了独立管理端口没设监听地址 | 两项成对配 |
| 注册页要求填邀请码，但没码 | v2.89 起默认档是 `open`，出现这一现象说明环境里**显式**设了 `REGISTRATION_MODE=invite`（或取值拼错——判定只认显式 `open`，其余一律按 `invite`），而第一个管理员有"注册要码、发码要管理员"引导死锁 | 按手册 5.10④ 用 SQL 放第一个码；或确认这台实例本就该放开注册后删掉该环境变量 |
| 升级 v2.89 后注册闸门自己打开了 | **换 jar 就是换默认值**：v2.88 及以前不设该变量 ⇒ `invite`，v2.89 起不设 ⇒ `open`。已有的公网实例若没在 `.env` 里显式写过 `REGISTRATION_MODE`，升级后任何人都能注册 | 想保持闸门请在 `.env` 里**显式**补 `REGISTRATION_MODE=invite` 再重启；先确认 6.7 决策 2 的两个未做条件（消费熔断告警 / 新号默认低配额）能否接受 |
| 起来了但 `/actuator/health` 显示 DB DOWN | 库凭据/地址不对 | 先修连通性再对外 |

## 5. 部署必须核对的四个易漏项

这四条各自的"配错了也不会报错、但行为已经变了"的属性，是它们被反复登记的原因（详见手册 5.7 / 5.8 / 6.5 / 7.4）：

1. **`TRUST_PROXY` / `TRUST_HOPS`**：反向代理后部署必须设（本仓拓扑是 nginx→后端一跳 ⇒ `TRUST_HOPS=1`）。
   不设的后果不是"日志少了 IP"，而是**所有用户被算成 nginx 那一个地址、共用同一个限流桶**，
   且登录锁定的来源维度也随之失真。配错的另一个方向（跳数多算）同样危险。
   **验收方式**：用审计日志落库的 `ip` 字段人工核对几个不同来源的请求（手册 5.8）。
2. **`CORS_ALLOWED_ORIGINS`**：公网站点必须把正式域名加进来。浏览器 WS 握手一定带 `Origin`，
   漏配的表现是"能登录、点什么都没反应"且后端日志无异常栈。
3. **HTTPS/WSS 反代细节**：`Upgrade`/`Connection` 头透传、WS 路径不缓冲、长超时；HTTP 只留 301 与证书签发。
4. **录音目录**：`RECORDING_DIR` 指向持久卷且服务账号可写；多实例部署时各实例的录音互不可见（已登记为限制）。
5. **安全响应头由 nginx 下发**（v2.76）：`add_header X-Frame-Options DENY always;` 与
   `add_header X-Content-Type-Options nosniff always;`——meta 标签对 X-Frame-Options **无效**
   （浏览器忽略并控制台告警，index.html 已撤）；CSP 可暂留 meta 形态，收口到响应头时同步搬。

## 6. 单实例 / 多实例

| | 单实例（推荐首发） | 多实例 |
|---|---|---|
| 配置 | 默认 | `REDIS_ENABLED=true` + 反代 `ip_hash`（WS 会话表在进程内） |
| 令牌黑名单 / 限流桶 / 登录锁定 | 进程内即可 | 走 Redis；Redis 抛异常时限流**降级为进程内桶** ⇒ 多实例时限流各自为政（降级是有意的可用性选择，代价要知情） |
| 归档定时任务 | 正常 | 有跨实例分布式锁防重 |
| 开放拒绝台账 | 准确 | **各算各的 ⇒ 读数系统性偏低**（候选 ㉟） |
| 录音文件 | 本地可读 | 不共享（回放可能取不到文件） |

结论：试验阶段不建议多实例。要扩之前先把 ㉟ 与录音存储方案解决掉。

## 7. 备份、监控与关停

- **备份**：`scripts/backup-mysql.sh`（全量 + 保留期清理）+ 异地同步；恢复要**演练**过才算有备份。
  binlog 增量的落地状态与残余见手册 6.4。
- **健康检查**：`/actuator/health` 默认 `HEALTH_SHOW_DETAILS=never`（`/actuator` 免鉴权 ⇒ 公网实例必须保持关闭并由网络层封住）。
- **日志**：`LOG_FILE` 非空则按天 + 10MB 轮转、保留 7 天；`MAPPER_LOG_LEVEL` 默认 `INFO`（不打 SQL，避免参数进日志）。
- **关停与数据处置**：手册 5.10⑨ 是"试验结束后必做"的关停清单（关服务、收安全组、数据留不留与留多久）。

## 8. 语音相关（第一阶段可整块跳过）

语音不进第一阶段的决策已拍板；若要开：coturn 物料与指引在 `deploy/turn/` 与手册 5.9。凭据有两条路（**v2.88 起推荐现签**）：配 `TURN_STATIC_AUTH_SECRET` + `TURN_REALM`（+ 可选 `TURN_CREDENTIAL_TTL_SEC`，默认 7200，须 ≥ `VOICE_MAX_CALL_SEC`），`GET /api/webrtc/config` 就按当前登录用户现签 udp/tcp 两条临时候选；不配则只有 `WEBRTC_ICE_SERVERS` 的**静态 JSON 原样下发**给已登录用户。两条路的验收都在浏览器之外可先做：`curl -H "Authorization: Bearer <token>" http://127.0.0.1:8091/api/webrtc/config` 读 `data.turn.signed`——`false` 说明后端没配齐（半配也只会有一个具名 WARN，属预期），`true` 才说明签发侧在工作；**这不代表 coturn 接受该凭据**，`relay` 候选要真部署后看。⚠️ 走静态 JSON 时 `.env` 里的值**必须整体加单引号**，否则 `set -a; . ./.env` 会把内层双引号剥掉，后端按设计静默降级空数组（v2.88 取证实测）。RustPBX 网关侧承担 ASR/TTS 与打断（`RUSTPBX_*` 三个变量）。**语音主链路的 AI 回复侧至今未在真实网关上端到端复测**。

## 9. 上线前验收（最小集）

- [ ] `scripts/db/db-migrate.sh --check` 无待应用项
- [ ] 实例只监听回环（`ss -lntp` 核对 8080/9080/3306/6379）
- [ ] 从公网侧执行手册 5.8 的端口探测清单：业务端口、管理端口、数据库全部不可达
- [ ] `GET /api/auth/register-config` 与预期的注册准入一致
- [ ] 真机 `scripts/smoke/smoke.sh` 一轮按期望跑绿（含 §1 暴露面节），并按跑法口径记录项数
- [ ] 审计日志里能看到不同来源的 `ip`（证明 `TRUST_PROXY` 生效）
- [ ] 备份文件已生成**且完成一次恢复演练**
- [ ] 冒烟产生的一次性账号已按精确主键清理

## 10. 相关文档

- [../README.md](../README.md) — 快速起步与配置表
- [DEVELOPMENT.md](DEVELOPMENT.md) — 构建与批次流程
- [REGISTRY.md](REGISTRY.md#4-数据库迁移账本) — 迁移账本逐条
- [ARCHITECTURE.md](ARCHITECTURE.md) — 为什么是单体
