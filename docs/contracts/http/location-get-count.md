# GET /internal/drivers/count

**Service:** `location-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `pricing-service` (tính surge dựa trên mật độ tài xế)  
**Content-Type:** `application/json`

## Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `lat` | double | yes | - | Vĩ độ tâm tìm kiếm |
| `lng` | double | yes | - | Kinh độ tâm tìm kiếm |
| `radiusM` | int | no | 2000 | Bán kính tìm kiếm (mét) |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "count": <số nguyên> }` |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
GET /internal/drivers/count?lat=10.7769&lng=106.7009&radiusM=2000
```

## Ví dụ Response

```json
{ "count": 15 }
```

## Ghi chú
- Dùng Redis GEOSEARCH COUNT
- Chỉ đếm tài xế `ONLINE` và không `BUSY`
- Dùng cho surge pricing: ít tài xế = surge cao