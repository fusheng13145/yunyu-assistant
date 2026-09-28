# AGENTS.md — 协作与开发规范

面向在本仓库工作的 AI 代理与开发者。**本文件只写"做错会出事"的约束**；功能与架构的细节权威在 [项目手册](docs/云谕助手项目手册.md)，入口与配置在 [README](README.md)，文档之间的关系见 [文档索引](#文档索引)。

## 交付标准

一句话：**不是每个计划功能都要有，但已实现的每个功能都必须站得住。**

- 已写进 README / 手册的能力，必须能被指向的代码或测试证据支撑；做不到就改口径，不改代码来凑。
- 允许"只登记不修"（写进手册 7.4 候选清单），但**不能顺手改行为**，也不许留半成品实现。
- 修缺陷时不夹带无关重构；缺陷修复不需要顺手清理周边。

## 批次节奏（提交与推送是两件事）

- **一个批次 = 一个主题收口 + 一个本地提交**。C-xx 编号连续，代码、测试、门禁、文档在同一个提交里落地。
- 提交前必做（缺一不可）：

  ```bash
  # 后端（环境变量与 CI 同源，缺一即 contextLoads 报错而非回归；详见 docs/DEVELOPMENT.md §3）
  cd backend && DB_PORT=3399 DB_USER=ci DB_PASSWORD=ci-placeholder-unused \
    OPENAI_API_KEY=ci-dummy-key-not-used JWT_SECRET=$(openssl rand -hex 32) \
    ./mvnw test
  # 前端（注意：上一行之后在 backend/ 里，要回仓根）
  cd ../frontend && npm run lint && npm run type:check && npm run build
  for c in auth notification kb-flag history-record chat-frame recording-upload denial-ledger registries; do npm run check:$c; done
  # 门禁（在仓根）
  cd .. && python scripts/check-docs.py && python scripts/check-config.py && bash -n scripts/smoke.sh
  ```

- **推送逐批单独等用户明确批准**，走 SSH origin（HTTPS 在本机 DNS 失败）。汇报必须区分"已提交"与"已推送"。
- 推送后用 `git ls-remote origin refs/heads/main` 核对落地；CI 结论用匿名 `GET /repos/.../commits/<full-sha>/check-runs`（要完整 SHA）。

## 文档索引

| 文件 | 职责 | 什么时候读 |
|---|---|---|
| [README.md](README.md) | 功能一览、技术栈、快速开始、环境变量表 | 第一次接触项目 |
| [AGENTS.md](AGENTS.md) | 本文件：硬约束与交付标准 | 动手前 |
| [DESIGN.md](DESIGN.md) | 界面视觉规范（纯白极客 + 暗色） | 改前端样式前 |
| [CHANGELOG.md](CHANGELOG.md) | 版本记录**索引**（正文在手册 7.5） | 查历史版本 |
| [TODO.md](TODO.md) | 当前进度与待表态事项 | 找下一步做什么 |
| [docs/PROJECT-SPEC.md](docs/PROJECT-SPEC.md) | 项目定位、产品目标、功能范围与非目标 | 判断"该不该做" |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、拦截器链、WS 通道、数据模型、外部集成 | 动跨模块代码前 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 环境、启动、迁移、门禁、冒烟、提交前清单 | 日常开发 |
| [docs/COMPONENT-GUIDELINES.md](docs/COMPONENT-GUIDELINES.md) | 前端组件、样式、依赖与无障碍规范 | 写组件前 |
| [docs/PAGE-STRUCTURE.md](docs/PAGE-STRUCTURE.md) | 路由 → 视图 → 接口 → WS 的对应关系 | 改页面时 |
| [docs/REGISTRY.md](docs/REGISTRY.md) | 仓库里的六张"登记表"（工具 / 开放能力 / 拒绝 Kind / 迁移账本 / 环境变量 / 门禁脚本） | 加工具、开放端点、列或依赖时 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 构建与部署实况（国内云 + ICP + nginx + coturn） | 上线相关 |
| [docs/云谕助手项目手册.md](docs/云谕助手项目手册.md) | **权威细节层**：7 章 + 问题追踪 7.4 + 变更记录 7.5 | 任何拿不准的口径 |

**新文档是导读层，不是第二份事实来源。** 与手册冲突时以手册为准，并同步修正；不要在导读层重复计数值（测试类数、断言数、行数）——那些归手册，抄一次就多一处会腐烂的地方。

## 不可逆与共享资源（本机环境）

- `backend/src/main/resources/index.sql` **开头是 `DROP DATABASE`**：任何时候都不要整份灌向有数据的实例。已部署库只走 `scripts/db-migrate.sh`（幂等增量）。
- **上线顺序固定为"先迁移、再上新代码"**。迁移 0005 缺列会让全站 401；迁移 0006 删掉 `api_apps.app_key` 明文列后**无法回退**到旧 jar。
- 本机是**共享机器**：8080 已被他人占用，自验证用业务 8091 / 管理 9091 并只监听 `127.0.0.1`；Redis 6379 与他人共用——**只按精确键名操作，禁止 `KEYS` 扫描、禁止清库**；不停任何不是自己起的进程（停之前用端口 + 命令行证明归属）。
- 开发库口令在 gitignored 的 `.scratch/dbpw`，用 `MYSQL_PWD` 注入，**不回显、不入库**。
- `.trae/` 等本地草稿目录不入库。

## 安全与验证纪律

- **不做"把安全护栏改回不安全实现"式变异测试**（例如把 `ClientIpResolver` 改回读 XFF 最左段、把默认关闸改成默认开闸）。这类改动会被本机权限层判定为未经请求的安全降级并拒绝；需要证明护栏有鉴别力时，改用反向锚点断言 + 真机负向实测，并在手册登记"未做变异"的原因。
- 变异测试只用于**把正确实现改窄改错**的方向，且每组跑后按 `sha256` 还原，末尾复核 `git grep MUTATION -- backend/src frontend/src scripts` 为空。
- 判定"通过/失败"只读原始产物：删掉 surefire 报告后跑，按 `TEST-*.xml` 计数；`mvn` 的退出码不能被管道后的 `echo` 吃掉（用 `./mvnw ... > log 2>&1; echo EXIT=$?`）。**"0 例失败"经常意味着编译失败或根本没跑**（本仓踩过三次，见手册 4.8）。
- 引用冒烟项数必须带跑法口径：`scripts/smoke.sh` 的断言数随是否提供 `SMOKE_ADMIN_*` 而变，且 §8.5 是"计数相对"断言（前序区段吃掉一个槽位就会改变回报的次数）。
- 后端跑测试要导出与 CI 同源的环境：`DB_PORT=3399 DB_USER=ci DB_PASSWORD=ci-placeholder-unused OPENAI_API_KEY=ci-dummy-key-not-used JWT_SECRET=<64 位十六进制> ./mvnw test`。**唯一的 `@SpringBootTest` 会真加载 `application.yaml`**，缺 `JWT_SECRET` 时 `contextLoads` 报错——那是环境缺失，不是回归。
- JVM 与 Maven **都不读 `.env`**（无 dotenv 依赖），需要 `set -a && . ./.env && set +a` 显式导出。

## 代码约定

- 后端：包结构 `controller/service/mapper/entity/handler/interceptor/tool/config/util/aspect/task`；**没有 DTO 层**，请求体多用 `Map<String, …>`（凭据类请求必须用 DTO/Map，不要用实体接，见手册 4.5）。统一响应体 `common/ApiResponse`。
- 凭据外发抑制落在**实体上**（`@JsonIgnore` + 不含凭据的 `toString()`），不在出口手写 `setXxx(null)`——手写会改写查出来的行，且少写一处就泄露一处。
- 客户端地址只经 `util/ClientIpResolver`；限流桶键、登录锁定、审计落库三处共用这一口径。
- 前端：无 Pinia、无 axios，会话态在 `localStorage` + `api/auth.ts` 的统一出口；**共享控件是 `src/style.css` 里的 `.geek-*` 全局类**，不是各页各写一份；通知走 `composables/useNotification.ts` 单例 + `App.vue` 唯一挂载点。
- 被 Node 门禁脚本 `import` 的前端模块**只能依赖裸包名或 `import type`**（普通相对 value import 在 Node 下 `ERR_MODULE_NOT_FOUND`）。
- 注释默认不写；要写就写 **WHY**（隐藏约束、微妙不变量、特定 bug 的绕法），不复述代码在做什么。

## 手册登记规范（本仓既定，不必再问）

每次提交若改变行为、口径或验证事实：手册头部「文档版本」bump + 「文档状态」重写 + **7.5 变更记录新增一行**（每版一行、不许合并），并核对 6.6（已知限制）与 7.4（问题追踪）。文档编辑插入时**不要锚相邻标题行**（历史上四次吃掉邻居）；改完跑 `[table]` 竖线计数与 `python scripts/check-docs.py`。
