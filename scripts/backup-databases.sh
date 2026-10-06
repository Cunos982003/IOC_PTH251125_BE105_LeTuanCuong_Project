#!/bin/bash
# Backup all PostgreSQL databases

set -euo pipefail

BACKUP_DIR="$HOME/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RETENTION_DAYS=7

# Database list
DATABASES=("user" "location" "dispatch" "payment")
CONTAINER="postgres"

mkdir -p "$BACKUP_DIR"

echo "Starting backup at $(date)"

for DB in "${DATABASES[@]}"; do
    BACKUP_FILE="$BACKUP_DIR/${DB}_${TIMESTAMP}.sql.gz"

    echo "→ Backing up database: $DB"
    docker exec -t $CONTAINER pg_dump -U ${DB}_app $DB | gzip > "$BACKUP_FILE"

    if [ -f "$BACKUP_FILE" ]; then
        SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
        echo "  ✓ Backup complete: $BACKUP_FILE ($SIZE)"
    else
        echo "  ✗ Backup failed: $DB"
    fi
done

# Clean up old backups (keep last 7 days)
echo "→ Cleaning up old backups (retention: $RETENTION_DAYS days)"
REMOVED=$(find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete -print | wc -l)
echo "  Removed $REMOVED old backup(s)"

echo "Backup completed at $(date)"
