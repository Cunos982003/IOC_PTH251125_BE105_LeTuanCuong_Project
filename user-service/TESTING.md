# Lệnh kiểm tra user-service

## 1. Kiểm tra compilation và test
mvn -pl user-service verify

## 2. Kiểm tra contracts khớp với docs/contracts/
powershell.exe -ExecutionPolicy Bypass -File "scripts\check-contracts.ps1"

## 3. Kiểm tra Flyway migration tạo đủ bảng
# Khởi động database và service:
docker compose -f infra/compose.yaml up -d
Start-Sleep -Seconds 10

# Kết nối DB và kiểm tra:
docker exec -it postgres psql -U user_app -d user -c "\dt"

# Kết quả mong đợi:
# - users
# - drivers  
# - vehicles
# - trip_history
# - outbox
# - flyway_schema_history

## 4. Test đăng ký user và kiểm tra outbox
# Start user-service:
cd user-service
$env:SERVER_PORT=8081
$env:DB_URL="jdbc:postgresql://localhost:5432/user"
$env:DB_USERNAME="user_app"
$env:DB_PASSWORD="user123"
$env:REDIS_HOST="localhost"
$env:REDIS_PASSWORD="redis123"
$env:JWT_SECRET="dGVzdC1zZWNyZXQtZm9yLWp3dC1zaWduaW5nLW11c3QtYmUtbG9uZy1lbm91Z2g="
$env:INTERNAL_KEY="test-internal-key-12345"
mvn spring-boot:run

# Trong terminal khác:
# Đăng ký user
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/api/v1/auth/register" `
  -ContentType "application/json" `
  -Body '{"email":"test@example.com","password":"password123","role":"CUSTOMER","fullName":"Test User"}'

# Kiểm tra outbox trong DB
docker exec -it postgres psql -U user_app -d user -c "SELECT id, stream, sent_at FROM outbox;"

# Kết quả mong đợi:
# - Một dòng trong outbox với stream='events.users'
# - sent_at sẽ được điền sau vài giây (OutboxWorker chạy mỗi 500ms)

## 5. Kiểm tra Redis Stream nhận event
docker exec -it redis redis-cli -a redis123 XREAD COUNT 10 STREAMS events.users 0

# Kết quả mong đợi: thấy UserRegistered event với userId, role, fullName

## 6. Test TripCompleted consumer
# Phát sự kiện TripCompleted vào Redis:
docker exec -it redis redis-cli -a redis123 XADD events.trips "*" payload '{\"eventId\":\"550e8400-e29b-41d4-a716-446655440000\",\"tripId\":\"123e4567-e89b-12d3-a456-426614174000\",\"customerId\":1,\"driverId\":2,\"fare\":50000,\"completedAt\":\"2026-01-15T10:30:00Z\"}'

# Đợi vài giây, kiểm tra trip_history
docker exec -it postgres psql -U user_app -d user -c "SELECT * FROM trip_history;"

# Kết quả mong đợi: một dòng với trip_id, customer_id, driver_id, fare, status='COMPLETED'

## 7. Test idempotency - gửi lại event
docker exec -it redis redis-cli -a redis123 XADD events.trips "*" payload '{\"eventId\":\"550e8400-e29b-41d4-a716-446655440001\",\"tripId\":\"123e4567-e89b-12d3-a456-426614174000\",\"customerId\":1,\"driverId\":2,\"fare\":50000,\"completedAt\":\"2026-01-15T10:30:00Z\"}'

# Kiểm tra lại trip_history - vẫn chỉ một dòng (vì ON CONFLICT DO NOTHING)
docker exec -it postgres psql -U user_app -d user -c "SELECT COUNT(*) FROM trip_history WHERE trip_id='123e4567-e89b-12d3-a456-426614174000';"

# Kết quả mong đợi: count = 1

## 8. Dọn dẹp
# Stop service (Ctrl+C)
# Stop infra
docker compose -f infra/compose.yaml down -v
