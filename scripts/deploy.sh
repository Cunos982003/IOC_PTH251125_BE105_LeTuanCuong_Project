#!/bin/bash
# =============================================================================
# Deploy script for Ride-Hailing (run on VPS/Linux)
#   - Generates .env with random secrets if missing
#   - Pulls latest code, builds images, starts all services
#   - Waits until every container reports healthy
#
# Usage:  ./scripts/deploy.sh
# =============================================================================

set -euo pipefail

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
LOG_LINES="${LOG_LINES:-50}"

# Repo root (parent of scripts/)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=============================================="
echo " Ride-Hailing — Deploy"
echo "=============================================="

# --- 1. Secrets (.env) -------------------------------------------------------
if [[ ! -f .env ]]; then
  echo "→ .env not found, generating random secrets..."
  cat > .env <<EOF
JWT_SECRET=$(openssl rand -base64 48)
INTERNAL_KEY=$(openssl rand -hex 32)
POSTGRES_PASSWORD=$(openssl rand -hex 24)
USER_DB_PASSWORD=$(openssl rand -hex 24)
LOCATION_DB_PASSWORD=$(openssl rand -hex 24)
DISPATCH_DB_PASSWORD=$(openssl rand -hex 24)
PAYMENT_DB_PASSWORD=$(openssl rand -hex 24)
REDIS_PASSWORD=$(openssl rand -hex 24)
CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS:-}
IMAGE_TAG=latest
EOF
  chmod 600 .env
  echo "✓ .env generated. Passwords only apply to fresh volumes."
fi

# --- 2. Latest code ----------------------------------------------------------
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "→ Pulling latest code..."
  git pull --ff-only || echo "⚠ git pull skipped (uncommitted changes?)"
fi

# --- 3. Build + start --------------------------------------------------------
echo "→ Building and starting all services..."
docker compose --profile apps up -d --build

# --- 4. Wait for health ------------------------------------------------------
echo "→ Waiting for healthy (timeout ${HEALTH_TIMEOUT}s)..."
start=$(date +%s)
while true; do
  total=$(docker compose --profile apps ps --format '{{.Name}}' | wc -l | tr -d ' ')
  healthy=$(docker compose --profile apps ps --format '{{.Health}}' | grep -cx 'healthy' || true)

  if [[ "$total" -gt 0 && "$healthy" -eq "$total" ]]; then
    echo "✓ All ${healthy}/${total} containers healthy"
    break
  fi

  elapsed=$(( $(date +%s) - start ))
  if (( elapsed > HEALTH_TIMEOUT )); then
    echo "✗ Health check timeout after ${HEALTH_TIMEOUT}s (${healthy}/${total} healthy)" >&2
    docker compose --profile apps ps >&2
    echo "--- recent logs ---" >&2
    docker compose --profile apps logs --tail="$LOG_LINES" >&2
    exit 1
  fi

  echo "  waiting: ${healthy}/${total} healthy (${elapsed}s)"
  sleep 5
done

# --- 5. Status ---------------------------------------------------------------
echo
docker compose --profile apps ps
echo
echo "API: http://localhost:8000/api/v1/health"
echo "WS:  http://localhost:8001/api/v1/health"
echo "Logs: docker compose logs -f <service>"
