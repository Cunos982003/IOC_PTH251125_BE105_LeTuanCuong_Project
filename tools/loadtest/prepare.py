#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import base64
import json
from pathlib import Path
from typing import Any

import httpx


def normalize_http_url(raw_url: str) -> str:
    if raw_url.startswith("ws://"):
        return "http://" + raw_url[len("ws://") :]
    if raw_url.startswith("wss://"):
        return "https://" + raw_url[len("wss://") :]
    return raw_url.rstrip("/")


def decode_jwt_subject(token: str) -> str:
    try:
        payload_segment = token.split(".", 2)[1]
        padded = payload_segment + "=" * (-len(payload_segment) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
        return str(payload.get("sub") or payload.get("userId") or payload.get("uid") or "unknown")
    except Exception:
        return "unknown"


async def register_or_login(client: httpx.AsyncClient, base_url: str, email: str, password: str) -> str:
    register_url = f"{base_url}/api/v1/auth/register"
    login_url = f"{base_url}/api/v1/auth/login"
    payload = {
        "email": email,
        "password": password,
        "fullName": email.split("@", 1)[0].replace(".", " ").title(),
        "role": "DRIVER",
    }

    response = await client.post(register_url, json=payload, timeout=15.0)
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
        if not token:
            raise RuntimeError(f"Login response missing token for {email}: {login_response.text}")
        return token

    raise RuntimeError(f"Register failed for {email}: {response.status_code}: {response.text}")


async def build_tokens(base_url: str, drivers: int, out_path: Path, email_prefix: str, password: str) -> dict[str, Any]:
    time_key = "generated_at"
    if out_path.exists():
        try:
            existing = json.loads(out_path.read_text(encoding="utf-8"))
            if isinstance(existing, dict) and isinstance(existing.get("drivers"), list):
                entries = []
                for item in existing["drivers"]:
                    if isinstance(item, dict) and item.get("token"):
                        entries.append(item)
                if len(entries) >= drivers:
                    return {"generated_at": existing.get(time_key, 0), "drivers": entries[:drivers]}
        except Exception:
            pass

    async with httpx.AsyncClient(http2=False) as client:
        semaphore = asyncio.Semaphore(10)

        async def worker(index: int) -> dict[str, Any]:
            async with semaphore:
                email = f"{email_prefix}{index}@loadtest.local"
                token = await register_or_login(client, base_url, email, password)
                subject = decode_jwt_subject(token)
                return {
                    "index": index,
                    "email": email,
                    "password": password,
                    "user_id": subject,
                    "token": token,
                }

        entries = await asyncio.gather(*(worker(i) for i in range(drivers)))

    payload = {"generated_at": __import__("time").time(), "drivers": entries}
    out_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return payload


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Register and login simulated drivers against the API gateway.")
    parser.add_argument("--drivers", type=int, default=100, help="Number of drivers to register/login.")
    parser.add_argument("--url", default="http://localhost:8000", help="API gateway base URL, e.g. http://localhost:8000")
    parser.add_argument("--email-prefix", default="driver", help="Email prefix used for generated drivers.")
    parser.add_argument("--password", default="LoadTestPass123!", help="Password for generated drivers.")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent / "tokens.json"), help="Path to the tokens JSON file.")
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    if args.drivers <= 0:
        raise SystemExit("--drivers must be > 0")

    out_path = Path(args.out).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    base_url = normalize_http_url(args.url)
    result = await build_tokens(base_url, args.drivers, out_path, args.email_prefix, args.password)
    print(f"Prepared {len(result['drivers'])} drivers -> {out_path}")
    print(f"Sample: {result['drivers'][0]['email']} -> token stored")


if __name__ == "__main__":
    asyncio.run(main())
