# E2E Test Suite

Module test E2E độc lập cho hệ thống Ride Hailing.

## Cấu trúc

```
tools/e2e/
├── pom.xml                                 # Maven config
├── run-e2e.ps1                            # PowerShell runner script
└── src/main/java/com/ridehailing/e2e/
    ├── client/                            # HTTP/WebSocket/Redis clients
    │   ├── ApiGatewayClient.java
    │   ├── WsGatewayClient.java
    │   └── RedisTestClient.java
    ├── model/                             # DTO models
    │   ├── RegisterRequest.java
    │   ├── LoginRequest.java
    │   ├── TripRequest.java
    │   └── ...
    └── scenario/                          # Test scenarios
        ├── ScenarioRunner.java            # Main runner
        ├── HappyPathScenario.java
        ├── NoDriverScenario.java
        ├── CancellationScenario.java
        └── InsufficientBalanceScenario.java
```

## Kịch bản test

### 1. Happy Path
- Đăng ký 1 khách + 1 tài xế qua api-gateway
- Chờ ví được tạo (tối đa 5 giây)
- Tài xế nối ws-gateway và gửi vị trí
- Khách đặt xe với Idempotency-Key
- Tài xế nhận offer và accept qua WebSocket
- Tài xế arrive → start → complete
- Kiểm tra ví: khách giảm fare, tài xế tăng 80% fare
- Phát lại TripCompleted bằng XADD thủ công vào stream
- Kiểm tra số dư không đổi (idempotent)

### 2. No Driver Found
- Đặt xe khi không có tài xế
- Phải trả NO_DRIVER_FOUND trong ≤25 giây

### 3. Cancellation
- Khách hủy chuyến khi tài xế đang đến
- Kiểm tra tài xế rảnh lại (xuất hiện trong nearby sau khi gửi vị trí)

### 4. Insufficient Balance
- Đặt xe nhiều lần cho đến khi hết tiền
- Thử đặt xe khi số dư không đủ → phải trả 402

## Chạy test

### Yêu cầu
```powershell
# Khởi động tất cả services
docker compose --profile apps up -d

# Đợi services sẵn sàng
Start-Sleep -Seconds 10
```

### Chạy bằng script
```powershell
.\tools\e2e\run-e2e.ps1
```

### Chạy thủ công
```powershell
cd tools\e2e

# Build
mvn clean package -DskipTests

# Set environment
$env:API_GATEWAY_URL = "http://localhost:8000"
$env:WS_GATEWAY_URL = "ws://localhost:8001/ws"
$env:REDIS_HOST = "localhost"
$env:REDIS_PASSWORD = "redis_password_123"

# Run
mvn exec:java -Dexec.mainClass="com.ridehailing.e2e.scenario.ScenarioRunner"
```

## Output mẫu

```
================================================================================
E2E Test Suite - Ride Hailing System
================================================================================
API Gateway: http://localhost:8000
WS Gateway: ws://localhost:8001/ws
Redis: localhost
================================================================================

Running: Happy Path...
Running: No Driver Found...
Running: Cancellation...
Running: Insufficient Balance...

================================================================================
RESULTS
================================================================================
Scenario                       Result          Duration (ms)   Message
--------------------------------------------------------------------------------
Happy Path                     ✓ PASSED        8234            OK
No Driver                      ✓ PASSED        15678           NO_DRIVER_FOUND in 14123ms
Cancellation                   ✓ PASSED        6543            Driver became available again after cancellation
Insufficient Balance           ✓ PASSED        12345           Got 402 after 3 trips
================================================================================
Total: 4 | Passed: 4 | Failed: 0
================================================================================
```

## Tiêu chí đạt

- Toàn bộ kịch bản trong bảng đạt
- Chạy lại lần hai vẫn đạt (không phụ thuộc dữ liệu cũ)
- Mỗi kịch bản dùng email ngẫu nhiên để tránh xung đột

## Troubleshooting

### Test timeout
- Tăng thời gian chờ trong các scenario
- Kiểm tra logs của services: `docker compose logs -f`

### Connection refused
- Kiểm tra services đã khởi động: `docker compose ps`
- Kiểm tra ports: `netstat -an | findstr "8000 8001"`

### Wallet không được tạo
- Kiểm tra payment-service logs
- Kiểm tra consumer group của payment-service đã đọc events.users chưa
