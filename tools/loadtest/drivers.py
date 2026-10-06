#!/usr/bin/env python3
"""
Simulate N drivers connecting via WebSocket, sending location updates,
and responding to offers.
"""
import asyncio
import json
import random
import sys
import time
import argparse
from pathlib import Path
import websockets
from websockets.exceptions import ConnectionClosed


# Ho Chi Minh City center
HCM_LAT = 10.762622
HCM_LNG = 106.660172
STEP_SIZE = 0.001  # ~100m per step

# Reconnection parameters
INITIAL_BACKOFF = 1.0
MAX_BACKOFF = 60.0
BACKOFF_MULTIPLIER = 2.0
JITTER_FACTOR = 0.5


class DriverSimulator:
    def __init__(self, driver_id: int, token: str, ws_url: str, reject_rate: float):
        self.driver_id = driver_id
        self.token = token
        self.ws_url = ws_url
        self.reject_rate = reject_rate

        # Random starting position around HCM
        self.lat = HCM_LAT + random.uniform(-0.05, 0.05)
        self.lng = HCM_LNG + random.uniform(-0.05, 0.05)

        # Stats
        self.connected = False
        self.messages_sent = 0
        self.errors = 0
        self.backoff = INITIAL_BACKOFF

    def random_walk(self):
        """Small random movement."""
        self.lat += random.uniform(-STEP_SIZE, STEP_SIZE)
        self.lng += random.uniform(-STEP_SIZE, STEP_SIZE)

    async def send_location(self, ws):
        """Send location update with timestamp."""
        try:
            msg = {
                "type": "location",
                "lat": self.lat,
                "lng": self.lng,
                "sent_at": time.time()
            }
            await ws.send(json.dumps(msg))
            self.messages_sent += 1
        except Exception as e:
            self.errors += 1
            raise

    async def handle_offer(self, ws, offer_data):
        """Handle incoming offer: wait 1-3s then accept or reject."""
        await asyncio.sleep(random.uniform(1.0, 3.0))

        if random.random() < self.reject_rate:
            return  # Ignore offer

        try:
            response = {
                "type": "accept_offer",
                "tripId": offer_data.get("tripId")
            }
            await ws.send(json.dumps(response))
            self.messages_sent += 1
        except Exception as e:
            self.errors += 1

    async def run(self):
        """Main driver loop with reconnection."""
        while True:
            try:
                async with websockets.connect(
                    self.ws_url,
                    extra_headers={"Authorization": f"Bearer {self.token}"}
                ) as ws:
                    self.connected = True
                    self.backoff = INITIAL_BACKOFF

                    # Send auth message
                    auth_msg = {"type": "auth", "token": self.token}
                    await ws.send(json.dumps(auth_msg))
                    self.messages_sent += 1

                    # Run location sender and message receiver concurrently
                    await asyncio.gather(
                        self._location_sender(ws),
                        self._message_receiver(ws),
                        return_exceptions=True
                    )
            except (ConnectionClosed, OSError, Exception) as e:
                self.connected = False
                self.errors += 1

                # Exponential backoff with jitter
                jitter = random.uniform(-JITTER_FACTOR, JITTER_FACTOR) * self.backoff
                wait_time = min(self.backoff + jitter, MAX_BACKOFF)
                await asyncio.sleep(max(0.1, wait_time))
                self.backoff = min(self.backoff * BACKOFF_MULTIPLIER, MAX_BACKOFF)

    async def _location_sender(self, ws):
        """Send location updates every 4s ± 0.5s."""
        while True:
            self.random_walk()
            await self.send_location(ws)
            jitter = random.uniform(-0.5, 0.5)
            await asyncio.sleep(4.0 + jitter)

    async def _message_receiver(self, ws):
        """Receive and handle messages from server."""
        async for message in ws:
            try:
                data = json.loads(message)
                msg_type = data.get("type")

                if msg_type == "offer":
                    asyncio.create_task(self.handle_offer(ws, data))
            except json.JSONDecodeError:
                self.errors += 1
            except Exception:
                self.errors += 1


async def stats_reporter(drivers: list[DriverSimulator], interval: int = 10):
    """Print stats every interval seconds."""
    while True:
        await asyncio.sleep(interval)

        connected = sum(1 for d in drivers if d.connected)
        total_sent = sum(d.messages_sent for d in drivers)
        total_errors = sum(d.errors for d in drivers)

        print(f"[{time.strftime('%H:%M:%S')}] Connected: {connected}/{len(drivers)}, "
              f"Sent: {total_sent}, Errors: {total_errors}")


async def main():
    parser = argparse.ArgumentParser(description="Simulate drivers sending location updates")
    parser.add_argument("--drivers", type=int, default=100, help="Number of drivers (default: 100)")
    parser.add_argument("--url", default="ws://localhost:8001/ws", help="WebSocket URL")
    parser.add_argument("--duration", type=int, default=300, help="Duration in seconds (default: 300)")
    parser.add_argument("--reject-rate", type=float, default=0.2, help="Offer rejection rate (default: 0.2)")
    args = parser.parse_args()

    # Load tokens
    tokens_file = Path(__file__).parent / "tokens.json"
    if not tokens_file.exists():
        print(f"Error: {tokens_file} not found. Run prepare.py first.", file=sys.stderr)
        sys.exit(1)

    with open(tokens_file) as f:
        tokens_data = json.load(f)

    # Create driver simulators
    drivers = []
    for i in range(args.drivers):
        driver_key = str(i)
        if driver_key not in tokens_data:
            print(f"Warning: Driver {i} not found in tokens.json", file=sys.stderr)
            continue

        token = tokens_data[driver_key]["token"]
        drivers.append(DriverSimulator(i, token, args.url, args.reject_rate))

    print(f"Starting {len(drivers)} drivers for {args.duration}s...")
    print(f"WebSocket URL: {args.url}")
    print(f"Reject rate: {args.reject_rate * 100}%\n")

    # Start all drivers and stats reporter
    driver_tasks = [asyncio.create_task(d.run()) for d in drivers]
    stats_task = asyncio.create_task(stats_reporter(drivers))

    # Run for specified duration
    await asyncio.sleep(args.duration)

    # Cancel all tasks
    for task in driver_tasks:
        task.cancel()
    stats_task.cancel()

    # Wait for cancellation
    await asyncio.gather(*driver_tasks, stats_task, return_exceptions=True)

    # Final stats
    print("\n" + "=" * 60)
    print("FINAL STATS")
    print("=" * 60)
    connected = sum(1 for d in drivers if d.connected)
    total_sent = sum(d.messages_sent for d in drivers)
    total_errors = sum(d.errors for d in drivers)
    print(f"Connected at end: {connected}/{len(drivers)}")
    print(f"Total messages sent: {total_sent}")
    print(f"Total errors: {total_errors}")


if __name__ == "__main__":
    asyncio.run(main())