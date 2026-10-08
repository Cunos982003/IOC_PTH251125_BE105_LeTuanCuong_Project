# Bộ prompt hoàn thiện đồ án (F1, F3, F4, F5, F6, F7)

Dùng trong Claude Code, thư mục gốc repo. Mỗi prompt bắt đầu bằng **Quy ước chung** bên dưới, rồi tới nội dung riêng.
Mỗi prompt: một nhánh hoặc một commit nhỏ, chạy `mvn -pl <service> verify` khi sửa mã, deploy và kiểm tra health xong mới sang prompt kế.

| Prompt | Việc | Đáp ứng | Ước lượng | Ưu tiên |
|---|---|---|---|---|
| F1 | App khách có bản đồ | R1, R4 | 2–3 giờ | Cao nhất |
| F5 | Số liệu và bằng chứng | R1, R2, R5, R6 | 1–2 giờ | Cao |
| F7 | Báo cáo và kịch bản demo | R16 | 1 giờ | Cao (làm sau F5) |
| F6 | CI tự deploy | R14 | 1–2 giờ | Trung bình |
| F3 | PostGIS | R9 | 45 phút | Thấp |
| F4 | OSRM, bảng giá Redis, metrics theo giờ | R10, R8 | 2–3 giờ | Thấp |

Thứ tự đề xuất: F1 → F5 → F7 → F6 → F3 → F4. Thiếu giờ thì bỏ F3, F4 và ghi rõ vào báo cáo.

---

## Quy ước chung (dán đầu mỗi prompt)

```
Bối cảnh: repo Cunos982003/IOC_PTH251125_BE105_LeTuanCuong_Project, nhánh master, 7 service độc lập (không có
module common), sự kiện qua RabbitMQ (exchange `events`), demo tại https://ridehailing.duckdns.org
(VPS Ubuntu, Docker Compose, Nginx + Certbot). Môi trường: Windows + PowerShell, Java 21, Docker Desktop;
mọi lệnh phải chạy được trong PowerShell.

Ràng buộc:
- KHÔNG sửa lõi đã có test xanh: TripStatus/state machine, khóa tài xế disp:lock trong MatchingService,
  SettlementService/ví, outbox và RabbitConfig.
- Đổi hợp đồng giữa service thì sửa docs/contracts/ trước, rồi các bản copy, rồi chạy scripts/check-contracts.ps1.
- Không đưa bí mật vào repo. Không in giá trị mật khẩu hay token.
- Không thêm tính năng ngoài danh sách. Nếu mơ hồ, hỏi đúng 1 câu.
- Số liệu phải đo thật; không đo được thì ghi [CẦN BỔ SUNG], không bịa.
- Cuối mỗi lần làm: liệt kê file đã tạo/sửa/xóa và các lệnh PowerShell để tôi kiểm tra.
```

---

## F1. App khách có bản đồ (R1, R4)

