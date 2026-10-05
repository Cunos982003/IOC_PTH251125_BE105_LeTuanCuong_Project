# Standard Error Format

**All internal HTTP endpoints** return errors in this format.

## Format

```json
{
  "code": "ERROR_CODE",
  "message": "Human readable description"
}
```

## Common Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `UNAUTHORIZED` | 401 | Missing or invalid `X-Internal-Key` |
| `FORBIDDEN` | 403 | Valid key but not allowed for this endpoint |
| `NOT_FOUND` | 404 | Resource not found |
| `VALIDATION_ERROR` | 400 | Request body/query validation failed |
| `CONFLICT` | 409 | Resource conflict (e.g., trip already accepted) |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `SERVICE_UNAVAILABLE` | 503 | Downstream service unavailable |

## Ví dụ

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Invalid request: lat is required"
}
```

```json
{
  "code": "CONFLICT",
  "message": "Trip already accepted by another driver"
}
```

## Ghi chú
- Luôn trả JSON, kể cả lỗi 5xx
- `message` dành cho developer/debug, không hiển thị trực tiếp cho end-user
- Log đầy đủ (stack trace, request ID) ở server-side