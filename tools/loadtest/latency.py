#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import base64
import csv
import json
import os
import statistics
import time
from pathlib import Path
from typing import Any

import httpx
import websockets


LOAD_LEVELS_DEFAULT = [100, 500, 1000]


from urllib.parse import urlparse


def normalize_http_url(raw_url: str) -> str:
    if raw_url.startswith("ws://"):
        parsed = urlparse(raw_url)
        return f"http://{parsed.netloc}"
    if raw_url.startswith("wss://"):
        parsed = urlparse(raw_url)
        return f"https://{parsed.netloc}"
    parsed = urlparse(raw_url)
    if parsed.scheme:
        return f"{parsed.scheme}://{parsed.netloc}"
    return raw_url.rstrip("/")


def normalize_ws_url(raw_url: str) -> str:
    if raw_url.startswith("http://"):
        return "ws://" + raw_url[len("http://") :]
    if raw_url.startswith("https://"):
        return "wss://" + raw_url[len("https://") :]
    if raw_url.startswith("ws://") or raw_url.startswith("wss://"):
        return raw_url
    return "ws://" + raw_url


def load_tokens(tokens_path: Path) -> list[dict[str, Any]]:
    if not tokens_path.exists():
        raise FileNotFoundError(f"Missing tokens file: {tokens_path}. Run prepare.py first.")
    data = json.loads(tokens_path.read_text(encoding="utf-8"))
    drivers = data.get("drivers") or []
    if not drivers:
        raise ValueError(f"No drivers in {tokens_path}")
    return drivers


