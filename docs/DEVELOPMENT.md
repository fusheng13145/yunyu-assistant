# 开发流程（DEVELOPMENT）

> 怎么把改动做完。规范条文在 [AGENTS.md](../AGENTS.md)（协作与纪律）与
> [COMPONENT-GUIDELINES.md](COMPONENT-GUIDELINES.md)（实现约束），本文只给命令、顺序和检查点。

## 1. 环境

| 项 | 要求 | 备注 |
|---|---|---|
| JDK | 21 | 后端用仓库自带的 `mvnw`（**必须在 `backend/` 目录下调用**） |
| Maven | 3.8+（或直接用 wrapper） | |
| Node.js | ≥ 20（CI 用 22，本机验证用到 24） | Node 门禁依赖原生 TS 类型擦除 |
| Python | 3.x | 两个文档/配置门禁脚本 |
| Git Bash（Windows） | 必须 | `bash -n`、`smoke.sh`、`db-migrate.sh` 都在 bash 下跑 |
| MySQL | 8+ | 单测**不需要**可达的库；真机冒烟需要 |
| Redis | 可选 | 本机是共享实例，约定见 [AGENTS.md](../AGENTS.md) 不可逆与共享资源一节 |

## 2. 首次拉起

```bash
# 1) 配置：生成 JWT_SECRET 并列出仍需人工填的项
scripts/gen-dev-env.sh                       # 产出 .env（不入库）
set -a && . ./.env && set +a                 # .env 不会被自动读取，必须显式导出

# 2) 数据库：全新环境建表；已部署库只跑增量
mysql -h"$DB_HOST" -P"$DB_PORT" -u root -p < backend/src/main/resources/index.sql   # ⚠️ 含 DROP DATABASE
scripts/db-migrate.sh --check && scripts/db-migrate.sh

# 3) 后端 / 前端
cd backend && ./mvnw -DskipTests spring-boot:run
cd frontend && npm ci && npm run dev         # Vite 5173，代理目标默认 8080
```

Windows 下可双击 `start-backend.bat` / `start-frontend.bat`（**它们不读 `.env`**，只在变量缺失时给一组开发默认值）。

后端不在 8080 时（本机自验证固定用 **8091 业务 / 9091 管理，只监听回环**），前端代理目标用 `VITE_PROXY_TARGET=http://localhost:8091` 覆盖。

## 3. 提交前必跑（与 CI 同源）

```bash
# 后端（backend/ 目录下；这两项占位值 + 一次性密钥是必需的，见第 3.1 小节）
DB_USER=ci DB_PASSWORD=ci-placeholder-unused OPENAI_API_KEY=ci-dummy-key-not-used \
  JWT_SECRET=$(openssl rand -hex 32) ./mvnw test

# 前端（frontend/ 目录下）
npm run lint && npm run type:check \
  && npm run check:auth && npm run check:notification && npm run check:kb-flag \
  && npm run check:history-record && npm run check:chat-frame \
  && npm run check:recording-upload && npm run check:denial-ledger \
  && npm run check:page-layout \
  && npm run check:registries \
  && npm run build

# 门禁（仓库根目录）
python scripts/check-docs.py && python scripts/check-config.py && bash -n scripts/smoke.sh
```

### 3.1 为什么这几个环境变量必须给

唯一的 `@SpringBootTest` 会真加载 `application.yaml`，而其中 `DB_USER` / `DB_PASSWORD` / `OPENAI_API_KEY` / `JWT_SECRET`
**没有默认值** ⇒ 缺任一个占位符解析即失败。**缺 `JWT_SECRET` 报的是 `contextLoads` 一条 error，不是回归**。

### 3.2 判绿的唯一方式

- 跑前删除 `backend/target/surefire-reports`，跑后**只按 `TEST-*.xml` 计数**（外层 `.txt` 对 `@Nested` 类打印
  "Tests run: 0"，控制台聚合行也可能被截断）。
