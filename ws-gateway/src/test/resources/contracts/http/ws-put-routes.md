# PUT /internal/routes/{driverId}

**Service:** `ws-gateway`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (khi tài xế nhận chuyến, map route tài xế -> khách)  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `driverId` | long | ID tài xế |

## Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `customerId` | long | yes | ID khách hàng trên chuyến này |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "mapped": true }` |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
PUT /internal/routes/67890
Content-Type: application/json

{ "customerId": 12345 }
```

## Ví dụ Response

```json
{ "mapped": true }
```

## Ghi chú
- Dùng để route WebSocket message từ khách -> tài xế đúng connection
- Redis hash `ws:routes` TTL theo trip
- Xóa khi chuyến kết thúc/hủy (DELETE /internal/routes/{driverId})