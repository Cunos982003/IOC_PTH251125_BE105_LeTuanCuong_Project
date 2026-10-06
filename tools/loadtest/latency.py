#!/usr/bin/env python3
"""
Measure WebSocket latency by receiving driver_location messages.
Assumes one customer has an active trip with driver 0.
"""
import asyncio
import json
import sys
import time
import csv
import argparse
from pathlib import Path
import statistics
import websockets


class LatencyMeasurer:
    def __init__(self, customer_token: str, ws_url: str):
        self.customer_token = customer_token
        self.ws_url = ws_url
        self.latencies = []
        self.connected = False

    async def run(self, duration: int):
        """Connect and collect latency samples for duration seconds."""
        try:
            async with websockets.connect(
                self.ws_url,
                extra_headers={"Authorization": f"Bearer {self.customer_token}"}
            ) as ws:
                self.connected = True

                # Send auth
                auth_msg = {"t": "auth", "token": self.customer_token}
                await ws.send(json.dumps(auth_msg))

                # Collect samples
                end_time = time.time() + duration
                async for message in ws:
                    if time.time() >= end_time:
                        break

                    try:
                        data = json.loads(message)
                        if data.get("t") == "driver_location" and "sent_at" in data:
                            now = time.time()
                            sent_at_ms = data["sent_at"]
                            # sent_at is epoch milliseconds; convert to seconds for the delta.
                            latency_ms = (now * 1000 - sent_at_ms)
                            self.latencies.append(latency_ms)
                    except (json.JSONDecodeError, KeyError):
                        continue
        except Exception as e:
            print(f"Connection error: {e}", file=sys.stderr)
            self.connected = False

    def get_stats(self) -> dict:
        """Calculate latency percentiles."""
        if not self.latencies:
            return {"count": 0, "p50": 0, "p95": 0, "p99": 0, "max": 0}

        sorted_lat = sorted(self.latencies)
        count = len(sorted_lat)

        return {
            "count": count,
            "p50": statistics.quantiles(sorted_lat, n=100)[49] if count >= 2 else sorted_lat[0],
            "p95": statistics.quantiles(sorted_lat, n=100)[94] if count >= 2 else sorted_lat[0],
            "p99": statistics.quantiles(sorted_lat, n=100)[98] if count >= 2 else sorted_lat[0],
            "max": max(sorted_lat)
        }


async def measure_single(customer_token: str, ws_url: str, duration: int) -> dict:
    """Run single measurement."""
    measurer = LatencyMeasurer(customer_token, ws_url)
    await measurer.run(duration)
    return measurer.get_stats()


async def measure_with_load_levels(
    customer_token: str,
    ws_url: str,
    duration: int,
    load_levels: list[int]
) -> list[dict]:
    """Measure latency at different load levels."""
    results = []

    for num_drivers in load_levels:
        print(f"\n{'=' * 60}")
        print(f"Testing with {num_drivers} drivers")
        print(f"{'=' * 60}")
        print(f"Start drivers.py with --drivers {num_drivers} in another terminal")
        print("Press Enter when drivers are running...")
        input()

        stats = await measure_single(customer_token, ws_url, duration)
        stats["drivers"] = num_drivers
        results.append(stats)

        print(f"\nResults for {num_drivers} drivers:")
        print(f"  Samples: {stats['count']}")
        print(f"  P50: {stats['p50']:.1f}ms")
        print(f"  P95: {stats['p95']:.1f}ms")
        print(f"  P99: {stats['p99']:.1f}ms")
        print(f"  Max: {stats['max']:.1f}ms")

    return results


def print_comparison_table(results: list[dict]):
    """Print comparison table across load levels."""
    print(f"\n{'=' * 60}")
    print("LATENCY COMPARISON")
    print(f"{'=' * 60}")
    print(f"{'Drivers':<10} {'Samples':<10} {'P50 (ms)':<12} {'P95 (ms)':<12} {'P99 (ms)':<12} {'Max (ms)':<12}")
    print("-" * 60)

    for r in results:
        print(f"{r['drivers']:<10} {r['count']:<10} "
              f"{r['p50']:<12.1f} {r['p95']:<12.1f} "
              f"{r['p99']:<12.1f} {r['max']:<12.1f}")


