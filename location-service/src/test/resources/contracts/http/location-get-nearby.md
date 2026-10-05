# GET /internal/drivers/nearby

**Service:** `location-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (tìm tài xế gần cho ghép chuyến)  
**Content-Type:** `application/json`

## Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `lat` | double | yes | - | Vĩ độ tâm tìm kiếm |
| `lng` | double | yes | - | Kinh độ tâm tìm kiếm |
| `radiusM` | int | no | 2000 | Bán kính tìm kiếm (mét) |
| `limit` | int | no | 10 | Số lượng tối đa |

## Response

| Status | Body |
|--------|------|
| 200 | `[{"driverId": long, "distanceM": int}]` |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
GET /internal/drivers/nearby?lat=10.7769&lng=106.7009&radiusM=2000&limit=10
```

## Ví dụ Response

```json
[
  { "driverId": 67890, "distanceM": 150 },
  { "driverId": 67891, "distanceM": 320 }
]
```

## Ghi chú
- Dùng Redis GEOSEARCH (không dùng GEORADIUS)
- `distanceM` làm tròn số nguyên mét
- Chỉ trả tài xế đang `ONLINE` và không `BUSY`
- `lng` đứng TRƯỚC `lat` trong Redis GEO