#!/usr/bin/env python3
"""
Matching accuracy test for dispatch-service.
Registers 6 drivers at different distances from pickup, verifies offer order.
Driver 6 at 0.1km but stops sending location >20s ago (should be ignored).
"""

import os
import sys
import asyncio
import json
import time
import random
import httpx
import websockets
from typing import List, Dict, Tuple
from dataclasses import dataclass

# Config
API_BASE = os.getenv("API_BASE", "http://localhost:8000")
WS_URL = os.getenv("WS_URL", "ws://localhost:8001/ws/driver")
OUT_DIR = os.getenv("EVIDENCE_OUT", "tools/evidence/out")

# Hanoi pickup point (central)
PICKUP_LAT, PICKUP_LNG = 21.0285, 105.8542

# Driver positions: (distance_km, label, should_receive_offer)
# Using Haversine to compute lat/lng offsets
DRIVERS = [
    (0.3, "driver_0.3km", True),
    (0.8, "driver_0.8km", True),
    (1.5, "driver_1.5km", True),
    (2.4, "driver_2.4km", True),
    (3.5, "driver_3.5km", False),  # Outside 3km radius
    (0.1, "driver_0.1km_stale", False),  # Close but stale (>20s no location)
]

EARTH_RADIUS_KM = 6371.0

def haversine(lat1, lng1, lat2, lng2) -> float:
    from math import radians, sin, cos, sqrt, atan2
    dlat = radians(lat2 - lat1)
    dlng = radians(lng2 - lng1)
    a = sin(dlat/2)**2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlng/2)**2
    return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1-a))

def offset_coord(lat, lng, distance_km, bearing_deg=0):
    """Calculate new coordinate at distance_km from (lat,lng) at bearing."""
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
    offers_received: List[Dict] = None
    connected: asyncio.Event = None

    def __post_init__(self):
        self.offers_received = []
        self.connected = asyncio.Event()

async def register_user(client: httpx.AsyncClient, email: str, password: str, role: str) -> Dict:
    """Register a user and return token."""
    # Register
    resp = await client.post(f"{API_BASE}/api/v1/auth/register",
        json={"email": email, "password": password, "fullName": email, "role": role})
    resp.raise_for_status()
    # Login
    resp = await client.post(f"{API_BASE}/api/v1/auth/login",
        json={"email": email, "password": password})
    resp.raise_for_status()
    return resp.json()


async def register_driver(client: httpx.AsyncClient, email: str, password: str) -> Dict:
    """Register a driver and return token."""
    return await register_user(client, email, password, "DRIVER")

async def driver_ws_loop(driver: Driver, stop_event: asyncio.Event):
    """WebSocket loop for a driver - receives offers."""
    try:
        print(f"  {driver.label} driver_ws_loop: connecting...")
        async with websockets.connect(f"{WS_URL}") as ws:
            print(f"  {driver.label} driver_ws_loop: connected, setting driver.ws")
            driver.ws = ws
            print(f"  {driver.label} driver_ws_loop: driver.ws set, sending auth")
            # Auth
            await ws.send(json.dumps({"t": "auth", "token": driver.token}))
            print(f"  {driver.label} driver_ws_loop: auth sent")
            # Signal connected after auth sent (server doesn't send ack)
            driver.connected.set()
            print(f"  {driver.label} driver_ws_loop: CONNECTED and authenticated")
            async for msg in ws:
                data = json.loads(msg)
                if data.get("t") == "offer":
                    driver.offers_received.append({
                        "time": time.time(),
                        "data": data
                    })
                    print(f"  {driver.label} received OFFER: trip={data.get('tripId')}, fare={data.get('fare')}")
                if stop_event.is_set():
                    break
    except Exception as e:
        print(f"  {driver.label} WS error: {e}")

async def send_location_ws(driver: Driver, stop_event: asyncio.Event):
    """Send GPS location via WebSocket every 3 seconds."""
    print(f"  {driver.label} send_location_ws STARTED, ws={driver.ws is not None}")
    if driver.ws is None:
        print(f"  {driver.label} WS is None!")
        return
    count = 0
    while not stop_event.is_set():
        try:
            location_msg = {
                "t": "location",
                "lat": driver.lat,
                "lng": driver.lng,
                "sent_at": int(time.time() * 1000)
            }
            await driver.ws.send(json.dumps(location_msg))
            count += 1
            print(f"  {driver.label} SENT location #{count}: lat={driver.lat}, lng={driver.lng}")
        except Exception as e:
            print(f"  {driver.label} WS location send error: {e}")
            break
        await asyncio.sleep(3)
    print(f"  {driver.label} send_location_ws STOPPED after {count} messages")

