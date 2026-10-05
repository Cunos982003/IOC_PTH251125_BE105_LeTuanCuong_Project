# WS Gateway - WebSocket Gateway Service

Service xử lý WebSocket cho tài xế và khách hàng trong hệ thống ride-hailing.

## Kiến trúc

- **Cổng**: 8001
- **Endpoints**: `/ws/driver`, `/ws/customer`
- **Dependencies**: Redis (session, batching)
- **Stack**: Spring WebSocket, JdbcClient (không có database riêng)

## Giao thức WebSocket

### Client → Server

```json
{"t":"auth","token":"<JWT>"}
{"t":"location","lat":10.762622,"lng":106.660172,"sent_at":1234567890}
{"t":"accept","tripId":"123"}
```

### Server → Client

```json
{"t":"offer","tripId":"123",...}
{"t":"driver_location","lat":10.762622,"lng":106.660172,"sent_at":1234567890}
{"t":"trip_update","status":"ACCEPTED"}
{"t":"error","code":"AUTH_FAILED","message":"Invalid token"}
```

## Tính năng chính

1. **Authentication**: JWT trong message đầu tiên (5 giây timeout)
2. **Rate limiting**: 5 message/giây mỗi kết nối
3. **Location batching**: Gom location từ nhiều tài xế rồi gửi đến location-service mỗi 200ms
4. **Ping/Pong**: Ping 25s, pong timeout 60s
5. **Message size limit**: 4 KB

## Test thủ công với wscat

### 1. Cài wscat

```bash
npm install -g wscat
```

### 2. Tạo JWT token

Dùng script Python hoặc online tool (jwt.io) với:
- Secret: giá trị của `JWT_SECRET` (base64)
- Payload: `{"sub":"driver123","role":"driver","exp":9999999999}`

### 3. Kết nối và gửi message

```bash
# Kết nối driver
wscat -c ws://localhost:8001/ws/driver

# Gửi auth (trong 5 giây)
{"t":"auth","token":"eyJhbGc..."}

# Gửi location
{"t":"location","lat":10.762622,"lng":106.660172,"sent_at":1696500000000}
```

### 4. Kiểm tra dữ liệu trong Redis

Sau khi gửi location, kiểm tra trong location-service:

```bash
curl "http://localhost:8082/api/drivers/nearby?lat=10.762622&lng=106.660172&radius=5000"
```

## Build & Run

### Development

```bash
mvn clean test
mvn spring-boot:run
```

### Docker

```bash
# Build từ root
mvn clean package -pl ws-gateway -am
docker build -t ws-gateway --build-arg MODULE=ws-gateway .

# Run
docker run -p 8001:8001 \
  -e REDIS_HOST=redis \
  -e REDIS_PASSWORD=yourpass \
  -e JWT_SECRET=dGVzdC1zZWNyZXQta2V5LTMyLWJ5dGVzLWxvbmch \
  -e INTERNAL_KEY=your-internal-key \
  -e LOCATION_SERVICE_URL=http://location-service:8082 \
  -e DISPATCH_SERVICE_URL=http://dispatch-service:8083 \
  ws-gateway
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERVER_PORT` | No | 8001 | Port server |
| `REDIS_HOST` | No | localhost | Redis host |
| `REDIS_PASSWORD` | Yes | - | Redis password |
| `jwt.secret` | Yes | - | JWT secret (base64) |
| `internal.key` | Yes | - | Internal API key |
| `services.location` | No | http://localhost:8082 | Location service URL |
| `services.dispatch` | No | http://localhost:8083 | Dispatch service URL |

## Tests

- `WebSocketAuthTest`: Auth timeout, role mismatch, invalid token
- `LocationBatchingTest`: 50 drivers → ít HTTP calls, fake driverId bị reject
- `RateLimitTest`: Rate limit 5 msg/s, message > 4KB bị reject