```
Tạo web/index.html (một file, Leaflet qua CDN, không framework, không build tool, tối đa ~400 dòng) làm ứng dụng khách.

1) Đăng ký/đăng nhập (role CUSTOMER) qua /api/v1/auth/register và /login. Token giữ trong biến JS,
   KHÔNG dùng localStorage hay sessionStorage.
2) WebSocket: địa chỉ tự suy ra từ trang: (location.protocol === 'https:' ? 'wss' : 'ws') + '://' + location.host + '/ws/customer'.
   Gửi {"t":"auth","token":...} ngay khi mở. Mất kết nối thì reconnect với exponential backoff (1s→30s) và jitter ±30%,
   gửi lại auth, hiện trạng thái kết nối trên màn hình.
3) Bản đồ: click lần 1 chọn điểm đón, lần 2 chọn điểm đến (marker khác màu, có nút xóa chọn lại).
   Nút "Báo giá" gọi POST /api/v1/quote và hiện fare, surge. Nút "Đặt xe" gọi POST /api/v1/rides với header
   Idempotency-Key = crypto.randomUUID() (giữ nguyên key nếu bấm lại khi chuyến đang chờ). Nút "Hủy" gọi
   POST /api/v1/rides/{id}/cancel.
4) Trạng thái chuyến: hiển thị timeline CREATED → MATCHING → ACCEPTED → PICKING_UP → IN_TRIP → COMPLETED từ
   message trip_update; xử lý riêng CANCELLED và NO_DRIVER_FOUND. Hiện số dư ví (GET /api/v1/wallet) và làm mới
   khi chuyến COMPLETED.
5) Marker tài xế: tạo một lần, cập nhật bằng setLatLng khi nhận driver_location (không tạo lại marker mỗi tin);
   ẩn khi chuyến kết thúc.
6) Khung đo độ trễ: p50 và p95 của 50 tin driver_location gần nhất, tính bằng (Date.now()/1000 - sent_at).
   Ghi chú trên màn hình rằng đây là ước lượng vì đồng hồ trình duyệt và máy chạy tài xế ảo có thể lệch.
7) Escape mọi chuỗi từ server trước khi chèn vào DOM (chống XSS); dùng textContent thay vì innerHTML.

Triển khai:
- Sửa docs/nginx-ride-api.conf: location / phục vụ thư mục /var/www/ride
  (root, index index.html, try_files $uri $uri/ =404). Giữ nguyên /internal (404), /api/v1/ và /ws/.
- File Nginx trên VPS đã bị certbot chỉnh, nên KHÔNG ghi đè cả file. Cho tôi đoạn thay thế chính xác cho khối
  `location /` trong /etc/nginx/sites-available/ride-api (cả hai khối server 80 và 443 nếu cần) kèm lệnh
  `sudo nginx -t && sudo systemctl reload nginx`.
- Đọc scripts/deploy.sh hiện có rồi thêm bước chép web/ vào /var/www/ride (rsync -a --delete, hoặc cp -r nếu
  VPS không có rsync). Cho tôi lệnh chạy một lần: sudo mkdir -p /var/www/ride && sudo chown deploy:deploy /var/www/ride.

Tiêu chí xong:
- Mở https://ridehailing.duckdns.org, đăng nhập, chọn hai điểm, báo giá, đặt xe.
- Khi tools/loadtest/drivers.py đang chạy: tài xế ảo nhận offer, trạng thái chuyến chạy tới COMPLETED,
  marker di chuyển mượt, p95 hiển thị.
- https://ridehailing.duckdns.org/internal/users/1 vẫn trả 404.
Bằng chứng cần chụp: ảnh màn hình có khóa HTTPS + marker + p95; video quay màn hình khoảng 60 giây.
```

---

## F3. PostGIS cho location-service (R9)

```
location-service: dùng PostGIS thật cho dữ liệu lịch sử. Redis GEO vẫn là nguồn truy vấn thời gian thực.

1) infra/postgres/01-create-databases.sh: sau khi tạo database `location`, chạy
   CREATE EXTENSION IF NOT EXISTS postgis trong database đó bằng superuser (role location_app không có quyền
   tạo extension). Script init chỉ chạy khi volume trống, nên cho tôi thêm lệnh PowerShell chạy một lần cho
   volume đã tồn tại (docker compose exec postgres psql -U postgres -d location -c "CREATE EXTENSION IF NOT EXISTS postgis;")
   và lệnh tương đương trên VPS (dùng -f compose.yaml).
2) Đọc migration hiện có của location-service. Thêm Flyway migration MỚI (không sửa migration cũ): nếu bảng
   location_history đã có thì thêm cột geom geography(Point,4326) GENERATED ALWAYS AS
   (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED; nếu chưa có thì tạo bảng đủ cột
   (id, driver_id, trip_id UUID, lat, lng, recorded_at, geom). Thêm index GiST trên geom và index (trip_id, recorded_at).
   Migration KHÔNG chạy CREATE EXTENSION (Flyway chạy bằng role location_app).
3) Thêm GET /internal/trips/{tripId}/route (X-Internal-Key) trả {distanceM, pointCount, points[{lat,lng,recordedAt}]}.
   distanceM tính bằng ST_Length của đường nối các điểm theo thứ tự thời gian (ST_MakeLine).
   Thêm vào docs/contracts và chạy check-contracts.
4) Test bằng Testcontainers image postgis/postgis:16-3.5 (asCompatibleSubstituteFor("postgres")); trong test,
   tạo extension bằng init script vì container chạy bằng superuser. Test: ghi 5 điểm cách nhau ~100 m dọc một đường
   thẳng rồi khẳng định distanceM ≈ 400 m (sai số 5%); truy vấn không gian ST_DWithin dùng được index GiST (EXPLAIN).
5) README và báo cáo chỉ ghi "PostGIS" sau khi các bước trên chạy thật.
Tiêu chí xong: mvn -pl location-service verify xanh; trên VPS gọi route của một chuyến thật trả distanceM hợp lý.
```

