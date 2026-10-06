# POST /internal/drivers/{id}/busy

**Service:** `location-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `dispatch-service` (khi tài xế nhận chuyến)  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | long | ID tài xế |

## Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `tripId` | UUID | yes | ID chuyến đi được nhận |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "status": "BUSY" }` |
| 404 | Driver not found |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
POST /internal/drivers/67890/busy
Content-Type: application/json

{ "tripId": "550e8400-e29b-41d4-a716-446655440002" }
```

## Ví dụ Response

```json
{ "status": "BUSY" }
```

## Ghi chú
- Đánh dấu tài xế `BUSY` trong Redis (loại khỏi tìm kiếm nearby)
- Lưu `tripId` để rollback nếu hủy
- Timeout 500ms-2s, không retry vô hạn