- `mvn` 的退出码不许被尾部管道或 `echo` 吞掉。
- "0 failures" 有两种可能：全绿，**或者根本没跑**。
- 冒烟项数必须带**跑法口径**（是否传了 `SMOKE_ADMIN_*` / `SMOKE_ORIGIN`），否则会把"跑法不同"读成"回归变少"。

### 3.3 CI 的三个 job

| job | 内容 | 凭据 |
|---|---|---|
| `backend` | JDK 21 + `./mvnw -B test`，失败上传 surefire 报告 | 无库、无仓库 Secrets（自备占位值） |
| `frontend` | `npm ci` → lint → type:check → 九道 Node 检查（`check:auth` + 八道桩测与一致性检查）→ 生产构建 | 无 |
| `gates` | 文档门禁 + 配置门禁 + `bash -n scripts/smoke.sh` | 无 |

**刻意不进 CI**：`scripts/smoke.sh` 全链路（要实例、要库）、真实模型调用、语音网关、浏览器级 E2E。

## 4. 一个批次的完整流程

```
读码/评估 → 定主题与编号 C-xx → 测试先行（RED）→ 实现 → 全部门禁
  → 手册 §7.5 加一行 + 头部版本戳 bump（涉及面还须改 1.x/2.x/4.x/5.x/6.x 相应小节）
  → README 同步（配置表 / 目录树 / 文档节）
  → git diff 敏感串扫描 → 一次本地提交 → 汇报 → 推送 → 核 CI（每轮收口后即推送，2026-09-29 起长期授权）
```

提交信息沿用既有风格：`feat: v2.51 主题（C-112 一句话 / C-113 一句话）`（纯修复用 `fix:`，纯文档批次也占一个版本号）。

推送与 CI 核对的纪律（SSH origin、`ls-remote` 落地、匿名 `/check-runs` 用完整 SHA）见
[AGENTS.md](../AGENTS.md) 批次节奏一节。

## 5. 发布单元与"组件发布流程"

本仓没有对外分发的组件包，因此"发布"有四个真实单元，各自的产出与流程：

| 单元 | 产出 | 流程 | 版本标识 |
|---|---|---|---|
| 后端 | `backend/target/backend-0.0.1-SNAPSHOT.jar` | `cd backend && ./mvnw -DskipTests clean package` → 拷到 `/opt/yunyu/bin/` → systemd 重启 | jar 文件名不随批次变，**靠手册 7.5 的版本号区分** |
| 前端 | `frontend/dist/`（静态产物，`assetsDir=static`） | `npm run build` → 整目录替换 `/var/www/yunyu` | 同上；无 CDN 版本号策略，靠浏览器缓存 + 反代头 |
| 数据库 | `backend/src/main/resources/db/migrations/00NN_*.sql` | 写幂等迁移 → `db-migrate.sh --check` 看清单 → 应用；账本表 `schema_migrations` | 迁移文件名（不含扩展名）即版本号 |
| 门禁/脚本 | `scripts/*`（不进产物） | 改完必须本地跑 + CI 同源执行 | 随批次 |

> **"要不要发布组件库"目前的答案是不做**：前端只有 4 个共享组件且全部服务本仓视图。
> 相关登记说明见 [REGISTRY.md](REGISTRY.md)。

### 5.1 上线顺序是硬约束

