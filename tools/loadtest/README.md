# Load test helpers

## 1) Prepare drivers

python tools/loadtest/prepare.py --drivers 100 --url http://localhost:8000

- Writes `tools/loadtest/tokens.json` (gitignored via repo `.gitignore`)
- Idempotent: existing tokens are reused and only missing drivers are logged in again

## 2) Start simulated drivers locally

python tools/loadtest/drivers.py --drivers 100 --url ws://localhost:8001/ws/driver --duration 300

- Sends `auth` then `location` every ~4s with ±0.5s jitter
- Accepts offers after 1–3 seconds unless `--reject-rate` skips them
- Reconnects with exponential backoff + jitter after disconnects

## 3) Measure latency from a virtual customer

python tools/loadtest/latency.py --drivers 100 --url ws://localhost:8001/ws/customer --api-url http://localhost:8000 --duration 300 --load-levels 100 500 1000 --internal-key "$env:INTERNAL_KEY"

- Targets driver index 0 by default
- Prints p50/p95/p99/max, sample count, and a comparison table for the selected load levels
- Writes CSV to `tools/loadtest/latency.csv`

## 4) Run against a VPS

python tools/loadtest/prepare.py --drivers 100 --url https://<domain>
python tools/loadtest/drivers.py --drivers 100 --url wss://<domain>/ws/driver --duration 300
python tools/loadtest/latency.py --drivers 100 --url wss://<domain>/ws/customer --api-url https://<domain> --duration 300 --load-levels 100 500 1000 --internal-key "$env:INTERNAL_KEY"

## 5) Container health snapshot

docker stats --no-stream

This shows CPU/RAM usage for each container while the load test is running.
