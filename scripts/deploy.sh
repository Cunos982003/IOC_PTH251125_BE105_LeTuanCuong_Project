#!/bin/bash
# Generate production .env with strong passwords

set -euo pipefail

DOMAIN="${1:-ride-api.yourdomain.com}"
OUTPUT_FILE=".env"

# Generate random password (32 characters)
gen_pass() {
    openssl rand -base64 32 | tr -d "=+/" | cut -c1-32
}

cat > "$OUTPUT_FILE" <<EOF
# Production Environment Variables
# Generated: $(date)

# Domain
DOMAIN=$DOMAIN

# Database passwords (IMPORTANT: Change these from default)
POSTGRES_PASSWORD=$(gen_pass)
USER_DB_PASSWORD=$(gen_pass)
LOCATION_DB_PASSWORD=$(gen_pass)
DISPATCH_DB_PASSWORD=$(gen_pass)
PAYMENT_DB_PASSWORD=$(gen_pass)

# Redis password
REDIS_PASSWORD=$(gen_pass)

# JWT secret (min 256 bits)
JWT_SECRET=$(gen_pass)

# Internal service key
INTERNAL_KEY=$(gen_pass)

# Port bindings (only localhost)
API_GATEWAY_PORT=127.0.0.1:8000
WS_GATEWAY_PORT_1=127.0.0.1:8001
WS_GATEWAY_PORT_2=127.0.0.1:8011

# Service URLs (internal Docker network)
USER_SERVICE_URL=http://user-service:8081
LOCATION_SERVICE_URL=http://location-service:8082
DISPATCH_SERVICE_URL=http://dispatch-service:8083
PRICING_SERVICE_URL=http://pricing-service:8084
PAYMENT_SERVICE_URL=http://payment-service:8085
WS_GATEWAY_URL=http://ws-gateway-1:8001

# Replica configuration
WS_GATEWAY_REPLICAS=2
EOF

chmod 600 "$OUTPUT_FILE"
echo "✓ .env generated: $OUTPUT_FILE"
echo "⚠  Passwords only apply to NEW volumes. For existing volumes, use ALTER USER."
echo ""
echo "Next steps:"
echo "1. Review .env file"
echo "2. Copy to project directory: cp .env ~/ride-hailing/.env"
echo "3. Run deployment: cd ~/ride-hailing && ./deploy.sh"
```

Tạo script deploy.sh:

<function_calls>
<invoke name="Write">
<parameter name="content">#!/bin/bash
# Production deployment script

set -euo pipefail

COMPOSE_FILE="docker-compose.yml"
HEALTH_TIMEOUT=120
LOG_LINES=50

echo "=========================================="
echo "Starting deployment..."
echo "=========================================="

# Pull latest code
echo "→ Pulling latest code..."
git pull origin main

# Build services
echo "→ Building services..."
docker compose --profile apps build

# Deploy ws-gateway replicas one by one to avoid mass WebSocket disconnect
echo "→ Deploying ws-gateway replica 1..."
docker compose up -d ws-gateway-1
sleep 5

echo "→ Deploying ws-gateway replica 2..."
docker compose up -d ws-gateway-2
sleep 5

# Deploy other services
echo "→ Deploying other services..."
docker compose --profile apps up -d

# Wait for health checks
echo "→ Waiting for services to be healthy..."
start_time=$(date +%s)

while true; do
    unhealthy=$(docker compose ps --format json | jq -r 'select(.Health != "" and .Health != "healthy") | .Service' 2>/dev/null || true)

    if [ -z "$unhealthy" ]; then
        echo "✓ All services healthy"
        break
    fi

    elapsed=$(($(date +%s) - start_time))
    if [ $elapsed -gt $HEALTH_TIMEOUT ]; then
        echo "✗ Health check timeout after ${HEALTH_TIMEOUT}s"
        echo "Unhealthy services:"
        echo "$unhealthy"
        echo
        echo "Last $LOG_LINES lines of logs:"
        echo "$unhealthy" | while read -r service; do
            echo "--- $service ---"
            docker compose logs --tail=$LOG_LINES "$service"
        done
        exit 1
    fi

    echo "  Waiting for: $unhealthy (${elapsed}s elapsed)"
    sleep 5
done

# Show status
echo
echo "=========================================="
echo "Deployment complete"
echo "=========================================="
docker compose ps

echo
echo "Service URLs:"
echo "  API: https://ride-api.yourdomain.com/api/v1/"
echo "  WebSocket: wss://ride-api.yourdomain.com/ws"
echo
echo "Useful commands:"
echo "  Logs: docker compose logs -f <service-name>"
echo "  Stats: docker stats"
echo "  Status: docker compose ps"
