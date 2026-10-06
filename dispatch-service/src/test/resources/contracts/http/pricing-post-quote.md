# POST /internal/quote

**Service:** `pricing-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (tính giá trước khi ghép chuyến)  
**Content-Type:** `application/json`

## Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `pickup` | object | yes | `{lat, lng}` điểm đón |
| `dropoff` | object | yes | `{lat, lng}` điểm đến |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "distanceM": long, "durationS": long, "surge": double, "fare": long }` |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```json
{
  "pickup": { "lat": 10.7769, "lng": 106.7009 },
  "dropoff": { "lat": 10.7829, "lng": 106.7059 }
}
```

## Ví dụ Response

```json
{
  "distanceM": 1500,
  "durationS": 600,
  "surge": 1.2,
  "fare": 45000
}
```

## Ghi chú
- `fare` = `(baseFare + distanceFare + timeFare) * surge` → làm tròn số nguyên (đồng)
- `surge` ≥ 1.0 (double), lấy từ Redis theo geohash + time bucket
- `distanceM`, `durationS` từ OSRM/Google Maps (mock nếu chưa có)
- Timeout 500ms-2s