**先迁移、再上新代码。** 实体已含新列时代码先上会整体报 `Unknown column`；
迁移 0006 会**删列**（`api_apps.app_key` 明文），跑过之后无法回退到 v2.44 及更早的 jar。
逐条迁移的内容与可回退性见 [REGISTRY.md](REGISTRY.md#4-数据库迁移账本)，部署细节见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 6. 写迁移的规矩

- 文件名 `NNNN_主题.sql`，序号递增、不回收；内容必须**幂等**（守卫存储过程在 `db/migrate-helpers.sql`）。
- 一次迁移只做一件事；**删列/删表**这类不可逆项要在手册 5.4 与 5.8 写明"跑之前先整表备份"。
- 实体列与迁移同批次提交；`index.sql` 同步加列（新环境一次建齐）。
- `db/seed-demo.sql` 是演示数据，**生产禁跑**。

## 7. 真机验证（改了部署面/接口契约才需要）

```bash
# 起实例（本机固定回环 + 8091/9091），再打冒烟
scripts/smoke.sh              # 分节冒烟；登录失败即 exit 2
```

- 边界：不调真实外部额度、不触发归档执行；一次性冒烟账号**无法自助删除**（无注销接口），跑完要按精确主键清理。
- 清理一次性账号后**审计行保留不删**，因此开发库必然留下悬空 `audit_logs.user_id` 引用——这是流程产物、不是脏数据，不必逐轮表态；判据与现状读数见手册 6.4（v2.56 口径），可读性缺口登记为候选 ㊹。
- 探针自身要先证明可信（过期即退出、显式关闭码、区分"键为 null"与"键不存在"），见 AGENTS.md 安全与验证纪律。

## 8. 文档维护

`scripts/check-docs.py` 当前检查五类：目录↔标题、表格竖线数、相对链接与跨文件锚点、遗留标记、手册变更记录连续性。
新增文档时**要把文件加进它的 `FILES`**，否则新文档不受任何门禁保护。

`scripts/check-registries.mjs`（v2.52）查的是**登记表 ↔ 代码**：[docs/REGISTRY.md](REGISTRY.md) 的四张可机判表（迁移账本 /
开放端点→能力 / 拒绝 Kind / AI 工具）与"门禁脚本台账 ↔ `package.json` ↔ `ci.yml` ↔ `AGENTS.md` 命令块"必须逐条对上，
文档里写死的计数（"七个枚举值""共八道桩测"）必须等于真实条数。**改了这些面而不同步登记表就会红。**
自 v2.53 起它还锁一条**不由登记表承载**的口径（第 6 组）：`knowledgebases` 是全仓唯一的知识库归属依据，
故"写入面只有一处"必须成立——判据落在控制器文件不存在、`knowledgeBaseService.create(` 的唯一调用点、
service 内只剩一条 `insert`、迁移 0007 与 `index.sql` 同口径、冒烟保留两条 404 锚点这五处实况上。
自 v2.54 起再加一组（第 7 组，判据来自手册 4.5 第 14 条）：LLM 模型清单**同时是界面选项与被执行白名单**的那一份，
助手级成本参数（模型 / 温度 / 最大输出 Token / 人设）**只能有一处判据入口**——故判据落在"`ModelInfo` 字面量只在
`ModelCatalog` 构造"、"除策略类与实体自身外全仓无人裸读四项 getter"、"三条装配链路都走 `runtime()`"、
"POST 拒绝先于建库写入而 PUT 鉴权先于校验"、"前端两处 `min/max` 与后端常量逐项相等"、"冒烟 §3.5 锚点与反向锚点在位"
这六类形状上；其中"清单解析到 ≥4 项"是防本组正则失效后静默全绿的空转锚点。
自 v2.57 起再加一组（第 8 组，判据来自手册 4.5 第 15 条）：配额四项数值的**上界**与**"0＝关闭"的文案**各只允许有一处，
故判据落在"四项 `MAX_*_LIMIT` 只在 `QuotaPolicy` 声明（时长那条必须写成 `24 * 60 * 60`，判据是一天的秒数而不是魔法数）、
后端其余源码与前端 `Admin.vue` 都不复制这些数、关闭文案只在策略类里拼、`AdminController` 的校验排在 upsert 路径内
任何库动作之前、`index.sql` 有 `uk_quota_scope`（冒烟逐值读回之所以确定）、`smoke.sh` §8.1 的具名锚点与反向锚点在位
且判据不依赖"库里本来没有哨兵行"（重复跑必须结论稳定）"这七类形状上；同批把 §3.5 的锚点计数改为"缩进也认"，
因为 PUT 侧那条一直在 `if` 块里缩进，旧写法给它留了漏登记的余量。
它只读仓库内文本，故与其它桩测一样在 CI 的零凭据环境可跑；它也接受 `--registry <路径>` 只替换被检查的那份 markdown
（代码侧仍读真实源码），用于验证判据真的会红而不必改动受版本控制的文档。
自 v2.58 起再加一组（第 9 组，判据来自手册 2.5 与 4.5 第 13 条）：**知识库可见性求交**是越权读取的唯一闸门，
而它此前在两条内部通道各写了一份私有包装、开放通道整条缺位——故判据落在"求交方法全仓只有一处定义"、
"文本 WS / 语音 WS / 开放 OpenAPI 三条装配链路都调用它（含文本 WS 运行中改选数据集那条）"、
"私有包装归零"、"空输入短路排在查库之前"、"丢弃计数 WARN 只在判据内部"、"三条通道各自的具名求交单测仍在"
这些形状上；最后一条是"用例名一旦被删本组先红"的锚点，因为删掉用例不会让其余任何一条判据变红。
自 v2.59 起再加一组（第 10 组，判据来自手册 4.5 第 17 条）：**单日通话时长**的复核此前只存在于发起前，而进行中的通话
`duration_sec` 恒为 0 ⇒ 一整通超长通话可以整轮穿透日上限。故判据落在"通话中判据全仓只有一处定义"、"关闭 / 越界两种拒绝
各只有一个出口且发起侧与回合侧都调用同一对"、"越界文案只拼一处（第二份会在两个入口给出不同的分钟数）"、
"`checkOngoingCallSec` 方法体内不得出现原子扣减（逐轮扣次数＝把通话次数配额当秒表烧掉）"、
"回合内三道复核次序固定：能力 → 时长 → 消息"、"被拒回合的收场是送原因帧 → 播报 → 挂断"，
以及三条**反向锚点**（语音处理器不得为时长新增定时器、PSTN 侧不得出现该调用、发起侧时长判定的处数仍是两处）——
后三条守的是"把墙钟方案顺手补一半"这个最可能出现的后续形态（手册候选 ㊻）。

- 手册是权威层；`AGENTS.md` / `README.md` / `docs/*` / `CHANGELOG.md` / `TODO.md` 是导读层，
  **不要在导读层重复计数值**（测试类数、用例数、冒烟项数只写一处）。
- 插入式编辑不要锚相邻标题行（历史上已四次吃掉邻居）；改完跑 `grep -c` + 竖线计数 + `check-docs`。
- 标题锚点由门禁按同一规则计算：小写、去标点、空格转 `-`（中文保留），如 `### 7.4 问题追踪…` → `#74-问题追踪…`。

## 9. 已知的环境坑

| 坑 | 表现 | 处置 |
|---|---|---|
| Windows 控制台非 UTF-8 | 门禁脚本打印中文/箭头时 `UnicodeEncodeError` 崩掉 | 脚本内已 `reconfigure`；手工跑 Python 可设 `PYTHONIOENCODING=utf-8` |
| MSYS 路径改写 | `curl` 的 `/ws/xxx` 被改成 `C:/Program Files/Git/ws/xxx`；中文参数被按 ANSI 码页重编码 | 冒烟脚本已改为解释器内补斜杠 + `--data-binary @文件`，写新断言时照此 |
| 换行符 | 脚本在 Linux 上 `\r` 报错 | `.gitattributes` 强制脚本 LF、`*.bat/*.cmd` 保持 CRLF |
| 可执行位 | Linux 上 `./mvnw`、`scripts/*.sh` `Permission denied` | git 索引须为 `100755`（Windows 看不出问题） |
| `type:check` 与 `build` 不等价 | `--noEmit` 对模板类型窄化全绿，`-b` 会报错 | 两条都要跑，`build` 不可省 |
| 8080 已被他人占用 | 起不来或打到别人的进程 | 本机自验证固定 8091/9091；**绝不停非自己起的进程** |

## 10. 相关文档

- [DEPLOYMENT.md](DEPLOYMENT.md) — 部署与验收
- [REGISTRY.md](REGISTRY.md) — 登记表与脚本台账
- [../TODO.md](../TODO.md) — 当前进度与待排期
