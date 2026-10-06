# GET /internal/users/{id}

**Service:** `user-service`  
**Auth:** `X-Internal-Key` header  
**Caller:** `api-gateway`, `ws-gateway`, `dispatch-service`, `payment-service`  
**Content-Type:** `application/json`

## Path Parameter

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | long | ID người dùng/tài xế |

## Response

| Status | Body |
|--------|------|
| 200 | `{ "id": long, "role": string, "fullName": string }` |
| 404 | User not found |
| 400 | Error format `{code, message}` |
| 401 | Missing/invalid `X-Internal-Key` |

## Ví dụ Request

```
GET /internal/users/12345
```

## Ví dụ Response

```json
{ "id": 12345, "role": "DRIVER", "fullName": "Nguyễn Văn A" }
```

## Ghi chú
- `role`: `PASSENGER` \| `DRIVER` \| `ADMIN`
- Không trả mật khẩu, token, thông tin nhạy cảm
- Cache Redis TTL 1-5 phút