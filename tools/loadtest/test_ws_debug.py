#!/usr/bin/env python3
"""Debug WebSocket connection to find handshake issue."""
import asyncio
import websockets
import json
import sys

async def test_connection():
    token = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI4OSIsInJvbGUiOiJEUklWRVIiLCJpYXQiOjE3OTEzMDE5ODMsImV4cCI6MTc5MTMwNTU4M30.fLgcSvziL7INgsN0rER-J6AMpX3oy073kedqUn5iv4I'
    url = 'ws://localhost:8001/ws/driver'

    print(f'Testing connection to {url}')
    print(f'websockets version: {websockets.__version__}')

    try:
        # Try with default headers
        print('\n--- Attempt 1: Default headers ---')
        async with websockets.connect(url) as ws:
            print(f'✓ Connected! Local: {ws.local_address}, Remote: {ws.remote_address}')

            # Send auth
            auth_msg = {'t': 'auth', 'token': token}
            await ws.send(json.dumps(auth_msg))
            print('✓ Auth message sent')

            # Try to receive
            try:
                response = await asyncio.wait_for(ws.recv(), timeout=2.0)
                print(f'Received: {response}')
            except asyncio.TimeoutError:
                print('✓ No error response (likely authenticated)')

            print('✓ Connection successful!')

    except websockets.exceptions.InvalidStatusCode as e:
        print(f'✗ Invalid status code: {e.status_code}')
        print(f'  Headers: {e.headers}')
    except Exception as e:
        print(f'✗ Error: {type(e).__name__}: {e}')
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == '__main__':
    asyncio.run(test_connection())