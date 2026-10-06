#!/usr/bin/env python3
"""
Register N drivers via api-gateway, login, and save tokens to tokens.json.
Idempotent: if already registered, just login.
"""
import asyncio
import json
import sys
from pathlib import Path
import httpx

API_BASE = "http://localhost:8000"
NUM_DRIVERS = 100
TIMEOUT = 10.0


async def register_and_login(client: httpx.AsyncClient, driver_id: int) -> dict | None:
    """Register driver if needed, then login and return token data."""
    phone = f"+849{driver_id:08d}"
    password = f"driver{driver_id}pass"
    name = f"Driver {driver_id}"

    # Try register
    try:
        resp = await client.post(
            f"{API_BASE}/api/v1/auth/register",
            json={
                "phone": phone,
                "password": password,
                "name": name,
                "role": "DRIVER"
            },
            timeout=TIMEOUT
        )
        if resp.status_code == 200:
            data = resp.json()
            return {"userId": data["userId"], "token": data["token"], "phone": phone}
        elif resp.status_code == 409:
            pass  # Already registered, proceed to login
        else:
            print(f"[{driver_id}] Register failed: {resp.status_code} {resp.text}", file=sys.stderr)
            return None
    except Exception as e:
        print(f"[{driver_id}] Register error: {e}", file=sys.stderr)
        return None

    # Login
    try:
        resp = await client.post(
            f"{API_BASE}/api/v1/auth/login",
            json={"phone": phone, "password": password},
            timeout=TIMEOUT
        )
        if resp.status_code == 200:
            data = resp.json()
            return {"userId": data["userId"], "token": data["token"], "phone": phone}
        else:
            print(f"[{driver_id}] Login failed: {resp.status_code} {resp.text}", file=sys.stderr)
            return None
    except Exception as e:
        print(f"[{driver_id}] Login error: {e}", file=sys.stderr)
        return None


async def main():
    num_drivers = int(sys.argv[1]) if len(sys.argv) > 1 else NUM_DRIVERS

    print(f"Preparing {num_drivers} drivers...")

    async with httpx.AsyncClient() as client:
        tasks = [register_and_login(client, i) for i in range(num_drivers)]
        results = await asyncio.gather(*tasks)

    tokens = {}
    success_count = 0
    for i, result in enumerate(results):
        if result:
            tokens[str(i)] = result
            success_count += 1

    output_file = Path(__file__).parent / "tokens.json"
    with open(output_file, "w") as f:
        json.dump(tokens, f, indent=2)

    print(f"✓ {success_count}/{num_drivers} drivers ready, tokens saved to {output_file}")

    if success_count < num_drivers:
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
