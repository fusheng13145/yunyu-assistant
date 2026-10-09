#!/usr/bin/env bash
# ============================================================
# MySQL 备份：mysqldump + gzip，按保留天数清理旧备份。
#
# 环境变量：DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD / BACKUP_DIR / BACKUP_KEEP_DAYS
# 密码经 MYSQL_PWD 传入，不出现在进程列表；备份文件名含时间戳。
#
# 用法：
#   scripts/db/backup-mysql.sh
# 定时（cron，每天 03:30；与归档任务 03:00 错开，避免备份撞上批量搬数据）：
#   30 3 * * * BACKUP_DIR=/var/backups/yunyu DB_USER=... DB_PASSWORD=... /opt/yunyu/scripts/db/backup-mysql.sh >> /var/log/yunyu-backup.log 2>&1
#
# 恢复：
#   gunzip < yunyu_assistant-YYYYMMDD-HHMMSS.sql.gz | mysql --default-character-set=utf8mb4 -u root -p
#
# 备份必须离开本机才算数：BACKUP_DIR 指向异地盘/对象存储，或用 rclone 同步。
# ============================================================
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-yunyu_assistant}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
: "${DB_USER:?DB_USER 未设置}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置}"
export MYSQL_PWD="$DB_PASSWORD"

mkdir -p "$BACKUP_DIR"
stamp="$(date +%Y%m%d-%H%M%S)"
target="${BACKUP_DIR}/${DB_NAME}-${stamp}.sql.gz"

# --single-transaction: InnoDB 一致性快照，不锁表（业务可继续写入）
# --routines/--triggers: 连存储过程与触发器一起导出（迁移辅助过程正常运行时已被清理）
# --set-gtid-purged=OFF: 便于恢复到非主从环境的实例
# --no-tablespaces: 应用账号无 PROCESS 权限，去掉它 mysqldump 会对每个表空间报 Access denied（8.0.21+）
mysqldump --default-character-set=utf8mb4 -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
    --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces \
    --databases "$DB_NAME" | gzip > "$target"

# gzip 只验证完整性（-t）并检查非空，避免"备份了个空文件"这种事后才发现的事故
gzip -t "$target"
size_bytes=$(wc -c < "$target" | tr -d ' ')
if [ "${size_bytes:-0}" -lt 1024 ]; then
    echo "[backup] 失败：备份文件仅 ${size_bytes} 字节，疑似空库或导出被中断 -> $target" >&2
    exit 1
fi

find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" -type f -mtime +"$BACKUP_KEEP_DAYS" -delete

echo "[backup] OK -> $target ($(du -h "$target" | cut -f1 | tr -d ' ')，保留 ${BACKUP_KEEP_DAYS} 天)"
