#!/usr/bin/env python3
"""
End-to-end latency test for Ride-Hailing system.
Measures: customer creates ride -> driver accepts -> customer receives driver_location.
Latency = now - sent_at from driver_location message.

Runs at 100, 500, 1000 driver loads for 5 minutes each.
Outputs p50/p95/p99/max and sample count, plus docker stats.
"""

import os
import sys
import asyncio
import json
import time
import random
import statistics
import csv
import httpx
import websockets
from typing import List, Dict
from dataclasses import dataclass
from datetime import datetime

# Config
API_BASE = os.getenv("API_BASE", "http://localhost:8000")
WS_CUSTOMER_URL = os.getenv("WS_CUSTOMER_URL", "ws://localhost:8001/ws/customer")
WS_DRIVER_URL = os.getenv("WS_DRIVER_URL", "ws://localhost:8001/ws/driver")
OUT_DIR = os.getenv("EVIDENCE_OUT", "tools/evidence/out")
DURATION_SECONDS = int(os.getenv("DURATION", "300"))
DRIVER_COUNTS = [int(x) for x in os.getenv("DRIVER_COUNTS", "100,500,1000").split(",")]

# Hanoi coordinates
PICKUP_LAT, PICKUP_LNG = 21.0285, 105.8542
DROPOFF_LAT, DROPOFF_LNG = 21.0385, 105.8642
EARTH_RADIUS_KM = 6371.0

def haversine(lat1, lng1, lat2, lng2) -> float:
    from math import radians, sin, cos, sqrt, atan2
    dlat = radians(lat2 - lat1)
    dlng = radians(lng2 - lng1)
    a = sin(dlat/2)**2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlng/2)**2
    return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1-a))

def offset_coord(lat, lng, distance_km, bearing_deg=0):
    from math import radians, sin, cos, asin, atan2, degrees
    brng = radians(bearing_deg)
    lat1 = radians(lat)
    lng1 = radians(lng)
    lat2 = asin(sin(lat1) * cos(distance_km/EARTH_RADIUS_KM) +
                cos(lat1) * sin(distance_km/EARTH_RADIUS_KM) * cos(brng))
    lng2 = lng1 + atan2(sin(brng) * sin(distance_km/EARTH_RADIUS_KM) * cos(lat1),
                        cos(distance_km/EARTH_RADIUS_KM) - sin(lat1) * sin(lat2))
    return degrees(lat2), degrees(lng2)

@dataclass
class Driver:
    label: str
    lat: float
    lng: float
    token: str = ""
    ws: websockets.WebSocketClientProtocol = None
    accepted_trip: str = ""

@dataclass
class LatencySample:
    trip_id: str
    driver_label: str
    sent_at: float
    received_at: float
    latency_ms: float

async def register_user(client: httpx.AsyncClient, email: str, password: str, role: str) -> str:
    for attempt in range(3):
        resp = await client.post(f"{API_BASE}/api/v1/auth/register",
            json={"email": email, "password": password, "fullName": email, "role": role})
        if resp.status_code == 409:
            # User exists, login instead
            resp = await client.post(f"{API_BASE}/api/v1/auth/login",
                json={"email": email, "password": password})
            resp.raise_for_status()
            return resp.json()["accessToken"]
        elif resp.status_code == 429:
            # Rate limited, wait and retry
            await asyncio.sleep(0.5 * (attempt + 1))
            continue
        else:
            resp.raise_for_status()
            return resp.json()["accessToken"]
    # Last attempt
    resp.raise_for_status()
    return resp.json()["accessToken"]

async def driver_ws_loop(driver: Driver, stop_event: asyncio.Event):
    try:
        async with websockets.connect(f"{WS_DRIVER_URL}") as ws:
            driver.ws = ws
            await ws.send(json.dumps({"t": "auth", "token": driver.token}))
            async for msg in ws:
                if stop_event.is_set():
                    break
                data = json.loads(msg)
                if data.get("t") == "offer" and not driver.accepted_trip:
                    trip_id = data.get("tripId")
                    driver.accepted_trip = trip_id
                    await ws.send(json.dumps({"t": "accept", "tripId": trip_id}))
                elif data.get("t") == "trip_update" and data.get("status") == "COMPLETED":
                    if data.get("tripId") == driver.accepted_trip:
                        driver.accepted_trip = ""
    except Exception as e:
        if not stop_event.is_set():
            pass

