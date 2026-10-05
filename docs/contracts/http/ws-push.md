# POST /internal/push

**Service:** `ws-gateway`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (gửi yêu cầu chuyến đến tài xế qua WebSocket)  
**Content-Type:** `application/json`

## Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | long | yes | ID tài xế nhận push |
| `type` | string | yes | Loại message: `TRIP_REQUEST` \| `TRIP_CANCELLED` \| `TRIP_ACCEPTED` \| `NOTIFICATION` |
| `payload` | object | yes | Dữ liệu tùy theo `type` |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "delivered": true }` |
| 404 | User not connected |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```json
{
  "userId": 67890,
  "type": "TRIP_REQUEST",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440002",
    "pickup": { "lat": 10.7769, "lng": 106.7009 }
  }
}
```

## Ví dụ Response

```json
{ "delivered": true }
```

## Ghi chú
- `delivered=true` chỉ có nghĩa là message đã đẩy vào outbox/WS connection, không đảm bảo client nhận
- Nếu tài xế offline, lưu vào pending queue để retry khi reconnect
- Timeout 500ms-2s, không retry vô hạn từ caller