# POST /internal/trips/{id}/accept

**Service:** `dispatch-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `ws-gateway` (khi tài xế bấm nhận chuyến trên app)  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | UUID | ID chuyến đi |

## Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `driverId` | long | yes | ID tài xế nhận chuyến |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "accepted": true, "tripId": UUID }` |
| 409 | Trip already accepted / cancelled |
| 404 | Trip not found |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
POST /internal/trips/550e8400-e29b-41d4-a716-446655440002/accept
Content-Type: application/json

{ "driverId": 67890 }
```

## Ví dụ Response

```json
{ "accepted": true, "tripId": "550e8400-e29b-41d4-a716-446655440002" }
```

## Ghi chú
- Atomic: chỉ 1 tài xế nhận được chuyến (optimistic lock / Redis SETNX)
- Trả 409 nếu chuyến đã bị nhận hoặc hủy
- Sau khi accept: dispatch gọi location-service để đặt tài xế BUSY, ws-gateway để map route