---

## F4. Pricing đúng đề: OSRM, bảng giá trong Redis, metrics theo giờ (R10, R8)

```
pricing-service: GIỮ NGUYÊN công thức surge và DemandService hiện có. Chỉ thêm ba phần sau.

1) Khoảng cách và thời gian từ OSRM (dữ liệu OpenStreetMap):
   - Biến môi trường OSRM_URL (mặc định https://router.project-osrm.org). Gọi
     GET {OSRM_URL}/route/v1/driving/{lng},{lat};{lng},{lat}?overview=false (KINH ĐỘ TRƯỚC, VĨ ĐỘ SAU).
   - Timeout 1 giây, cache Redis price:route:{hash tọa độ làm tròn 4 chữ số} trong 10 phút.
   - Lỗi hoặc timeout: fallback Haversine × 1.3 như hiện tại, trả thêm field source = OSRM hoặc HAVERSINE.
   - Máy chủ demo công cộng của OSRM chỉ phù hợp tải nhỏ: ghi chú điều này trong docs.
2) Bảng giá nạp vào Redis (HASH price:rules): khi khởi động, nếu chưa có thì ghi từ application.yml;
   PricingService đọc từ Redis (có cache ngắn 30 giây). Thêm PUT /internal/rules {BASE, PER_KM, PER_MIN, MIN_FARE}
   để đổi giá không cần deploy lại; validate giá trị dương và giới hạn hợp lý.
3) Metrics theo ô lưới và khung giờ: khi ghi demand, HINCRBY price:metrics:{cell}:{yyyyMMddHH} demand 1 và
   HSET supply (số tài xế rảnh dùng để tính surge) và surge_max; TTL 7 ngày. Thêm
   GET /internal/metrics?cell=&hours=24 trả chuỗi theo giờ. Demand là yêu cầu đặt xe, supply là tài xế rảnh.
4) compose.yaml: thêm OSRM_URL cho pricing-service (giá trị mặc định đặt trong compose). Cập nhật docs/contracts
   (quote có field source, endpoint rules và metrics) rồi chạy check-contracts.
Test: OSRM giả bằng WireMock (trả route đúng; timeout 2 giây thì fallback và source=HAVERSINE); PUT rules làm giá
đổi mà không restart; metrics: ghi 10 yêu cầu ở cùng ô trong cùng giờ thì demand=10. Test surge hiện có phải xanh nguyên vẹn.
Tiêu chí xong: mvn -pl pricing-service verify xanh; trên VPS /api/v1/quote trả source=OSRM với khoảng cách hợp lý.
```

---

## F5. Số liệu và bằng chứng (R1, R2, R5, R6)

