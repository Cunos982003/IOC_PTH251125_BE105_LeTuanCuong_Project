# GET /internal/wallets/{userId}/balance

**Service:** `payment-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `api-gateway` (hiển thị số dư), `ws-gateway` (kiểm tra ví tài xế)  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `userId` | long | ID người dùng/tài xế |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "userId": long, "balance": long }` |
| 404 | Wallet not found |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
GET /internal/wallets/12345/balance
```

## Ví dụ Response

```json
{ "userId": 12345, "balance": 2500000 }
```

## Ghi chú
- `balance` số nguyên (đồng), có thể âm nếu cho overdraft
- Dùng cho hiển thị trên app và kiểm tra đủ tiền trước khi đặt chuyến
- Cache Redis TTL ngắn (10-30s) để giảm tải DB