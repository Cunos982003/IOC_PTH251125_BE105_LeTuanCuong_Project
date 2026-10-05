#!/bin/sh
# Health check script for Ride-Hailing services
# Checks HTTP endpoint at http://127.0.0.1:${SERVER_PORT}/api/v1/health
# Exits 0 if HTTP 200, non-zero otherwise

set -e

# Default port if not set
PORT="${SERVER_PORT:-8080}"

# Health endpoint
HEALTH_URL="http://127.0.0.1:${PORT}/api/v1/health"

# Use curl with timeout and silent mode
# -f: fail silently on HTTP errors (non-2xx)
# -s: silent mode
# -S: show error if -s fails
# --max-time: timeout in seconds
if curl -fsS --max-time 5 "${HEALTH_URL}" > /dev/null 2>&1; then
    exit 0
else
    exit 1
fi