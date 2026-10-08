# KỊCH BẢN DEMO 5 PHÚT

**Môi trường:** https://ridehailing.duckdns.org (VPS Ubuntu, Docker Compose, Nginx + Certbot)  
**Thời gian:** ~5 phút  
**Chuẩn bị:** Mở 2 tab trình duyệt (1 khách, 1 theo dõi), 1 terminal

---

## DANH SÁCH KIỂM TRƯỚC KHI DEMO

- [ ] VPS chạy: `docker compose -f compose.yaml --profile apps --profile ha up -d`
- [ ] 9 container healthy: `docker ps --format "table {{.Names}}\t{{.Status}}"`
- [ ] HTTPS OK: `curl -I https://ridehailing.duckdns.org/api/v1/health` → 200 UP
- [ ] Web app load: Mở https://ridehailing.duckdns.org → hiện bản đồ Leaflet
- [ ] 100 tài xế ảo: `python tools/loadtest/drivers.py --count 100` (chạy nền)
- [ ] WS Gateway 2 replica: `docker ps | grep ws-gateway` → 2 container

---

## KỊCH BẢN CHI TIẾT

### 1. Khách đăng ký & đăng nhập (30s)
**Tab 1 - Khách:**
1. Mở https://ridehailing.duckdns.org
2. Nhập email: `demo_customer@test.com`, mật khẩu: `password123`
3. Bấm **Đăng ký** → tự động đăng nhập
4. Kiểm tra: Sidebar hiện "Đã đăng nhập", "Ví điện tử: 500.000đ", "Kết nối WebSocket: Đã xác thực"

**Lệnh kiểm tra (terminal):**
```bash
curl -s -X POST https://ridehailing.duckdns.org/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo_customer@test.com","password":"password123"}' | jq .accessToken
```

---

### 2. Chọn điểm đón, điểm đến, báo giá, đặt xe (60s)
**Tab 1 - Khách:**
1. Click 1 lần trên bản đồ → marker xanh (điểm đón) ở Hà Nội (VD: 21.0285, 105.8542)
2. Click lần 2 → marker đỏ (điểm đến) cách ~1km
3. Bấm **Báo giá** → hiện cước cơ bản, surge x1.0, tổng ước tính ~34.000đ
4. Bấm **Đặt xe** → nút "Đặt xe" ẩn, nút "Hủy" hiện, timeline: CREATED → MATCHING

**Lệnh kiểm tra:**
```bash
# Lấy ride_id từ response đặt xe
curl -s -X POST https://ridehailing.duckdns.org/api/v1/rides \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pickup_lat":21.0285,"pickup_lng":105.8542,"dropoff_lat":21.0385,"dropoff_lng":105.8642}' | jq .
```

---

### 3. Tài xế ảo nhận chuyến, trạng thái chạy tới COMPLETED (90s)
**Tab 1 - Khách:** Quan sát timeline tự cập nhật:
- MATCHING → ACCEPTED (tài xế nhận)
- ACCEPTED → PICKING_UP (tài xế đến đón)
- PICKING_UP → IN_TRIP (bắt đầu chạy)
- IN_TRIP → COMPLETED (kết thúc)

**Marker tài xế:** Xanh lá di chuyển mượt theo lộ trình, popup "Tài xế"

**Tab 2 - Theo dõi (mở cùng URL, đăng nhập tài khoản khác hoặc ẩn danh):**
- Có thể thấy cùng một chuyến, marker tài xế đồng bộ

**Lệnh kiểm tra (terminal):**
```bash
# Xem trip status
curl -s https://ridehailing.duckdns.org/api/v1/rides/<RIDE_ID> \
  -H "Authorization: Bearer <TOKEN>" | jq .
```

---

### 4. Xem ví sau khi COMPLETED (15s)
**Tab 1 - Khách:**
1. Khi timeline hiện COMPLETED (màu xanh)
2. Sidebar "Ví điện tử" tự làm mới: số dư giảm 34.000đ (từ 500.000đ → 466.000đ)
3. Bấm **Làm mới** để xác nhận

**Lệnh kiểm tra:**
```bash
curl -s https://ridehailing.duckdns.org/api/v1/wallet \
  -H "Authorization: Bearer <TOKEN>" | jq .balance
```

---

### 5. Độ trễ p50/p95 (15s)
**Tab 1 - Khách:** Khung "Độ trễ (ước lượng)" hiển thị:
- p50: ~XX ms
- p95: ~XX ms
- Mẫu: 50 tin gần nhất
- Chú thích: "ước lượng do đồng hồ trình duyệt và server có thể lệch"

---

### 6. Thử dừng 1 replica ws-gateway (60s)
**Terminal:**
```bash
# Dừng replica 1 (port 8001)
docker compose -f compose.yaml stop ws-gateway
```

**Quan sát Tab 1:**
- WebSocket status: "Đang ngắt kết nối" → "Thử lại sau Xs (lần N)"
- Sau 5-15s: "Đã xác thực" (reconnect tới replica 2 port 8011 qua Nginx)
- Marker tài xế tiếp tục di chuyển, timeline tiếp tục cập nhật

**Bật lại:**
```bash
docker compose -f compose.yaml start ws-gateway
```

---

## LỆNH CHẠY TẤT XẾ TỰ ĐỘNG (CHO DEMO NHANH)

```bash
# 1. Khởi động toàn bộ (nếu chưa chạy)
docker compose -f compose.yaml --profile apps --profile ha up -d

# 2. Chạy 100 tài xế ảo nền
python tools/loadtest/drivers.py --count 100 &

# 3. Đợi 10s cho tài xế sẵn sàng
sleep 10

# 4. Tạo khách và đặt xe (cần jq, uuidgen)
TOKEN=$(curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo_customer@test.com","password":"password123"}' | jq -r .accessToken)

RIDE=$(curl -s -X POST http://localhost:8000/api/v1/rides \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pickup_lat":21.0285,"pickup_lng":105.8542,"dropoff_lat":21.0385,"dropoff_lng":105.8642}')

RIDE_ID=$(echo $RIDE | jq -r .tripId)
echo "Ride ID: $RIDE_ID"

# 5. Theo dõi trạng thái
for i in {1..30}; do
  curl -s http://localhost:8000/api/v1/rides/$RIDE_ID -H "Authorization: Bearer $TOKEN" | jq .status
  sleep 2
done

# 6. Xem ví sau khi xong
curl -s http://localhost:8000/api/v1/wallet -H "Authorization: Bearer $TOKEN" | jq .balance
```

---

## CHECKLIST SAU DEMO

- [ ] Tắt tài xế ảo: `pkill -f drivers.py`
- [ ] Dọn container test: `docker compose down` (giữ infra: postgres, redis, rabbitmq)
- [ ] Commit & push nếu có thay đổi demo

---

## LƯU Ý QUAN TRỌNG

1. **Không bịa số liệu** – Chỉ demo những gì đã chạy được thực tế
2. **Nếu WebSocket không reconnect** – Kiểm tra Nginx upstream có 2 server ws-gateway:8001 và ws-gateway-2:8011
3. **Nếu matching chậm** – Tăng số tài xế ảo hoặc giảm radius tìm kiếm
4. **HTTPS trên VPS** – Certbot tự gia hạn, không cần can thiệp
4. **Rollback** – Nếu deploy lỗi: `./scripts/deploy.sh <tag-cũ>` trên VPS