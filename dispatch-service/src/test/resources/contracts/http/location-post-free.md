# POST /internal/drivers/{id}/free

**Service:** `location-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (khi chuyến kết thúc hoặc hủy)  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | long | ID tài xế |

## Request Body

Rỗng `{}`

## Response

| Status | Body |
|--------|------|
| 200 | `{ "status": "ONLINE" }` |
| 404 | Driver not found |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
POST /internal/drivers/67890/free
Content-Type: application/json

{}
```

## Ví dụ Response

```json
{ "status": "ONLINE" }
```

## Ghi chú
- Đánh dấu tài xế `ONLINE` trở lại (có thể nhận chuyến mới)
- Xóa `tripId` liên kết
- Gọi khi chuyến `COMPLETED` hoặc `CANCELLED`