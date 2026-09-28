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
| `frontend` | `npm ci` → lint → type:check → 八道 Node 检查（`check:auth` + 七道桩测与一致性检查）→ 生产构建 | 无 |
| `gates` | 文档门禁 + 配置门禁 + `bash -n scripts/smoke.sh` | 无 |

**刻意不进 CI**：`scripts/smoke.sh` 全链路（要实例、要库）、真实模型调用、语音网关、浏览器级 E2E。

## 4. 一个批次的完整流程

```
读码/评估 → 定主题与编号 C-xx → 测试先行（RED）→ 实现 → 全部门禁
  → 手册 §7.5 加一行 + 头部版本戳 bump（涉及面还须改 1.x/2.x/4.x/5.x/6.x 相应小节）
  → README 同步（配置表 / 目录树 / 文档节）
  → git diff 敏感串扫描 → 一次本地提交 → 汇报并等推送批准 → 推送 → 核 CI
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
- 探针自身要先证明可信（过期即退出、显式关闭码、区分"键为 null"与"键不存在"），见 AGENTS.md 安全与验证纪律。

## 8. 文档维护

`scripts/check-docs.py` 当前检查五类：目录↔标题、表格竖线数、相对链接与跨文件锚点、遗留标记、手册变更记录连续性。
新增文档时**要把文件加进它的 `FILES`**，否则新文档不受任何门禁保护。

`scripts/check-registries.mjs`（v2.52）查的是**登记表 ↔ 代码**：[docs/REGISTRY.md](REGISTRY.md) 的四张可机判表（迁移账本 /
开放端点→能力 / 拒绝 Kind / AI 工具）与"门禁脚本台账 ↔ `package.json` ↔ `ci.yml` ↔ `AGENTS.md` 命令块"必须逐条对上，
文档里写死的计数（"七个枚举值""共七道桩测"）必须等于真实条数。**改了这些面而不同步登记表就会红。**
它只读仓库内文本，故与其它桩测一样在 CI 的零凭据环境可跑；它也接受 `--registry <路径>` 只替换被检查的那份 markdown
（代码侧仍读真实源码），用于验证判据真的会红而不必改动受版本控制的文档。

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
