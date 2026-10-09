# scripts/

工程脚本与质量门禁。按职能分四个子目录：

| 目录 | 内容 | 跑法 |
|---|---|---|
| `db/` | 数据库运维：增量迁移、备份、本地环境生成 | `bash scripts/db/db-migrate.sh [--check]` |
| `gates/` | 文档与配置一致性门禁（Python） | `python scripts/gates/check-docs.py` |
| `frontend/` | 前端 Node 桩测（零依赖、直接 import 前端源码） | `cd frontend && npm run check:xxx` |
| `smoke/` | 对真实实例的 HTTP/WS 全链路冒烟 | `BASE=http://127.0.0.1:8091 bash scripts/smoke/smoke.sh` |

## 在 CI 里怎么跑

`.github/workflows/ci.yml` 三个 job：

- **backend**：`./mvnw test`
- **frontend**：`npm ci` → lint → type:check → `npm run check:*`（9 道桩测）→ build
- **gates**：`python scripts/gates/check-docs.py`、`python scripts/gates/check-config.py`、`bash -n scripts/smoke/smoke.sh`

`smoke.sh` 全链路刻意不进 CI——它需要真实数据库和 LLM Key，放在本机/真机手动跑。

## 新增脚本放哪

- 新数据库相关脚本 → `db/`
- 新文档/配置门禁 → `gates/`
- 新前端桩测 → `frontend/`，并在 `frontend/package.json` 加 `check:xxx` script
- 新冒烟项 → `smoke/smoke.sh` 里加节
- 改了路径后必须同步：ci.yml、package.json、AGENTS.md、`frontend/check-registries.mjs`（它自己就是 scripts 清单的守卫）