```
Tạo tools/evidence/ (không phải module Maven; dùng Python 3.11 và PowerShell, phụ thuộc ghi trong requirements.txt).
Mọi script in bảng ra màn hình và xuất CSV vào tools/evidence/out/; cuối cùng sinh tools/evidence/out/summary.md
(ghi ngày giờ, máy chạy, cấu hình VPS nếu có). Tái sử dụng tools/loadtest/prepare.py và drivers.py, không viết lại.

1) bench_geo.py (R5): kết nối Redis (host, cổng, mật khẩu từ biến môi trường), nạp 10.000, 50.000 và 100.000
   tài xế ngẫu nhiên quanh TP.HCM vào khóa riêng bench:geo (TUYỆT ĐỐI không dùng loc:geo của hệ thống thật), rồi đo
   1000 lần GEOSEARCH bán kính 2 km (FROMLONLAT, BYRADIUS 2 km, ASC, COUNT 10). In p50/p95/p99 tính bằng ms ở
   MỖI quy mô. Báo hai số riêng: (a) đo tại Redis, (b) đo qua HTTP location-service /internal/drivers/nearby.
   Xóa khóa bench:geo khi xong. Nêu rõ nếu (b) lớn hơn 2 ms (dự kiến có, vì thêm HTTP).
2) failover_ws.ps1 (R6): cần hai replica ws-gateway (profile ha). Nối 20 client customer, rồi chạy
   docker compose -f compose.yaml stop ws-gateway, đo thời gian đến khi 100% client đã reconnect và nhận lại
   driver_location; sau đó bật lại. In bảng: số client, thời gian reconnect (p50/max), số tin mất. Nếu client trong
   script chưa có reconnect thì dùng bản có backoff của tools/loadtest.
3) match_accuracy.py (R2): đăng ký 6 tài xế, đặt ở 0.3 / 0.8 / 1.5 / 2.4 / 3.5 km tính từ điểm đón (dùng
   Haversine để dựng tọa độ), tài xế thứ sáu ở 0.1 km nhưng ngừng gửi vị trí quá 20 giây (phải bị bỏ qua).
   Khách đặt xe; cả 6 tài xế nối WebSocket và KHÔNG accept. Khẳng định: offer đầu tới tài xế 0.3 km; sau 15 giây
   offer kế tới tài xế 0.8 km; tài xế 0.1 km không nhận offer nào. In thứ tự offer kèm thời gian.
4) latency_e2e.py (R1): khách ảo đặt một chuyến, tài xế số 0 của drivers.py tự accept, khách nhận driver_location;
   độ trễ = now - sent_at. Mỗi mức tải 100 / 500 / 1000 tài xế chạy tối thiểu 5 phút, in p50/p95/p99/max và số mẫu,
   kèm kết quả `docker stats --no-stream` (CPU, RAM) từng container ngay lúc đo. Chạy cùng máy với drivers.py để
   dùng cùng đồng hồ; khác máy thì ghi cần đồng bộ NTP. Ghi rõ 2 hạn chế của cách đo.
5) README ngắn trong tools/evidence: lệnh chạy cho local và cho VPS (wss://ridehailing.duckdns.org).
Kết quả ghi dạng "thiết kế cho X, đo thực tế Y". Nếu một mức tải làm hệ thống quá tải, ghi lại mức đó
như một kết quả (không giấu). Không bịa số.
Tiêu chí xong: chạy bench_geo và latency_e2e (mức 100 tài xế) thành công; summary.md có số thật.
```

---

## F6. CI tự deploy (R14)

```
Hoàn thiện .github/workflows/ci.yml (nhánh mặc định là master):
- Giữ nguyên các job test, contracts và bước đổi owner sang chữ thường (ghcr yêu cầu tên viết thường).
- job images: push lên ghcr.io/${OWNER_LC}/ride-hailing-<module> CHỈ khi push vào master (pull request chỉ build,
  không push); tag :${{ github.sha }} và :latest; permissions: contents: read, packages: write ở cấp job;
  docker/login-action với GITHUB_TOKEN; giữ cache gha theo module.
- job deploy: needs [test, contracts, images]; chỉ chạy khi push vào master; concurrency group "deploy" với
  cancel-in-progress: false để không chạy hai deploy cùng lúc. Dùng secrets VPS_HOST, VPS_USER, VPS_SSH_KEY
  (key riêng cho deploy, KHÔNG dùng key cá nhân) và VPS_HOST_KEY (kết quả ssh-keyscan -t ed25519 <host>, ghi vào
  known_hosts, không tắt StrictHostKeyChecking). Chạy trên VPS:
  cd ~/ride-hailing && IMAGE_TAG=<sha> ./scripts/deploy.sh
  rồi gọi https://ridehailing.duckdns.org/api/v1/health và làm job đỏ nếu không trả UP trong 120 giây.
- Đọc scripts/deploy.sh hiện có: nói rõ nó chạy trên VPS hay trên máy dev, rồi sửa cho khớp cách gọi ở trên
  (nhận IMAGE_TAG, pull image từ GHCR, không build trên VPS, cập nhật lần lượt từng service, ws-gateway lần lượt từng
  replica). Ghi tag đang chạy vào ~/ride-hailing/.deployed-tag và cho phép rollback bằng ./scripts/deploy.sh <tag-cũ>.
- Liệt kê cho tôi việc làm tay một lần: tạo key deploy riêng và thêm vào authorized_keys của user deploy trên VPS,
  tạo ba secret trên GitHub (Settings → Secrets and variables → Actions), chạy docker login ghcr.io trên VPS bằng
  token chỉ có quyền read:packages.
Tiêu chí xong: push master → image lên GHCR → VPS cập nhật → /api/v1/health UP; làm một test đỏ thì job deploy
không chạy; rollback bằng một lệnh được thử thật một lần.
```

