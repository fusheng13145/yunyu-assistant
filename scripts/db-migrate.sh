#!/usr/bin/env bash
# ============================================================
# 云谕助手 —— 数据库增量迁移（v2.29）
#
# 用途：把"已部署的库"演进到当前代码要求的 schema，替代此前"手工执行 ALTER"的做法。
#       新环境不需要本脚本建表（用 index.sql），但仍建议跑一次以登记迁移版本。
#
# 环境变量（与后端一致）：DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD
#   密码经 MYSQL_PWD 传给客户端，不出现在进程列表里。
#
# 用法：
#   scripts/db-migrate.sh --check    # 只列出待应用迁移，不改库
#   scripts/db-migrate.sh            # 应用全部待应用迁移
#
# 迁移文件：backend/src/main/resources/db/migrations/NNNN_简述.sql，按文件名顺序应用；
#           已应用版本记录在 schema_migrations 表；每条迁移自带存在性守卫，重复执行为 no-op。
# 新增 schema 变更 = 新增一个迁移文件，不要改 index.sql 里已发布过的部分。
# ============================================================
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-yunyu_assistant}"
: "${DB_USER:?DB_USER 未设置}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置}"
export MYSQL_PWD="$DB_PASSWORD"

MIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../backend/src/main/resources/db/migrations" && pwd)"
HELPERS="$(dirname "$MIG_DIR")/migrate-helpers.sql"

run_sql() {
    mysql --default-character-set=utf8mb4 -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" "$@"
}

MODE="${1:-apply}"

# 账本表（旧库可能没有：v2.29 之前的库不存在该表，这里补建而非报错）
run_sql -e "CREATE TABLE IF NOT EXISTS \`schema_migrations\` (
    \`version\` VARCHAR(128) NOT NULL COMMENT '迁移文件名（不含扩展名）',
    \`applied_at\` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '应用时间',
    PRIMARY KEY (\`version\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已应用增量迁移登记表';"

pending=()
while IFS= read -r f; do
    version="$(basename "$f" .sql)"
    applied="$(run_sql -N -e "SELECT COUNT(*) FROM schema_migrations WHERE version='${version}';")"
    [ "$applied" = "0" ] && pending+=("$f|$version")
done < <(find "$MIG_DIR" -maxdepth 1 -name '*.sql' | sort)

if [ "${#pending[@]}" -eq 0 ]; then
    echo "[migrate] 无待应用迁移，schema 已是最新（已登记 $(run_sql -N -e "SELECT COUNT(*) FROM schema_migrations;") 条）"
    exit 0
fi

echo "[migrate] 待应用迁移 ${#pending[@]} 条："
for item in "${pending[@]}"; do
    echo "  - ${item##*|}"
done

if [ "$MODE" = "--check" ]; then
    exit 0
fi

run_sql < "$HELPERS"

for item in "${pending[@]}"; do
    file="${item%%|*}"
    version="${item##*|}"
    echo "[migrate] 应用 ${version} ..."
    run_sql < "$file"
    run_sql -e "INSERT INTO schema_migrations (version) VALUES ('${version}');"
done

run_sql -e "DROP PROCEDURE IF EXISTS yunyu_add_column; DROP PROCEDURE IF EXISTS yunyu_add_index;"
echo "[migrate] 完成，共应用 ${#pending[@]} 条迁移；辅助过程已从库中移除"
