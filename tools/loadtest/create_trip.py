#!/usr/bin/env python3
"""
Create an active trip for latency testing.
Usage: python create_trip.py
"""
import json
import sys
from pathlib import Path
import requests


def load_tokens():
    """Load tokens from tokens.json."""
    tokens_file = Path(__file__).parent / "tokens.json"
    with open(tokens_file) as f:
        return json.load(f)


def create_trip(customer_token: str, driver_token: str):
    """Create and start a trip."""
    api_base = "http://localhost:8000/api/v1"
    import uuid

    # 1. Customer requests trip
    print("Step 1: Customer requesting trip...")
    try:
        resp = requests.post(
            f"{api_base}/rides",
            headers={"Authorization": f"Bearer {customer_token}"},
            json={
                "pickupLat": 10.8231,
                "pickupLng": 106.6297,
                "dropoffLat": 10.7769,
                "dropoffLng": 106.7009,
                "idempotencyKey": str(uuid.uuid4())
            },
            timeout=5
        )

        if resp.status_code not in (200, 201):
            print(f"Failed to request trip: {resp.status_code} {resp.text}")
            return None

        trip_data = resp.json()
        trip_id = trip_data.get("tripId")
        print(f"[OK] Trip requested: {trip_id}")
        print(f"     Status: {trip_data.get('status')}")
        print(f"     Fare: {trip_data.get('fare')}")
    except Exception as e:
        print(f"Error requesting trip: {e}")
        return None

    # Note: In the real flow, the matching service will automatically
    # offer the trip to nearby drivers via WebSocket. For testing,
    # we would need drivers.py running to accept the offer.
    # The accept/start endpoints are internal and require special setup.

    print("\nNote: Trip is in MATCHING state.")
    print("To complete the flow, you need:")
    print("1. Run drivers.py to have drivers accept the trip offer")
    print("2. Then the trip will transition to ACCEPTED -> IN_PROGRESS")

    return trip_id


def main():
    print("Creating active trip for latency testing...")
    print("=" * 60)

    # Load tokens
    tokens = load_tokens()
    customer_token = tokens["customer"]["token"]
    driver_token = tokens["0"]["token"]  # Use driver 0

    print(f"Customer token: {customer_token[:20]}...")
    print(f"Driver token: {driver_token[:20]}...")
    print()

    # Create trip
    trip_id = create_trip(customer_token, driver_token)

    if trip_id:
        print("\n" + "=" * 60)
        print("SUCCESS")
        print("=" * 60)
        print(f"Trip ID: {trip_id}")
        print("\nTo complete the trip flow:")
        print("1. Run: python drivers.py --drivers 10 --duration 60")
        print("2. A nearby driver will accept the trip automatically")
        print("3. Then you can run: python latency.py --duration 60")
    else:
        print("\n" + "=" * 60)
        print("FAILED")
        print("=" * 60)
        sys.exit(1)


if __name__ == "__main__":
    main()
