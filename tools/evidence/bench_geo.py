#!/usr/bin/env python3
"""
Redis GEOSEARCH benchmark for Ride-Hailing location-service.
Measures p50/p95/p99 latency at 10k, 50k, 100k drivers.
Two modes:
  (a) Direct Redis (b) Via HTTP /internal/drivers/nearby
"""

import os
import sys
import time
import random
import statistics
import csv
import asyncio
import redis.asyncio as redis
import httpx
from typing import List, Tuple

# Config from environment
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")
LOCATION_SERVICE_URL = os.getenv("LOCATION_SERVICE_URL", "http://localhost:8082")
BENCH_KEY = "bench:geo"
OUT_DIR = os.getenv("EVIDENCE_OUT", "tools/evidence/out")

# Hanoi bounding box (approximate)
HANOI_LAT_MIN, HANOI_LAT_MAX = 20.9, 21.2
HANOI_LNG_MIN, HANOI_LNG_MAX = 105.7, 106.0

RADIUS_KM = 2
COUNT = 10
QUERIES_PER_SCALE = 1000


def random_hanoi_coord() -> Tuple[float, float]:
    """Generate random coordinate within Hanoi bounds."""
    lat = random.uniform(HANOI_LAT_MIN, HANOI_LAT_MAX)
    lng = random.uniform(HANOI_LNG_MIN, HANOI_LNG_MAX)
    return lng, lat  # Redis GEO uses (longitude, latitude)


async def populate_redis(r: redis.Redis, n: int):
    """Populate bench:geo with n random drivers around Hanoi."""
    print(f"  Populating {n:,} drivers...")
    pipe = r.pipeline()
    for i in range(n):
        lng, lat = random_hanoi_coord()
        pipe.geoadd(BENCH_KEY, (lng, lat, f"driver:{i}"))
        if i % 5000 == 0:
            await pipe.execute()
            pipe = r.pipeline()
    await pipe.execute()
    count = await r.zcard(BENCH_KEY)
    print(f"  Done. Actual count: {count:,}")


async def bench_redis_direct(r: redis.Redis, n_queries: int) -> List[float]:
    """Benchmark GEOSEARCH directly on Redis."""
    latencies = []
    for _ in range(n_queries):
        lng, lat = random_hanoi_coord()
        start = time.perf_counter()
        await r.geosearch(BENCH_KEY, member=None, longitude=lng, latitude=lat,
                          radius=RADIUS_KM, unit="km", withcoord=False, withdist=False,
                          count=COUNT)
        latencies.append((time.perf_counter() - start) * 1000)  # ms
    return latencies


async def bench_via_http(client: httpx.AsyncClient, n_queries: int, internal_key: str) -> List[float]:
    """Benchmark via location-service /internal/drivers/nearby."""
    latencies = []
    for _ in range(n_queries):
        lng, lat = random_hanoi_coord()
        start = time.perf_counter()
        try:
            resp = await client.get(
                f"{LOCATION_SERVICE_URL}/internal/drivers/nearby",
                params={"lng": lng, "lat": lat, "radius_km": RADIUS_KM, "limit": COUNT},
                headers={"X-Internal-Key": internal_key},
                timeout=5.0
            )
            resp.raise_for_status()
        except Exception as e:
            print(f"  HTTP error: {e}")
            latencies.append(None)
            continue
        latencies.append((time.perf_counter() - start) * 1000)
    return [l for l in latencies if l is not None]


def percentiles(data: List[float]) -> dict:
    if not data:
        return {"p50": None, "p95": None, "p99": None, "count": 0}
    sorted_data = sorted(data)
    return {
        "p50": sorted_data[int(len(sorted_data) * 0.50)],
        "p95": sorted_data[int(len(sorted_data) * 0.95)],
        "p99": sorted_data[int(len(sorted_data) * 0.99)],
        "count": len(data),
        "mean": statistics.mean(data),
        "stdev": statistics.stdev(data) if len(data) > 1 else 0,
    }


async def run_bench():
    os.makedirs(OUT_DIR, exist_ok=True)

    # Connect to Redis
    r = redis.Redis(
        host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASSWORD or None,
        decode_responses=True, socket_timeout=5, socket_connect_timeout=5
    )

    # Test connection
    try:
        await r.ping()
        print(f"Connected to Redis at {REDIS_HOST}:{REDIS_PORT}")
    except Exception as e:
        print(f"Failed to connect to Redis: {e}")
        return

    # HTTP client for location-service
    async with httpx.AsyncClient(timeout=10.0) as http_client:
        scales = [10_000, 50_000, 100_000]
        results = []

        for scale in scales:
            print(f"\n=== Scale: {scale:,} drivers ===")

            # Clear and repopulate
            await r.delete(BENCH_KEY)
            await populate_redis(r, scale)

            # (a) Direct Redis
            print("  Benchmarking direct Redis GEOSEARCH...")
            redis_lats = await bench_redis_direct(r, QUERIES_PER_SCALE)
            redis_pct = percentiles(redis_lats)

            # (b) Via HTTP
            print("  Benchmarking via HTTP location-service...")
            internal_key = os.getenv("INTERNAL_KEY", "test-internal-key")
            http_lats = await bench_via_http(http_client, QUERIES_PER_SCALE, internal_key)
            http_pct = percentiles(http_lats)

            results.append({
                "scale": scale,
                "redis_p50": redis_pct["p50"], "redis_p95": redis_pct["p95"], "redis_p99": redis_pct["p99"],
                "http_p50": http_pct["p50"], "http_p95": http_pct["p95"], "http_p99": http_pct["p99"],
                "redis_count": redis_pct["count"], "http_count": http_pct["count"],
            })

            print(f"  Redis:  p50={redis_pct['p50']:.2f}ms p95={redis_pct['p95']:.2f}ms p99={redis_pct['p99']:.2f}ms")
            print(f"  HTTP:   p50={http_pct['p50']:.2f}ms p95={http_pct['p95']:.2f}ms p99={http_pct['p99']:.2f}ms")
            if http_pct["p50"] and redis_pct["p50"]:
                overhead = http_pct["p50"] - redis_pct["p50"]
                print(f"  HTTP overhead: +{overhead:.2f}ms ({'WARN >2ms' if overhead > 2 else 'OK'})")

        # Cleanup
        await r.delete(BENCH_KEY)
        await r.aclose()

        # Write CSV
        csv_path = os.path.join(OUT_DIR, "bench_geo.csv")
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=[
                "scale", "redis_p50", "redis_p95", "redis_p99",
                "http_p50", "http_p95", "http_p99", "redis_count", "http_count"
            ])
            writer.writeheader()
            writer.writerows(results)
        print(f"\nCSV written to {csv_path}")

        # Print summary table
        print("\n=== SUMMARY ===")
        print(f"{'Scale':>10} | {'Redis p50':>10} | {'Redis p95':>10} | {'Redis p99':>10} | {'HTTP p50':>10} | {'HTTP p95':>10} | {'HTTP p99':>10} | {'Overhead':>8}")
        print("-" * 100)
        for row in results:
            overhead = (row["http_p50"] - row["redis_p50"]) if row["http_p50"] and row["redis_p50"] else None
            print(f"{row['scale']:>10,} | {row['redis_p50']:>10.2f} | {row['redis_p95']:>10.2f} | {row['redis_p99']:>10.2f} | "
                  f"{row['http_p50']:>10.2f} | {row['http_p95']:>10.2f} | {row['http_p99']:>10.2f} | "
                  f"{overhead:>7.2f}ms" if overhead else "N/A")

        return results


if __name__ == "__main__":
    asyncio.run(run_bench())