#!/usr/bin/env python3
"""
Register N drivers via api-gateway, login, and save tokens to tokens.json.
Idempotent: if already registered, just login.
"""
import asyncio
import json
import sys
import time
from pathlib import Path
import httpx

# Windows console defaults to cp1252; force UTF-8 so "✓" prints cleanly.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

API_BASE = "http://localhost:8000"
NUM_DRIVERS = 100
TIMEOUT = 10.0

# The api-gateway rate-limits /api/v1/auth/** at auth-rpm (60 req/min) per IP.
# Stay under it with a small safety margin.
AUTH_RPM_CAP = 55


class AuthRateLimiter:
    """Globally spaces auth requests so we never exceed the gateway's auth-rpm."""

    def __init__(self, rpm: int = AUTH_RPM_CAP):
        self._min_interval = 60.0 / rpm
        self._lock = asyncio.Lock()
        self._next_ok = 0.0

    async def wait(self):
        async with self._lock:
            now = time.monotonic()
            if now < self._next_ok:
                await asyncio.sleep(self._next_ok - now)
            self._next_ok = time.monotonic() + self._min_interval


async def _post_auth(client: httpx.AsyncClient, limiter: AuthRateLimiter,
                     driver_id: int, path: str, json_body: dict) -> httpx.Response:
    """POST to an auth endpoint, throttled and retrying once on 429."""
    url = f"{API_BASE}{path}"
    for attempt in (1, 2):
        await limiter.wait()
        resp = await client.post(url, json=json_body, timeout=TIMEOUT)
        if resp.status_code != 429 or attempt == 2:
            return resp
        # Rate-limited: back off and retry once.
        await asyncio.sleep(1.5)
    return resp


async def register_and_login(client: httpx.AsyncClient, limiter: AuthRateLimiter,
                             driver_id: int) -> dict | None:
    """Register driver if needed, then login and return token data."""
    email = f"driver{driver_id}@loadtest.com"
    password = f"driver{driver_id}pass"
    full_name = f"Driver {driver_id}"

    # Try register
    try:
        resp = await _post_auth(client, limiter, driver_id, "/api/v1/auth/register", {
            "email": email,
            "password": password,
            "fullName": full_name,
            "role": "DRIVER"
        })
        if resp.status_code == 200:
            data = resp.json()
            return {"token": data["accessToken"], "email": email}
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
        resp = await _post_auth(client, limiter, driver_id, "/api/v1/auth/login", {
            "email": email,
            "password": password
        })
        if resp.status_code == 200:
            data = resp.json()
            return {"token": data["accessToken"], "email": email}
        else:
            print(f"[{driver_id}] Login failed: {resp.status_code} {resp.text}", file=sys.stderr)
            return None
    except Exception as e:
        print(f"[{driver_id}] Login error: {e}", file=sys.stderr)
        return None


async def main():
    num_drivers = int(sys.argv[1]) if len(sys.argv) > 1 else NUM_DRIVERS

    print(f"Preparing {num_drivers} drivers...")
    print(f"(auth endpoints are rate-limited to ~{AUTH_RPM_CAP} req/min; "
          f"this will take ~{max(1, (2 * num_drivers) / AUTH_RPM_CAP * 60):.0f}s for a full re-run)")

    limiter = AuthRateLimiter()
    async with httpx.AsyncClient() as client:
        tasks = [register_and_login(client, limiter, i) for i in range(num_drivers)]
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