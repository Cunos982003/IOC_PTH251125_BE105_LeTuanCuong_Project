# TripCancelled Event

**Exchange:** `events` (topic)  
**Routing Key:** `trips.cancelled`  
**Writer:** `dispatch-service`  
**Queues:** `trip.events.cancelled`  
**Readers:** `payment-service` (hoàn tiền nếu đã khấu), `user-service` (cập nhật lịch sử)  
**Consumer group:** `payment-trips`, `user-trips`

## Message Properties

| Property | Value |
|----------|-------|
| `content-type` | `application/json` |
| `delivery_mode` | `2` (persistent) |
| `headers.eventId` | UUID (idempotency key) |
| `headers.type` | `trips.cancelled` |

## Payload Fields

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | UUID | ID duy nhất của sự kiện (idempotency key) |
| `tripId` | UUID | ID chuyến đi |
| `customerId` | long | ID khách hàng |
| `driverId` | long \| null | ID tài xế (null nếu chưa ghép được tài xế) |
| `reason` | string | Lý do: `DRIVER_NOT_FOUND` \| `CUSTOMER_CANCEL` \| `DRIVER_CANCEL` \| `TIMEOUT` \| `SYSTEM_ERROR` |
| `cancelledAt` | ISO-8601 UTC | Thời điểm hủy chuyến |

## Ví dụ

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440003",
  "tripId": "550e8400-e29b-41d4-a716-446655440004",
  "customerId": 12345,
  "driverId": 67890,
  "reason": "DRIVER_NOT_FOUND",
  "cancelledAt": "2026-10-04T10:35:00Z"
}
```

## Ghi chú
- `eventId` dùng làm khóa idempotent
- `driverId` có thể null nếu hủy trước khi ghép tài xế
- `payment-service` hoàn tiền nếu khách đã bị khấu
- Message được publish với `mandatory=true`, publisher confirm enabled
- Consumer ack sau khi xử lý xong, retry 3 lần với backoff rồi DLQ