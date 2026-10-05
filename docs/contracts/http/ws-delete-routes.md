# DELETE /internal/routes/{driverId}

**Service:** `ws-gateway`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (khi chuyến kết thúc/hủy, xóa mapping route)  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `driverId` | long | ID tài xế |

## Request Body

Rỗng `{}`

## Response

| Status | Body |
|--------|------|
| 200 | `{ "deleted": true }` |
| 404 | Route not found |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
DELETE /internal/routes/67890
```

## Ví dụ Response

```json
{ "deleted": true }
```

## Ghi chú
- Xóa mapping `ws:routes` cho tài xế
- Gọi khi chuyến `COMPLETED` hoặc `CANCELLED`
- Idempotent: xóa nhiều lần không lỗi