async def send_driver_location(driver: Driver, client: httpx.AsyncClient, stop_event: asyncio.Event, internal_key: str):
    while not stop_event.is_set():
        try:
            await client.post(
                f"{API_BASE}/api/v1/internal/drivers/location",
                headers={"X-Internal-Key": internal_key},
                json={"driver_id": driver.label, "lat": driver.lat, "lng": driver.lng}
            )
        except Exception:
            pass
        await asyncio.sleep(3)

async def customer_ws_loop(customer_token: str, stop_event: asyncio.Event, samples: List[LatencySample], trip_id: str):
    try:
        async with websockets.connect(f"{WS_CUSTOMER_URL}") as ws:
            await ws.send(json.dumps({"t": "auth", "token": customer_token}))
            await ws.send(json.dumps({"t": "subscribe", "tripId": trip_id}))
            async for msg in ws:
                if stop_event.is_set():
                    break
                data = json.loads(msg)
                if data.get("t") == "driver_location":
                    sent_at = data.get("sent_at")
                    if sent_at:
                        received_at = time.time()
                        latency = (received_at - sent_at) * 1000
                        samples.append(LatencySample(
                            trip_id=trip_id,
                            driver_label=data.get("driver_id", "unknown"),
                            sent_at=sent_at,
                            received_at=received_at,
                            latency_ms=latency
                        ))
    except Exception:
        pass

def percentiles(data: List[float]) -> Dict:
    if not data:
        return {"p50": 0, "p95": 0, "p99": 0, "max": 0, "count": 0, "mean": 0}
    sorted_data = sorted(data)
    n = len(sorted_data)
    return {
        "p50": sorted_data[int(n * 0.50)],
        "p95": sorted_data[int(n * 0.95)],
        "p99": sorted_data[int(n * 0.99)],
        "max": sorted_data[-1],
        "count": n,
        "mean": statistics.mean(data),
    }

async def get_docker_stats() -> Dict:
    import subprocess
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}},{{.MemUsage}}"],
            capture_output=True, text=True, timeout=10
        )
        stats = {}
        for line in result.stdout.strip().split('\n'):
            if line:
                parts = line.split(',')
                if len(parts) >= 3:
                    stats[parts[0]] = {"cpu": parts[1], "mem": parts[2]}
        return stats
    except Exception as e:
        return {"error": str(e)}

