# DriverOffered Event

**Exchange:** `events` (topic)  
**Routing Key:** `trips.offered`  
**Writer:** `dispatch-service`  
**Queues:** `trip.events.offered`  
**Readers:** `ws-gateway` (push real-time đến app tài xế), `dispatch-service` (timeout handling)  
**Consumer group:** `ws-driver-offers`, `dispatch-offer-timeout`

## Message Properties

| Property | Value |
|----------|-------|
| `content-type` | `application/json` |
| `delivery_mode` | `2` (persistent) |
| `headers.eventId` | UUID (idempotency key) |
| `headers.type` | `trips.offered` |

## Payload Fields

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | UUID | ID duy nhất của sự kiện (idempotency key) |
| `tripId` | UUID | ID chuyến đi |
| `driverId` | long | ID tài xế được ghép |
| `customerId` | long | ID khách hàng |
| `pickup` | object | Điểm đón: `lat` (double), `lng` (double) |
| `fare` | long | Cước phí ước tính (đồng, số nguyên) |
| `expiresAt` | ISO-8601 UTC | Thời hạn chấp nhận chuyến |

## Ví dụ

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440005",
  "tripId": "550e8400-e29b-41d4-a716-446655440006",
  "driverId": 67890,
  "customerId": 12345,
  "pickup": {
    "lat": 10.7769,
    "lng": 106.7009
  },
  "fare": 120000,
  "expiresAt": "2026-10-04T10:32:00Z"
}
```

## Ghi chú
- `eventId` dùng làm khóa idempotent
- `fare` là số nguyên (đồng), không dùng double
- `ws-gateway` push real-time đến app tài xế qua WebSocket
- `dispatch-service` nghe để xử lý timeout (nếu tài xế không chấp nhận trước `expiresAt`)
- Message được publish với `mandatory=true`, publisher confirm enabled
- Consumer ack sau khi xử lý xong, retry 3 lần với backoff rồi DLQ