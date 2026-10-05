# POST /internal/demand

**Service:** `pricing-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (khi có yêu cầu đặt chuyến mới)  
**Content-Type:** `application/json`

## Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `tripId` | UUID | yes | ID chuyến đi |
| `lat` | double | yes | Vĩ độ điểm đón |
| `lng` | double | yes | Kinh độ điểm đón |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "recorded": true }` |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440002",
  "lat": 10.7769,
  "lng": 106.7009
}
```

## Ví dụ Response

```json
{ "recorded": true }
```

## Ghi chú
- Ghi nhận nhu cầu (demand) tại geohash + time bucket để tính surge
- Dùng Redis sorted set hoặc hash với TTL
- Không cần response body phức tạp, chỉ xác nhận ghi nhận