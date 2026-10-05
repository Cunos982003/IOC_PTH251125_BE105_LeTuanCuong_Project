# UserRegistered Event

**Stream:** `events.users`  
**Writer:** `user-service`  
**Readers:** `payment-service` (cập nhật ví), `user-service` (audit log)  
**Consumer group:** `payment-users`, `user-audit`

## Payload Fields

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | UUID | ID duy nhất của sự kiện (idempotency key) |
| `userId` | long | ID người dùng/tài xế |
| `role` | string | `PASSENGER` \| `DRIVER` |
| `fullName` | string | Họ tên đầy đủ |
| `registeredAt` | ISO-8601 UTC | Thời điểm đăng ký |

## Ví dụ

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 12345,
  "role": "DRIVER",
  "fullName": "Nguyễn Văn A",
  "registeredAt": "2026-10-04T10:30:00Z"
}
```

## Ghi chú
- `eventId` dùng làm khóa idempotent khi consumer xử lý
- `payment-service` dùng để tạo ví điện tử cho user mới
- Tiền là số nguyên (đồng) - không áp dụng cho event này