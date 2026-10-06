#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import json
import random
import time
from pathlib import Path
from typing import Any

import websockets


def normalize_ws_url(raw_url: str) -> str:
    if raw_url.startswith("http://"):
        return "ws://" + raw_url[len("http://") :]
    if raw_url.startswith("https://"):
        return "wss://" + raw_url[len("https://") :]
    if raw_url.startswith("ws://") or raw_url.startswith("wss://"):
        return raw_url
    return "ws://" + raw_url


def load_tokens(tokens_path: Path, expected_count: int) -> list[dict[str, Any]]:
    if not tokens_path.exists():
        raise FileNotFoundError(f"Missing tokens file: {tokens_path}. Run prepare.py first.")
    data = json.loads(tokens_path.read_text(encoding="utf-8"))
    drivers = data.get("drivers") or []
    if len(drivers) < expected_count:
        raise ValueError(f"Need {expected_count} tokens, found {len(drivers)} in {tokens_path}")
    selected = drivers[:expected_count]
    for idx, item in enumerate(selected):
        if not item.get("token"):
            raise ValueError(f"Token missing for driver {idx} in {tokens_path}")
    return selected


class Stats:
    def __init__(self) -> None:
        self.lock = asyncio.Lock()
        self.messages = 0
        self.errors = 0
        self.live = 0

    async def mark_live(self, delta: int) -> None:
        async with self.lock:
            self.live += delta

    async def record_message(self) -> None:
        async with self.lock:
            self.messages += 1

    async def record_error(self) -> None:
        async with self.lock:
            self.errors += 1

    async def snapshot(self) -> dict[str, int]:
        async with self.lock:
            return {"live": self.live, "messages": self.messages, "errors": self.errors}


async def stats_printer(stats: Stats, duration: int) -> None:
    deadline = time.monotonic() + duration
    while time.monotonic() < deadline:
        await asyncio.sleep(10)
        snapshot = await stats.snapshot()
        print(
            f"[{time.strftime('%H:%M:%S', time.localtime())}] live={snapshot['live']} "
            f"messages={snapshot['messages']} errors={snapshot['errors']}"
        )


async def reader_loop(ws: websockets.WebSocketClientProtocol, driver_index: int, stats: Stats, reject_rate: float) -> None:
    try:
        async for raw in ws:
            payload = json.loads(raw)
            msg_type = str(payload.get("t", "")).lower()
            if msg_type == "offer":
                trip_id = payload.get("tripId") or payload.get("trip_id") or payload.get("tripid") or "unknown"
                await asyncio.sleep(random.uniform(1.0, 3.0))
                if random.random() < reject_rate:
                    print(f"driver {driver_index}: skipped offer {trip_id}")
                    continue
                await ws.send(json.dumps({"t": "accept", "tripId": trip_id}))
            elif msg_type == "error":
                await stats.record_error()
                print(f"driver {driver_index}: error={payload}")
            elif msg_type in {"trip_update", "trip_status", "connected"}:
                continue
    except Exception as exc:  # websocket disconnect is expected, count as error only on unexpected exceptions
        if not isinstance(exc, websockets.ConnectionClosed):
            await stats.record_error()
            print(f"driver {driver_index}: reader exception: {exc}")


def random_point_hcm() -> tuple[float, float]:
    base_lat = 10.7769
    base_lng = 106.7009
    lat = base_lat + random.uniform(-0.0035, 0.0035)
    lng = base_lng + random.uniform(-0.0045, 0.0045)
    return lat, lng


async def run_driver(driver_info: dict[str, Any], ws_url: str, stats: Stats, reject_rate: float, duration: int) -> None:
    driver_index = int(driver_info.get("index", 0))
    token = str(driver_info["token"])
    deadline = time.monotonic() + duration
    backoff = 1.0

    while time.monotonic() < deadline:
        try:
            async with websockets.connect(ws_url, ping_interval=20, ping_timeout=60, open_timeout=15) as ws:
                await stats.mark_live(1)
                await ws.send(json.dumps({"t": "auth", "token": token}))
                await asyncio.sleep(0.3)

                reader_task = asyncio.create_task(reader_loop(ws, driver_index, stats, reject_rate))
                try:
                    while time.monotonic() < deadline:
                        await asyncio.sleep(4.0 + random.uniform(-0.5, 0.5))
                        if ws.closed:
                            break
                        lat, lng = random_point_hcm()
                        payload = {"t": "location", "lat": lat, "lng": lng, "sent_at": time.time()}
                        await ws.send(json.dumps(payload))
                        await stats.record_message()
                finally:
                    reader_task.cancel()
                    try:
                        await reader_task
                    except asyncio.CancelledError:
                        pass

                await stats.mark_live(-1)
                backoff = 1.0
                await asyncio.sleep(0.1)
        except (OSError, asyncio.TimeoutError, websockets.WebSocketException, ConnectionError) as exc:
            await stats.mark_live(-1)
            await stats.record_error()
            print(f"driver {driver_index}: reconnecting after error: {exc}")
            if time.monotonic() >= deadline:
                break
            sleep_for = backoff + random.uniform(0.0, 1.0)
            await asyncio.sleep(min(sleep_for, 30.0))
            backoff = min(backoff * 2.0, 30.0)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Simulate many driver WebSocket connections.")
    parser.add_argument("--drivers", type=int, default=100, help="Number of drivers to simulate.")
    parser.add_argument("--url", default="ws://localhost:8001", help="WebSocket endpoint, e.g. ws://localhost:8001/ws/driver")
    parser.add_argument("--duration", type=int, default=300, help="Runtime in seconds.")
    parser.add_argument("--reject-rate", type=float, default=0.2, help="Fraction of offers to ignore (0.0-1.0).")
    parser.add_argument("--tokens", default=str(Path(__file__).resolve().parent / "tokens.json"), help="Path to the driver token file.")
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    if args.drivers <= 0:
        raise SystemExit("--drivers must be > 0")
    if not 0.0 <= args.reject_rate <= 1.0:
        raise SystemExit("--reject-rate must be between 0.0 and 1.0")

    ws_url = normalize_ws_url(args.url)
    tokens_path = Path(args.tokens).expanduser().resolve()
    drivers = load_tokens(tokens_path, args.drivers)
    stats = Stats()

    tasks = [asyncio.create_task(run_driver(item, ws_url, stats, args.reject_rate, args.duration)) for item in drivers]
    tasks.append(asyncio.create_task(stats_printer(stats, args.duration)))
    await asyncio.gather(*tasks)
    snapshot = await stats.snapshot()
    print(f"Final: live={snapshot['live']} messages={snapshot['messages']} errors={snapshot['errors']}")


if __name__ == "__main__":
    asyncio.run(main())
