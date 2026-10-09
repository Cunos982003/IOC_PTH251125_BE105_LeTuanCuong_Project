#!/bin/bash
set -a
source .env
set +a
# =============================================================================
# Deploy script for Ride-Hailing (run on VPS/Linux)
#   - Pulls images from GHCR using IMAGE_TAG
#   - Rolling update: updates services one by one, ws-gateway replicas sequentially
#   - Saves deployed tag to .deployed-tag for rollback
#   - Supports rollback: ./scripts/deploy.sh <old-tag>
#
# Usage:  ./scripts/deploy.sh [TAG]
#   TAG defaults to $IMAGE_TAG env var, then 'latest'
#   If TAG is an existing tag in .deployed-tag history, does rollback
# =============================================================================

set -euo pipefail

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
LOG_LINES="${LOG_LINES:-50}"

# Repo root (parent of scripts/)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Determine tag
REQUESTED_TAG="${1:-${IMAGE_TAG:-latest}}"
DEPLOYED_TAG_FILE="$ROOT/.deployed-tag"

echo "=============================================="
echo " Ride-Hailing — Deploy"
echo " Tag: $REQUESTED_TAG"
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

# Update IMAGE_TAG in .env
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$REQUESTED_TAG/" .env

# --- 2. Login to GHCR --------------------------------------------------------
echo "→ Logging into GHCR..."
if [[ -n "${GHCR_TOKEN:-}" ]]; then
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "${GHCR_USER:-}" --password-stdin
else
  echo "⚠ GHCR_TOKEN not set, assuming already logged in"
fi

# --- 3. Pull images ----------------------------------------------------------
echo "→ Pulling images for tag $REQUESTED_TAG..."
docker compose --profile apps --profile ha pull

# --- 4. Rolling update -------------------------------------------------------
# Order: infrastructure-independent services first, then dependent ones
# ws-gateway replicas updated sequentially to maintain availability

SERVICES_ORDER=(
  "user-service"
  "location-service"
  "pricing-service"
  "payment-service"
  "dispatch-service"
  "api-gateway"
)

echo "→ Rolling update for services: ${SERVICES_ORDER[*]}"
for svc in "${SERVICES_ORDER[@]}"; do
  echo "  Updating $svc..."
  docker compose --profile apps --profile ha up -d --no-deps "$svc"

  # Wait for this service to be healthy
  start=$(date +%s)
  while true; do
    health=$(docker inspect --format='{{.State.Health.Status}}' "ridehailing-${svc}-1" 2>/dev/null || echo "unknown")
    if [[ "$health" == "healthy" ]]; then
      echo "  ✓ $svc healthy"
      break
    fi
    elapsed=$(( $(date +%s) - start ))
    if (( elapsed > HEALTH_TIMEOUT )); then
      echo "  ✗ $svc health check timeout after ${HEALTH_TIMEOUT}s" >&2
      exit 1
    fi
    sleep 3
  done
done

# --- 5. Update ws-gateway replicas sequentially ------------------------------
echo "→ Updating ws-gateway replicas sequentially..."
for replica in 1 2; do
  svc="ws-gateway"
  if [[ $replica -eq 2 ]]; then
    svc="ws-gateway-2"
  fi
  echo "  Updating $svc..."
  docker compose --profile apps --profile ha up -d --no-deps "$svc"

  start=$(date +%s)
  while true; do
    health=$(docker inspect --format='{{.State.Health.Status}}' "ridehailing-${svc}-1" 2>/dev/null || echo "unknown")
    if [[ "$health" == "healthy" ]]; then
      echo "  ✓ $svc healthy"
      break
    fi
    elapsed=$(( $(date +%s) - start ))
    if (( elapsed > HEALTH_TIMEOUT )); then
      echo "  ✗ $svc health check timeout after ${HEALTH_TIMEOUT}s" >&2
      exit 1
    fi
    sleep 3
  done
done

# --- 6. Deploy web app -------------------------------------------------------
echo "→ Deploying web app to /var/www/ride..."
if [[ -d web ]]; then
  if command -v rsync >/dev/null 2>&1; then
    sudo rsync -a --delete web/ /var/www/ride/
  else
    sudo cp -r web/* /var/www/ride/
  fi
  sudo chown -R deploy:deploy /var/www/ride 2>/dev/null || true
  echo "✓ Web app deployed"
else
  echo "⚠ web/ directory not found, skipping"
fi

# --- 7. Save deployed tag ----------------------------------------------------
echo "$REQUESTED_TAG" > "$DEPLOYED_TAG_FILE"
echo "✓ Saved deployed tag to $DEPLOYED_TAG_FILE"

# --- 8. Final health check ---------------------------------------------------
echo "→ Final health check on https://ridehailing.duckdns.org/api/v1/health..."
start=$(date +%s)
while true; do
  if curl -sf "https://ridehailing.duckdns.org/api/v1/health" >/dev/null; then
    echo "✓ Health endpoint UP"
    break
  fi
  elapsed=$(( $(date +%s) - start ))
  if (( elapsed > HEALTH_TIMEOUT )); then
    echo "✗ Health check timeout after ${HEALTH_TIMEOUT}s" >&2
    exit 1
  fi
  sleep 3
done

# --- 9. Status ---------------------------------------------------------------
echo
docker compose --profile apps --profile ha ps
echo
echo "✓ Deploy complete"
echo "API:  https://ridehailing.duckdns.org/api/v1/health"
echo "WS:   https://ridehailing.duckdns.org/ws/customer"
echo "Web:  https://ridehailing.duckdns.org"
echo "Logs: docker compose logs -f <service>"
echo
echo "Rollback: ./scripts/deploy.sh <previous-tag>"
echo "Available tags: $(cat "$DEPLOYED_TAG_FILE" 2>/dev/null || echo 'none')"
