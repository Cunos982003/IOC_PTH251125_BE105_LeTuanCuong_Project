#!/bin/bash
# Test restore to temporary database

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <backup_file.sql.gz>"
    echo "Example: $0 ~/backups/user_20260106_020000.sql.gz"
    exit 1
fi

BACKUP_FILE="$1"
CONTAINER="postgres"
TEST_DB="test_restore_$(date +%s)"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "Error: Backup file not found: $BACKUP_FILE"
    exit 1
fi

echo "→ Creating test database: $TEST_DB"
docker exec -t $CONTAINER psql -U postgres -c "CREATE DATABASE $TEST_DB;"

echo "→ Restoring from: $BACKUP_FILE"
gunzip -c "$BACKUP_FILE" | docker exec -i $CONTAINER psql -U postgres -d $TEST_DB

echo "→ Verifying tables"
TABLE_COUNT=$(docker exec -t $CONTAINER psql -U postgres -d $TEST_DB -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';" | tr -d ' \n\r')
echo "  Found $TABLE_COUNT table(s)"

docker exec -t $CONTAINER psql -U postgres -d $TEST_DB -c "\dt"

echo "→ Cleaning up test database"
docker exec -t $CONTAINER psql -U postgres -c "DROP DATABASE $TEST_DB;"

echo "✓ Restore test successful"
