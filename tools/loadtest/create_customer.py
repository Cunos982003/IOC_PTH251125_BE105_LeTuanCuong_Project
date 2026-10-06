#!/usr/bin/env python3
"""Create a customer and add token to tokens.json."""
import json
import requests
from pathlib import Path

API_GATEWAY = "http://localhost:8000"
EMAIL = "customer.loadtest@test.com"
PASSWORD = "test12345"
FULL_NAME = "Test Customer Loadtest"

def main():
    # Register
    try:
        resp = requests.post(
            f"{API_GATEWAY}/api/v1/auth/register",
            json={"email": EMAIL, "password": PASSWORD, "fullName": FULL_NAME, "role": "CUSTOMER"},
            timeout=5
        )
        if resp.status_code in (200, 201):
            print(f"[OK] Customer registered: {EMAIL}")
        elif resp.status_code == 409:
            print(f"Customer {EMAIL} already exists")
        else:
            print(f"Register failed: {resp.status_code} {resp.text}")
    except Exception as e:
        print(f"Register error: {e}")

    # Login
    try:
        resp = requests.post(
            f"{API_GATEWAY}/api/v1/auth/login",
            json={"email": EMAIL, "password": PASSWORD},
            timeout=5
        )
        if resp.status_code != 200:
            print(f"Login failed: {resp.status_code} {resp.text}")
            return

        data = resp.json()
        token = data.get("accessToken") or data.get("token")
        if not token:
            print(f"No token in response: {data}")
            return

        print(f"[OK] Login successful")

        # Update tokens.json
        tokens_file = Path(__file__).parent / "tokens.json"
        with open(tokens_file) as f:
            tokens_data = json.load(f)

        tokens_data["customer"] = {"token": token}

        with open(tokens_file, "w") as f:
            json.dump(tokens_data, f, indent=2)

        print(f"[OK] Token saved to tokens.json")

    except Exception as e:
        print(f"Login error: {e}")


if __name__ == "__main__":
    main()