def decode_jwt_subject(token: str) -> str:
    try:
        payload_segment = token.split(".", 2)[1]
        padded = payload_segment + "=" * (-len(payload_segment) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
        return str(payload.get("sub") or payload.get("userId") or payload.get("uid") or "unknown")
    except Exception:
        return "unknown"


async def register_or_login(client: httpx.AsyncClient, base_url: str, email: str, password: str, role: str) -> str:
    register_url = f"{base_url}/api/v1/auth/register"
    login_url = f"{base_url}/api/v1/auth/login"
    registration = {"email": email, "password": password, "fullName": email.split("@", 1)[0].replace(".", " ").title(), "role": role}
    response = await client.post(register_url, json=registration, timeout=15.0)
    if response.status_code == 200:
        data = response.json()
        token = str(data.get("accessToken") or data.get("token") or "")
        if token:
            return token
        raise RuntimeError(f"Register response missing token for {email}: {response.text}")
    if response.status_code == 409:
        login_response = await client.post(login_url, json={"email": email, "password": password}, timeout=15.0)
        if login_response.status_code != 200:
            raise RuntimeError(f"Login failed for {email}: {login_response.status_code}: {login_response.text}")
        data = login_response.json()
        token = str(data.get("accessToken") or data.get("token") or "")
        if token:
            return token
        raise RuntimeError(f"Login response missing token for {email}: {login_response.text}")
    raise RuntimeError(f"Register failed for {email}: {response.status_code}: {response.text}")


async def ensure_customer_token(api_base_url: str, email: str, password: str) -> tuple[str, str]:
    async with httpx.AsyncClient() as client:
        token = await register_or_login(client, api_base_url, email, password, "CUSTOMER")
    return token, decode_jwt_subject(token)


async def set_route(ws_gateway_url: str, internal_key: str, driver_id: int, customer_id: int) -> None:
    route_url = f"{ws_gateway_url}/internal/routes/{driver_id}"
    headers = {"X-Internal-Key": internal_key, "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=5.0) as client:
        response = await client.put(route_url, json={"customerId": customer_id}, headers=headers)
        if response.status_code != 200:
            raise RuntimeError(f"Route mapping failed for driver {driver_id}: {response.status_code} {response.text}")


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    data = sorted(values)
    if len(data) == 1:
        return data[0]
    rank = max(0, min(len(data) - 1, int((len(data) - 1) * pct / 100)))
    return data[rank]


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    fieldnames = ["load", "driver_index", "driver_id", "sent_at", "received_at", "latency_ms"]
    with path.open("w", newline="", encoding="utf-8") as fp:
        writer = csv.DictWriter(fp, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


async def measure_latency_for_load(
    ws_url: str,
    api_base_url: str,
    load_count: int,
    duration_seconds: int,
    customer_email: str,
    customer_password: str,
    driver_index: int,
    internal_key: str,
    csv_path: Path,
) -> tuple[list[dict[str, Any]], dict[str, float | int]]:
    drivers = load_tokens(Path(__file__).resolve().parent / "tokens.json")
    if load_count > len(drivers):
        raise ValueError(f"Requested {load_count} drivers but only {len(drivers)} tokens are available")
    driver_entry = drivers[driver_index]
    driver_id = driver_entry.get("user_id") or driver_index

    customer_token, customer_id = await ensure_customer_token(api_base_url, customer_email, customer_password)
    ws_gateway_base = normalize_http_url(ws_url)
    if not internal_key:
        raise ValueError("--internal-key is required for route mapping; set INTERNAL_KEY or pass --internal-key.")
    await set_route(ws_gateway_base, internal_key, int(driver_id), int(customer_id))

    received: list[dict[str, Any]] = []
    deadline = time.monotonic() + duration_seconds

    async def ws_client() -> None:
        async with websockets.connect(ws_url, ping_interval=20, ping_timeout=60, open_timeout=15) as websocket:
            await websocket.send(json.dumps({"t": "auth", "token": customer_token}))
            await asyncio.sleep(0.5)
            while time.monotonic() < deadline:
                try:
                    raw = await asyncio.wait_for(websocket.recv(), timeout=1.0)
                except (asyncio.TimeoutError, websockets.ConnectionClosed):
                    continue
                payload = json.loads(raw)
                if str(payload.get("t", "")).lower() != "driver_location":
                    continue
                sent_at = float(payload.get("sent_at") or payload.get("sentAt") or 0.0)
                now = time.time()
                latency_ms = max(0.0, (now - sent_at) * 1000)
                received.append(
                    {
                        "load": load_count,
                        "driver_index": driver_index,
                        "driver_id": str(driver_id),
                        "sent_at": sent_at,
                        "received_at": now,
                        "latency_ms": latency_ms,
                    }
                )

    await ws_client()
    summary = {
        "samples": len(received),
        "p50": percentile([item["latency_ms"] for item in received], 50),
        "p95": percentile([item["latency_ms"] for item in received], 95),
        "p99": percentile([item["latency_ms"] for item in received], 99),
        "max": max((item["latency_ms"] for item in received), default=0.0),
    }
    if received:
        write_csv(csv_path, received)
    return received, summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Measure driver-location latency under increasing load.")
    parser.add_argument("--drivers", type=int, default=100, help="Single load size when a single benchmark is desired.")
    parser.add_argument("--load-levels", type=int, nargs="*", default=None, help="Optional benchmark levels, e.g. 100 500 1000")
    parser.add_argument("--duration", type=int, default=300, help="Measurement window in seconds (minimum 300 for stable stats).")
    parser.add_argument("--url", default="ws://localhost:8001", help="WebSocket endpoint, e.g. ws://localhost:8001/ws/customer")
    parser.add_argument("--api-url", default="http://localhost:8000", help="HTTP API gateway base URL.")
    parser.add_argument("--driver-index", type=int, default=0, help="Driver index to target in tokens.json, default 0.")
    parser.add_argument("--internal-key", default=os.getenv("INTERNAL_KEY", ""), help="Internal key for ws-gateway route mapping")
    parser.add_argument("--customer-email", default="customer_loadtest@example.com", help="Email for the virtual customer account.")
    parser.add_argument("--customer-password", default="LoadTestPass123!", help="Password for the virtual customer account.")
    parser.add_argument("--csv", default=None, help="Optional CSV export path.")
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    if args.duration < 300:
        print("WARNING: latency benchmarks are more stable with at least 300s duration.")

    load_levels = args.load_levels or [args.drivers]
    if not load_levels:
        load_levels = [100]
    token_path = Path(__file__).resolve().parent / "tokens.json"
    if not token_path.exists():
        raise SystemExit("Tokens file not found. Run tools/loadtest/prepare.py first.")

    print("NOTE: run this on the same machine as drivers.py to share the same wall clock; otherwise synchronize NTP before comparing results.")
    print("This measurement includes the full path from the local machine to the VPS/WebSocket gateway, so it is a conservative estimate.")
    print("Limitations:")
    print("1) Network jitter and WebSocket batching can add extra latency beyond pure server-side processing.")
    print("2) Without NTP synchronization across machines, cross-host timing is not precise and may skew p95/p99.")

    ws_url = normalize_ws_url(args.url)
    api_base_url = normalize_http_url(args.api_url)
    csv_path = Path(args.csv).expanduser().resolve() if args.csv else Path(__file__).resolve().parent / "latency.csv"

    results: list[dict[str, Any]] = []
    all_rows: list[dict[str, Any]] = []
    for load in load_levels:
        rows, summary = await measure_latency_for_load(
            ws_url=ws_url,
            api_base_url=api_base_url,
            load_count=load,
            duration_seconds=args.duration,
            customer_email=args.customer_email,
            customer_password=args.customer_password,
            driver_index=args.driver_index,
            internal_key=args.internal_key,
            csv_path=csv_path,
        )
        results.append({"load": load, **summary})
        all_rows.extend(rows)

        print(f"load={load}: samples={summary['samples']} p50={summary['p50']:.2f}ms p95={summary['p95']:.2f}ms p99={summary['p99']:.2f}ms max={summary['max']:.2f}ms")

    if len(results) > 1:
        print("\nComparison table")
        print(f"{'load':>8} {'samples':>8} {'p50(ms)':>10} {'p95(ms)':>10} {'p99(ms)':>10} {'max(ms)':>10}")
        for item in results:
            print(
                f"{item['load']:>8} {item['samples']:>8} "
                f"{float(item['p50']):>10.2f} {float(item['p95']):>10.2f} {float(item['p99']):>10.2f} {float(item['max']):>10.2f}"
            )

    if all_rows:
        write_csv(csv_path, all_rows)
        print(f"CSV written to {csv_path}")


if __name__ == "__main__":
    asyncio.run(main())