def save_csv(results: list[dict], filename: str):
    """Save results to CSV."""
    with open(filename, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["drivers", "count", "p50", "p95", "p99", "max"])
        writer.writeheader()
        writer.writerows(results)

    print(f"\n✓ Results saved to {filename}")


async def main():
    parser = argparse.ArgumentParser(
        description="Measure WebSocket latency",
        epilog="""
NOTES:
1. Run this script on the SAME machine as drivers.py to use the same clock.
   Running on different machines requires NTP synchronization.
2. Latency includes network delay from test machine to server,
   so this is a conservative (upper bound) estimate.

LIMITATIONS:
1. Assumes sent_at timestamp from driver is accurate and synchronized.
2. Single customer measurement may not reflect all routing paths.
"""
    )
    parser.add_argument("--url", default="ws://localhost:8001/ws/customer", help="WebSocket URL")
    parser.add_argument("--duration", type=int, default=300, help="Duration in seconds (default: 300)")
    parser.add_argument("--load-test", action="store_true", help="Test with 100, 500, 1000 drivers")
    parser.add_argument("--output", default="latency_results.csv", help="Output CSV file")
    parser.add_argument("--token", help="Customer JWT (role=CUSTOMER). If omitted, read the 'customer' key in tokens.json")
    args = parser.parse_args()

    # For simplicity, use driver 0's token as customer
    # In real scenario, create a separate customer account
    tokens_file = Path(__file__).parent / "tokens.json"
    if not tokens_file.exists():
        print(f"Error: {tokens_file} not found. Run prepare.py first.", file=sys.stderr)
        sys.exit(1)

    # Need a CUSTOMER token: /ws/customer rejects driver tokens (role mismatch).
    if args.token:
        customer_token = args.token
    else:
        with open(tokens_file) as f:
            tokens_data = json.load(f)
        cust = tokens_data.get("customer")
        if not cust:
            print(
                "Error: no 'customer' entry in tokens.json. Register a CUSTOMER "
                "(role=CUSTOMER) and store its token under the 'customer' key, or pass "
                "--token <jwt>.", file=sys.stderr)
            sys.exit(1)
        customer_token = cust["token"]

    print("LATENCY MEASUREMENT")
    print("=" * 60)
    print(f"WebSocket URL: {args.url}")
    print(f"Duration: {args.duration}s")
    print(f"Minimum samples: {args.duration // 4} (1 update per 4s)")
    print("\nIMPORTANT:")
    print("- Run on SAME machine as drivers.py for accurate clock sync")
    print("- Latency includes network delay (conservative estimate)")
    print("=" * 60)

    if args.load_test:
        load_levels = [100, 500, 1000]
        results = await measure_with_load_levels(customer_token, args.url, args.duration, load_levels)
        print_comparison_table(results)
        save_csv(results, args.output)
    else:
        print("\nMeasuring latency...")
        stats = await measure_single(customer_token, args.url, args.duration)

        print(f"\n{'=' * 60}")
        print("RESULTS")
        print(f"{'=' * 60}")
        print(f"Samples collected: {stats['count']}")
        print(f"P50 latency: {stats['p50']:.1f}ms")
        print(f"P95 latency: {stats['p95']:.1f}ms")
        print(f"P99 latency: {stats['p99']:.1f}ms")
        print(f"Max latency: {stats['max']:.1f}ms")

        # Save single result
        save_csv([{"drivers": "N/A", **stats}], args.output)

    print("\nLIMITATIONS:")
    print("1. Assumes sent_at timestamp is accurate and clocks are synchronized")
    print("2. Single customer may not reflect all message routing paths")


if __name__ == "__main__":
    asyncio.run(main())