async def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    print("=== Matching Accuracy Test ===")
    print(f"Pickup: {PICKUP_LAT}, {PICKUP_LNG}")
    print()

    # Create driver coordinates
    drivers = []
    for i, (dist, label, should_offer) in enumerate(DRIVERS):
        # Vary bearing so they're in different directions
        bearing = i * 60  # 0, 60, 120, 180, 240, 300 degrees
        lat, lng = offset_coord(PICKUP_LAT, PICKUP_LNG, dist, bearing)
        actual_dist = haversine(PICKUP_LAT, PICKUP_LNG, lat, lng)
        drivers.append(Driver(label=label, lat=lat, lng=lng))
        print(f"  {label}: {actual_dist:.2f}km from pickup (target {dist}km) {'[OK] should get offer' if should_offer else '[NO] no offer expected'}")

    run_id = int(time.time() * 1000)

    async with httpx.AsyncClient(timeout=10.0) as client:
        # Register all drivers
        print("\nRegistering drivers...")
        for i, d in enumerate(drivers):
            email = f"{d.label}_{run_id}@test.com"
            password = "password123"
            data = await register_driver(client, email, password)
            d.token = data["accessToken"]
            print(f"  {d.label} registered, token acquired")

        # Connect WebSocket for all drivers FIRST
        print("Connecting WebSockets...")
        stop_event = asyncio.Event()
        ws_tasks = []
        for d in drivers:
            task = asyncio.create_task(driver_ws_loop(d, stop_event))
            ws_tasks.append(task)

        # Wait for all drivers to be connected and authenticated
        print("Waiting for all drivers to connect...")
        await asyncio.gather(*[d.connected.wait() for d in drivers])
        print("All drivers connected and authenticated")

        # Start location sending via WebSocket for ALL drivers (including stale)
        location_tasks = []
        stale_location_task = None
        for d in drivers:
            task = asyncio.create_task(send_location_ws(d, stop_event))
            location_tasks.append(task)
            if d == drivers[-1]:  # stale driver
                stale_location_task = task

        # Let all drivers send initial locations
        print("\nSending initial locations (2s)...")
        await asyncio.sleep(2)

        # Stop the stale driver's location sending (simulate going offline >20s ago)
        stale = drivers[-1]
        print(f"  {stale.label} STOPPING location sending (will become stale)")
        if stale_location_task:
            stale_location_task.cancel()
            try:
                await stale_location_task
            except asyncio.CancelledError:
                pass

        # Wait for stale driver to become stale (>15s threshold)
        print("\nWaiting for stale driver to expire (20s)...")
        await asyncio.sleep(20)

        # Customer creates a ride
        print("\nCustomer creating ride...")
        cust_email = f"customer_{run_id}@test.com"
        cust_password = "password123"
        cust_data = await register_user(client, cust_email, cust_password, "CUSTOMER")
        cust_token = cust_data["accessToken"]

        # Request ride - uses Authorization Bearer token (JWT)
        ride_resp = await client.post(
            f"{API_BASE}/api/v1/rides",
            headers={"Authorization": f"Bearer {cust_token}",
                     "Content-Type": "application/json"},
            json={"pickupLat": PICKUP_LAT, "pickupLng": PICKUP_LNG,
                  "dropoffLat": PICKUP_LAT + 0.01, "dropoffLng": PICKUP_LNG + 0.01,
                  "idempotencyKey": f"match-acc-test-{run_id}"}
        )
        ride_resp.raise_for_status()
        ride = ride_resp.json()
        trip_id = ride["tripId"]
        print(f"  Ride created: {trip_id}")

        # Wait for offers (15 seconds)
        print("\nWaiting for offers (15s)...")
        await asyncio.sleep(15)

        # Stop
        stop_event.set()
        for t in ws_tasks:
            t.cancel()
        for t in location_tasks:
            t.cancel()

        # Analyze results
        print("\n=== OFFER ANALYSIS ===")
        offer_events = []
        for d in drivers:
            for offer in d.offers_received:
                offer_events.append({
                    "driver": d.label,
                    "time": offer["time"],
                    "trip_id": offer["data"].get("tripId"),
                    "fare": offer["data"].get("fare")
                })

        offer_events.sort(key=lambda x: x["time"])

        print(f"\nOffer sequence (by time):")
        for i, ev in enumerate(offer_events):
            print(f"  {i+1}. {ev['driver']} at {ev['time']:.3f} (trip={ev['trip_id'][:8]}...)")

        # Verify expectations
        print("\n=== VERIFICATION ===")
        expected_first = "driver_0.3km"
        expected_second = "driver_0.8km"
        stale_driver = "driver_0.1km_stale"

        if offer_events:
            first_driver = offer_events[0]["driver"]
            if first_driver == expected_first:
                print(f"[PASS] First offer to {expected_first} (closest)")
            else:
                print(f"[FAIL] First offer to {first_driver}, expected {expected_first}")

            if len(offer_events) >= 2:
                second_driver = offer_events[1]["driver"]
                if second_driver == expected_second:
                    print(f"[PASS] Second offer to {expected_second} (next closest after 15s)")
                else:
                    print(f"[FAIL] Second offer to {second_driver}, expected {expected_second}")
        else:
            print("[FAIL] No offers received!")

        stale_offers = [e for e in offer_events if e["driver"] == stale_driver]
        if not stale_offers:
            print(f"[PASS] Stale driver ({stale_driver}) received no offers")
        else:
            print(f"[FAIL] Stale driver received {len(stale_offers)} offer(s)")

        # Save CSV
        import csv
        csv_path = os.path.join(OUT_DIR, "match_accuracy.csv")
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["order", "driver", "timestamp", "trip_id", "fare"])
            writer.writeheader()
            for i, ev in enumerate(offer_events):
                writer.writerow({"order": i+1, "driver": ev["driver"],
                               "timestamp": ev["time"], "trip_id": ev["trip_id"], "fare": ev["fare"]})
        print(f"\nCSV saved to {csv_path}")

if __name__ == "__main__":
    asyncio.run(main())