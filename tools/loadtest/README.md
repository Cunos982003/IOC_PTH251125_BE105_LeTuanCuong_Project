# Load Testing Tools

Python-based load testing tools for the ride-hailing WebSocket system.

## Requirements

- Python 3.11+
- Dependencies: `websockets`, `httpx`

Install dependencies:
```bash
pip install websockets httpx
```

## Scripts

### 1. prepare.py

Register N drivers and save their tokens.

```bash
# Local
python prepare.py 100

# For VPS (update API_BASE in script)
python prepare.py 500
```

Output: `tokens.json` (ignored by git)

### 2. drivers.py

Simulate drivers sending location updates.

```bash
# Local - 100 drivers for 5 minutes
python drivers.py --drivers 100 --duration 300

# VPS - 500 drivers with custom rejection rate
python drivers.py --drivers 500 --url wss://yourdomain.com/ws/driver --duration 600 --reject-rate 0.3
```

Parameters:
- `--drivers N`: Number of concurrent drivers (default: 100)
- `--url URL`: WebSocket URL (default: `ws://localhost:8001/ws/driver`)
- `--duration SEC`: Run duration in seconds (default: 300)
- `--reject-rate RATE`: Offer rejection rate 0.0-1.0 (default: 0.2)

Prints stats every 10 seconds: connected drivers, messages sent, errors.

### 3. latency.py

Measure WebSocket message latency.

```bash
# Local - single measurement
python latency.py --duration 300

# Local - load test with 100/500/1000 drivers
python latency.py --duration 300 --load-test

# VPS
python latency.py --url wss://yourdomain.com/ws/customer --duration 600 --output vps_latency.csv
```

Parameters:
- `--url URL`: WebSocket URL (default: `ws://localhost:8001/ws/customer`)
- `--duration SEC`: Measurement duration (default: 300)
- `--load-test`: Test with 100, 500, 1000 drivers (prompts to start drivers.py)
- `--output FILE`: CSV output file (default: `latency_results.csv`)
- `--token JWT`: Customer JWT (role=CUSTOMER). If omitted, read the `customer` key in tokens.json

Output: P50/P95/P99/Max latency, CSV results

**IMPORTANT NOTES:**
1. Run on the **same machine** as drivers.py to ensure clock synchronization
2. Latency includes network delay from test machine → server (conservative estimate)
3. **Requires a CUSTOMER-role account and an active trip:** `latency.py` connects to `/ws/customer`, which requires a token with `role=CUSTOMER` (not a driver token). Additionally, `driver_location` messages only flow to a customer who has an active trip with a driver (i.e., the dispatch has created a route). To use this script, either:
   - Register a customer account separately (via the user-service `/api/v1/auth/register` with `role=CUSTOMER`), store its token in `tokens.json` under the `"customer"` key, and create an active trip (via dispatch-service `/api/v1/rides`), OR
   - Pass a valid customer JWT with `--token <jwt>` and ensure an active trip exists for that customer.

**Limitations:**
1. Assumes `sent_at` timestamp from driver is accurate and clocks are synchronized
2. Single customer measurement may not reflect all message routing paths in the system
3. Without an active trip, the customer WebSocket will connect but receive **no `driver_location` messages** (the script will report 0 latency samples)

## Example Workflow - Local

```bash
# Terminal 1: Start services
docker-compose up

# Terminal 2: Prepare drivers
cd tools/loadtest
python prepare.py 100

# Terminal 3: Run drivers
python drivers.py --drivers 100 --duration 300

# Terminal 4: Measure latency (after drivers running)
python latency.py --duration 300

# Terminal 5: Monitor resources
docker stats
```

## Example Workflow - VPS

Update URLs in commands:
- API: `https://yourdomain.com`
- WebSocket: `wss://yourdomain.com/ws/driver` (drivers) and `wss://yourdomain.com/ws/customer` (customer)

```bash
# Prepare (one time)
python prepare.py 500

# Run drivers
python drivers.py --drivers 500 --url wss://yourdomain.com/ws/driver --duration 600

# Measure latency (separate terminal)
python latency.py --url wss://yourdomain.com/ws/customer --duration 600
```

## Expected Results (Local, 100 drivers, 5 min)

- P95 latency: < 100ms (typically 20-50ms on localhost)
- Connected: 100/100
- Messages sent: ~7500 (100 drivers × 15 updates × 5 minutes)

Check `docker stats` for resource usage:
- ws-gateway: ~200-300MB RAM, ~10-20% CPU
- Redis: ~50-100MB RAM
- PostgreSQL: ~100-200MB RAM