async def run_load_test(driver_count: int):
    print(f"\n{'='*60}")
    print(f"LOAD TEST: {driver_count} drivers, {DURATION_SECONDS}s duration")
    print(f"{'='*60}")

    os.makedirs(OUT_DIR, exist_ok=True)
    internal_key = os.getenv("INTERNAL_KEY", "test-internal-key")

    drivers = []
    for i in range(driver_count):
        bearing = (i * 360 / driver_count) % 360
        dist = random.uniform(0.1, 3.0)
        lat, lng = offset_coord(PICKUP_LAT, PICKUP_LNG, dist, bearing)
        drivers.append(Driver(label=f"driver_{i}", lat=lat, lng=lng))

    async with httpx.AsyncClient(timeout=10.0) as client:
        reg_tasks = []
        for d in drivers:
            reg_tasks.append(register_user(client, f"{d.label}@test.com", "password123", "DRIVER"))
            await asyncio.sleep(0.05)  # Small delay to avoid rate limiting
        tokens = await asyncio.gather(*reg_tasks, return_exceptions=True)
        for i, (d, token) in enumerate(zip(drivers, tokens)):
            if isinstance(token, Exception):
                print(f"  Driver {i} ({d.label}) ERROR: {token}")
                d.token = ""
            else:
                d.token = token

        valid_drivers = [d for d in drivers if d.token]
        print(f"  {len(valid_drivers)}/{driver_count} drivers registered")
        if not valid_drivers:
            return None

        customer_token = await register_user(client, "latency_customer@test.com", "password123", "CUSTOMER")

        stop_event = asyncio.Event()
        samples = []

        print("Starting driver location streams...")
        location_tasks = []
        ws_tasks = []
        for d in valid_drivers:
            location_tasks.append(asyncio.create_task(send_driver_location(d, client, stop_event, internal_key)))
            ws_tasks.append(asyncio.create_task(driver_ws_loop(d, stop_event)))

        await asyncio.sleep(5)

        print("Creating ride...")
        ride_resp = await client.post(
            f"{API_BASE}/api/v1/rides",
            headers={"Authorization": f"Bearer {customer_token}"},
            json={"pickupLat": PICKUP_LAT, "pickupLng": PICKUP_LNG,
                  "dropoffLat": DROPOFF_LAT, "dropoffLng": DROPOFF_LNG,
                  "idempotencyKey": f"latency-test-{driver_count}-{int(time.time())}"}
        )
        ride_resp.raise_for_status()
        trip_id = ride_resp.json()["tripId"]
        print(f"  Trip: {trip_id}")

        customer_ws_task = asyncio.create_task(customer_ws_loop(customer_token, stop_event, samples, trip_id))

        print(f"Running for {DURATION_SECONDS}s...")
        start_time = time.time()
        last_report = start_time

        while time.time() - start_time < DURATION_SECONDS:
            await asyncio.sleep(10)
            if time.time() - last_report >= 30:
                latencies = [s.latency_ms for s in samples]
                if latencies:
                    pct = percentiles(latencies)
                    print(f"  [{int(time.time()-start_time)}s] Samples: {len(latencies)}, p50: {pct['p50']:.1f}ms, p95: {pct['p95']:.1f}ms")
                last_report = time.time()

        stop_event.set()
        for t in ws_tasks + location_tasks:
            t.cancel()
        customer_ws_task.cancel()
        await asyncio.gather(*ws_tasks, *location_tasks, customer_ws_task, return_exceptions=True)

        docker_stats = await get_docker_stats()
        latencies = [s.latency_ms for s in samples]
        pct = percentiles(latencies)

        print(f"\nResults for {driver_count} drivers:")
        print(f"  Samples: {pct['count']}, p50: {pct['p50']:.2f}ms, p95: {pct['p95']:.2f}ms, p99: {pct['p99']:.2f}ms, max: {pct['max']:.2f}ms")

        csv_path = os.path.join(OUT_DIR, f"latency_e2e_{driver_count}.csv")
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["trip_id", "driver", "sent_at", "received_at", "latency_ms"])
            writer.writeheader()
            for s in samples:
                writer.writerow({"trip_id": s.trip_id, "driver": s.driver_label,
                               "sent_at": s.sent_at, "received_at": s.received_at, "latency_ms": s.latency_ms})

        return {
            "driver_count": driver_count, "duration": DURATION_SECONDS, "samples": pct["count"],
            "p50": pct["p50"], "p95": pct["p95"], "p99": pct["p99"], "max": pct["max"], "mean": pct["mean"],
            "docker_stats": docker_stats, "timestamp": datetime.now().isoformat(),
        }

async def main():
    print("=== End-to-End Latency Test ===")
    print(f"API: {API_BASE}")
    print(f"Driver counts: {DRIVER_COUNTS}, Duration: {DURATION_SECONDS}s")

    all_results = []
    for count in DRIVER_COUNTS:
        result = await run_load_test(count)
        if result:
            all_results.append(result)
        if count != DRIVER_COUNTS[-1]:
            print("\nCool down 30s...")
            await asyncio.sleep(30)

    print(f"\n{'='*60}")
    print("SUMMARY")
    print(f"{'Drivers':>8} | {'Samples':>8} | {'p50':>8} | {'p95':>8} | {'p99':>8} | {'max':>8} | {'mean':>8}")
    print("-" * 70)
    for r in all_results:
        print(f"{r['driver_count']:>8} | {r['samples']:>8} | {r['p50']:>8.1f} | {r['p95']:>8.1f} | {r['p99']:>8.1f} | {r['max']:>8.1f} | {r['mean']:>8.1f}")

    summary_csv = os.path.join(OUT_DIR, "latency_e2e_summary.csv")
    with open(summary_csv, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["driver_count", "duration", "samples", "p50", "p95", "p99", "max", "mean", "timestamp"])
        writer.writeheader()
        for r in all_results:
            writer.writerow({k: v for k, v in r.items() if k != "docker_stats"})

    import json
    summary_json = os.path.join(OUT_DIR, "latency_e2e_summary.json")
    with open(summary_json, "w") as f:
        json.dump(all_results, f, indent=2)

    print(f"\nSummary CSV: {summary_csv}")
    print(f"Summary JSON: {summary_json}")
    print("\nLIMITATIONS:")
    print("1. Clock skew between driver sender and customer receiver (same machine assumed)")
    print("2. Does not include network RTT from driver app to ws-gateway")
    print("3. sent_at timestamp set by driver app, not server")

if __name__ == "__main__":
    asyncio.run(main())