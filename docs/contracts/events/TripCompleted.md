# TripCompleted Event

**Stream:** `events.trips`  
**Writer:** `dispatch-service`  
**Readers:** `payment-service` (thanh toán, chia hoa hồng), `user-service` (cập nhật lịch sử)  
**Consumer group:** `payment-trips`, `user-trips`

## Payload Fields

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | UUID | ID duy nhất của sự kiện (idempotency key) |
| `tripId` | UUID | ID chuyến đi |
| `customerId` | long | ID khách hàng |
| `driverId` | long | ID tài xế |
| `fare` | long | Cước phí (đồng, số nguyên) |
| `completedAt` | ISO-8601 UTC | Thời điểm hoàn thành chuyến |

## Ví dụ

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440001",
  "tripId": "550e8400-e29b-41d4-a716-446655440002",
  "customerId": 12345,
  "driverId": 67890,
  "fare": 150000,
  "completedAt": "2026-10-04T10:45:00Z"
}
```

## Ghi chú
- `eventId` dùng làm khóa idempotent
- `fare` là số nguyên (đồng), không dùng double
- `payment-service` trừ tiền khách, cộng hoa hồng tài xế
- `user-service` cập nhật lịch sử chuyến của cả hai bên