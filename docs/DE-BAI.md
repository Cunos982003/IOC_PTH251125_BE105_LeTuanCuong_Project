# Đề bài gốc - ĐỀ TÀI 2: HỆ THỐNG ĐẶT XE VÀ GIAO HÀNG THEO YÊU CẦU THEO THỜI GIAN THỰC

## Tên đề tài
Thiết kế và phát triển hệ thống Đặt xe & Giao hàng thời gian thực (mô hình Grab/Gojek) trên kiến trúc Microservices.

## Mô tả
Hệ thống quản lý kết nối giữa Khách hàng (Passenger/Customer) và Tài xế (Driver), định vị vị trí thời gian thực (Real-time GPS Tracking), tính toán cước phí linh hoạt (Surge Pricing) và ghép nối chuyến xe thông minh (Matching Engine).

## Tính thực tế doanh nghiệp
- Xử lý hàng trăm ngàn tọa độ GPS gửi lên mỗi giây từ ứng dụng của tài xế.
- Đòi hỏi độ trễ cực thấp trong việc ghép nối tài xế gần nhất với khách hàng.
- Phân tách service giúp đảm bảo nếu tính năng tính cước/khuyến mãi bị quá tải, hệ thống định vị tài xế vẫn hoạt động ổn định.

## Yêu cầu kỹ thuật (R1-R16)

### R1: Real-time Performance
Vị trí tài xế cập nhật liên tục và hiển thị mượt mà trên bản đồ khách hàng qua WebSocket với độ trễ < 500ms.

### R2: Matching Accuracy
Hệ thống gửi tín hiệu đặt xe đến đúng tài xế đang rảnh và ở gần nhất.

### R3: Microservices Architecture
7 microservice độc lập, không chia sẻ database, giao tiếp qua HTTP nội bộ và RabbitMQ.

### R4: Customer Web App
Ứng dụng web khách hàng có bản đồ, đặt xe, theo dõi trạng thái chuyến.

### R5: Spatial Query Performance
Redis GEOSEARCH tìm kiếm tài xế trong bán kính 2km < 2ms.

### R6: WebSocket Failover
Hai replica ws-gateway, client tự reconnect khi một instance down.

### R7: State Machine
Trip status: CREATED → MATCHING → ACCEPTED → PICKING_UP → IN_TRIP → COMPLETED, có CANCELLED, NO_DRIVER_FOUND.

### R8: Surge Pricing
Tính toán cước phí dựa trên supply/demand theo geohash, cache Redis.

### R9: PostGIS
Lưu trữ lịch sử tuyến đường, truy vấn không gian ST_DWithin.

### R10: OSRM Integration
Khoảng cách và thời gian thực từ OSRM, fallback Haversine.

### R11: Payment & Wallet
Ví điện tử, trừ hoa hồng, thanh toán.

### R12: Outbox Pattern + RabbitMQ
Event-driven, at-least-once delivery, idempotent consumer, DLQ.

### R13: Internal API Security
X-Internal-Key header, timeout 500ms-2s, không retry vô hạn.

### R14: CI/CD
GitHub Actions build, test, deploy lên VPS.

### R15: Load Testing
100 tài xế ảo gửi GPS, đo độ trễ end-to-end.

### R16: Documentation & Demo
Báo cáo kỹ thuật, kịch bản demo 5 phút.

---

*File này chứa đề bài gốc. Xem README.md cho trạng thái triển khai thực tế.*