---

## F7. Báo cáo và kịch bản demo (R16)

```
Viết docs/BAO-CAO.md (tiếng Việt, văn phong kỹ thuật, ngắn gọn, khoảng 3–5 trang). Chỉ nêu điều có trong mã hoặc
trong kết quả đo; mục nào thiếu dữ liệu đánh dấu [CẦN BỔ SUNG].
1. Bài toán và mục tiêu (3–5 dòng).
2. Kiến trúc 7 service: sơ đồ mermaid khớp mã thật (RabbitMQ, Redis, PostgreSQL), bảng service (cổng, database,
   trách nhiệm). Sửa sơ đồ của đề: Payment chỉ nhận TripCompleted, không nhận yêu cầu đặt xe.
3. Dữ liệu và sự kiện: quyền sở hữu dữ liệu từng service, bảng routing key và queue, outbox + publisher confirm + DLQ.
4. State machine (mermaid) với các trạng thái thực tế trong TripStatus, kể cả CANCELLED và NO_DRIVER_FOUND.
5. Chống ghép trùng tài xế: ba lớp (khóa Redis disp:lock, UPDATE có điều kiện status + version, partial unique index)
   và test chứng minh (tên test, kết quả).
6. Bảng "đề yêu cầu / đã làm / khác biệt và lý do", dựa trên kết quả kiểm R1–R16 và các mục sau: RabbitMQ thay
   Kafka + Zookeeper; trạng thái Busy lưu ở location-service; đề ghi ngược supply/demand; Haversine hoặc OSRM; PostGIS
   (đã làm hay chưa); thanh toán thẻ giả lập; chỉ ws-gateway có 2 replica.
7. Kết quả đo: lấy từ tools/evidence/out/summary.md và các CSV, trình bày bảng p50/p95/p99 theo mức tải, benchmark
   GEOSEARCH, thời gian failover. Ghi "thiết kế cho X, đo thực tế Y". Không tuyên bố đạt quy mô hàng trăm nghìn GPS/giây.
8. Triển khai: VPS (cấu hình), Nginx, HTTPS, CI/CD; chèn ảnh trong docs/img/ (khóa HTTPS, wscat từ mạng ngoài,
   bản đồ có marker, trạng thái GitHub Actions); liệt kê tên ảnh còn thiếu.
9. Hạn chế và hướng mở rộng (một VPS, backend chỉ một instance nên có vài giây gián đoạn khi cập nhật, PostGIS/OSRM
   nếu chưa làm, shard theo thành phố, Kafka khi cần quy mô lớn).
Thêm docs/DEMO-SCRIPT.md: kịch bản demo 5 phút theo thứ tự và lệnh cụ thể (bật 100 tài xế ảo, đặt xe trên web,
tài xế ảo nhận chuyến, hoàn thành, xem ví, đo độ trễ, thử dừng một replica ws-gateway), cùng danh sách kiểm trước khi
quay video. Không bịa tính năng hoặc số liệu.
```
