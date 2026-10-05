# POST /internal/locations

**Service:** `location-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `ws-gateway` (nhận GPS từ tài xế qua WebSocket)  
**Content-Type:** `application/json`

## Request Body

Array of location updates:

| Field | Type | Description |
|-------|------|-------------|
| `driverId` | long | ID tài xế |
| `lat` | double | Vĩ độ |
| `lng` | double | Kinh độ |
| `sentAt` | ISO-8601 UTC | Thời điểm tài xế gửi |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "accepted": <số lượng> }` |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```json
[
  {
    "driverId": 67890,
    "lat": 10.7769,
    "lng": 106.7009,
    "sentAt": "2026-10-04T10:30:00Z"
  }
]
```

## Ví dụ Response

```json
{ "accepted": 1 }
```

## Ghi chú
- Batch nhiều cập nhật trong một request để giảm overhead
- `lng` đứng TRƯỚC `lat` theo quy ước Redis GEO
- Idempotent: gửi trùng `driverId`+`sentAt` chỉ cập nhật vị trí mới nhất