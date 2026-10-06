# Hướng dẫn chạy E2E Test

## Bước 1: Khởi động hệ thống

```powershell
# Khởi động tất cả services với profile apps
docker compose --profile apps up -d

# Đợi services khởi động hoàn tất (khoảng 30 giây)
Start-Sleep -Seconds 30

# Kiểm tra trạng thái
docker compose ps
```

## Bước 2: Chạy E2E test

### Cách 1: Dùng script PowerShell (khuyến nghị)

```powershell
.\tools\e2e\run-e2e.ps1
```

### Cách 2: Chạy thủ công

```powershell
cd tools\e2e

# Build module
mvn clean package -DskipTests

# Set environment variables
$env:API_GATEWAY_URL = "http://localhost:8000"
$env:WS_GATEWAY_URL = "ws://localhost:8001/ws"
$env:REDIS_HOST = "localhost"
$env:REDIS_PASSWORD = "redis_password_123"

# Run test
mvn exec:java -Dexec.mainClass="com.ridehailing.e2e.scenario.ScenarioRunner"
```

## Kịch bản được test

| Kịch bản | Mô tả | Kỳ vọng |
|----------|-------|---------|
| **Happy Path** | Đặt xe thành công: rider đăng ký → driver đăng ký → driver gửi vị trí → rider đặt xe → driver accept → arrive → start → complete → kiểm tra ví | Rider trừ tiền, driver nhận 80% fare; phát lại event không làm số dư thay đổi |
| **No Driver Found** | Đặt xe khi không có tài xế trong vùng | Trả về NO_DRIVER_FOUND trong ≤25 giây |
| **Cancellation** | Rider hủy chuyến khi driver đang đến | Driver rảnh lại và xuất hiện trong nearby khi gửi vị trí |
| **Insufficient Balance** | Đặt xe khi số dư không đủ | Trả về 402 Payment Required |

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

## Troubleshooting

### Test timeout hoặc connection refused
```powershell
# Kiểm tra services đang chạy
docker compose ps

# Kiểm tra logs nếu có service failed
docker compose logs api-gateway
docker compose logs ws-gateway
docker compose logs payment-service

# Restart nếu cần
docker compose restart
```

### Wallet không được tạo (timeout 5 giây)
```powershell
# Kiểm tra payment-service có đọc events.users không
docker compose logs payment-service | Select-String "UserRegistered"

# Kiểm tra Redis streams
docker exec -it ride-hailing-redis-1 redis-cli -a redis_password_123 XINFO STREAM events.users
```

### Test failed do race condition
```powershell
# Chạy lại - mỗi test dùng email ngẫu nhiên nên không bị xung đột
.\tools\e2e\run-e2e.ps1
```

### Port đã được sử dụng
```powershell
# Kiểm tra port 8000, 8001
netstat -ano | findstr ":8000"
netstat -ano | findstr ":8001"

# Dừng các process đang chiếm port hoặc thay đổi port trong docker-compose.yml
```

## Chạy lại từ đầu

```powershell
# Dừng và xóa containers + volumes
docker compose down -v

# Khởi động lại
docker compose --profile apps up -d
Start-Sleep -Seconds 30

# Chạy test
.\tools\e2e\run-e2e.ps1
```

## Lưu ý

- Mỗi kịch bản dùng email ngẫu nhiên (`UUID`) để tránh xung đột dữ liệu
- Test có thể chạy nhiều lần mà không cần xóa database
- Tổng thời gian chạy: ~30-60 giây tùy vào hiệu năng máy
- Script tự động kiểm tra Docker Compose đã khởi động chưa trước khi chạy test
