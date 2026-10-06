# VPS Deployment Guide

Hướng dẫn triển khai Ride-Hailing system lên VPS Ubuntu 22.04 (4 vCPU / 8GB RAM).

## Yêu cầu

- VPS Ubuntu 22.04 LTS
- Domain đã trỏ DNS về IP VPS
- SSH key pair
- Root hoặc sudo access

---

## 1. Thiết lập máy chủ

### 1.1. Tạo user deploy

```bash
# Tạo user deploy với quyền sudo
adduser deploy
usermod -aG sudo deploy

# Copy SSH key từ root sang deploy (nếu đang dùng root)
mkdir -p /home/deploy/.ssh
cp /root/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
```

Đăng nhập lại bằng user deploy và tiếp tục các bước sau.

### 1.2. Cấu hình SSH bảo mật

```bash
# Backup cấu hình SSH gốc
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.backup

# Tắt SSH login bằng password và root login
sudo sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/#PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config
sudo sed -i 's/PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config

# Khởi động lại SSH để áp dụng
sudo systemctl restart sshd
```

### 1.3. Cấu hình tường lửa UFW

```bash
# Cho phép SSH trước khi enable ufw
sudo ufw allow 22/tcp

# Cho phép HTTP và HTTPS
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# Bật UFW
sudo ufw --force enable

# Kiểm tra trạng thái
sudo ufw status verbose
```

**LƯU Ý:** Docker publish port với `0.0.0.0` sẽ bỏ qua UFW. Trong setup này, chỉ publish `127.0.0.1:8000` và `127.0.0.1:8001` nên an toàn.

### 1.4. Tạo swap 2GB

```bash
# Tạo file swap 2GB
sudo fallocate -l 2G /swapfile

# Phân quyền chỉ root đọc/ghi
sudo chmod 600 /swapfile

# Định dạng làm swap
sudo mkswap /swapfile

# Kích hoạt swap
sudo swapon /swapfile

# Thêm vào /etc/fstab để tự động mount khi reboot
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Kiểm tra
sudo swapon --show
free -h
```

### 1.5. Cài đặt Docker

```bash
# Cập nhật package index
sudo apt update

# Cài đặt dependencies
sudo apt install -y ca-certificates curl gnupg lsb-release

# Thêm Docker GPG key
sudo mkdir -m 0755 -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg

# Thêm Docker repository
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Cài Docker Engine và Compose plugin
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Thêm user deploy vào group docker để chạy không cần sudo
sudo usermod -aG docker deploy

# Đăng xuất và đăng nhập lại để áp dụng group membership
# Hoặc chạy: newgrp docker

# Kiểm tra
docker --version
docker compose version
```

### 1.6. Cài đặt Nginx

```bash
# Cài Nginx
sudo apt install -y nginx

# Kiểm tra trạng thái
sudo systemctl status nginx

# Nginx tự động start và enable
```

### 1.7. Cài đặt Certbot

```bash
# Cài Certbot và plugin Nginx
sudo apt install -y certbot python3-certbot-nginx

# Kiểm tra
certbot --version
```

---

## 2. Cấu hình DNS

### 2.1. Tạo bản ghi A

Truy cập DNS manager của nhà cung cấp domain và tạo:

```
Type: A
Host: ride-api
Value: <IP_VPS>
TTL: 300 (hoặc Auto)
```

### 2.2. Kiểm tra DNS

```bash
# Từ máy local hoặc VPS
nslookup ride-api.yourdomain.com

# Hoặc dùng dig
dig ride-api.yourdomain.com +short

# Kết quả phải trả về IP VPS
```

**LƯU Ý:** DNS có thể mất 5-60 phút để lan truyền. Đợi trước khi chạy Certbot.

---

## 3. Cấu hình Nginx

### 3.1. Tạo file cấu hình

```bash
sudo nano /etc/nginx/sites-available/ride-api
```

Nội dung:

```nginx
# WebSocket upgrade mapping
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

# Upstream cho api-gateway (single instance)
upstream api_backend {
    server 127.0.0.1:8000;
}

# Upstream cho ws-gateway (2 replicas)
upstream ws_backend {
    server 127.0.0.1:8001;
    server 127.0.0.1:8011;
}

server {
    listen 80;
    server_name ride-api.yourdomain.com;

    # Client upload size limit
    client_max_body_size 64k;

    # Không log query string (có thể chứa token)
    log_format no_query '$remote_addr - $remote_user [$time_local] '
                        '"$request_method $uri $server_protocol" '
                        '$status $body_bytes_sent "$http_referer" '
                        '"$http_user_agent"';
    access_log /var/log/nginx/ride-api.access.log no_query;
    error_log /var/log/nginx/ride-api.error.log;

    # API routes
    location /api/v1/ {
        proxy_pass http://api_backend;
        proxy_http_version 1.1;
        
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # WebSocket routes
    location /ws {
        proxy_pass http://ws_backend;
        proxy_http_version 1.1;
        
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # WebSocket timeout: 1 hour
        proxy_connect_timeout 60s;
        proxy_send_timeout 3600s;
        proxy_read_timeout 3600s;
    }

    # Block /internal/ endpoints (never proxy)
    location /internal/ {
        return 404;
    }

    # Health check
    location /health {
        access_log off;
        return 200 "OK\n";
        add_header Content-Type text/plain;
    }
}
```

### 3.2. Kích hoạt cấu hình

```bash
# Tạo symbolic link
sudo ln -s /etc/nginx/sites-available/ride-api /etc/nginx/sites-enabled/

# Kiểm tra cú pháp
sudo nginx -t

# Reload Nginx
sudo systemctl reload nginx
```

---

## 4. Cấu hình SSL với Certbot

### 4.1. Tạo chứng chỉ SSL

```bash
# Chạy Certbot với plugin Nginx
sudo certbot --nginx -d ride-api.yourdomain.com

# Nhập email và đồng ý ToS
# Chọn redirect HTTP -> HTTPS (option 2)
```

Certbot tự động:
- Tạo chứng chỉ Let's Encrypt
- Cập nhật cấu hình Nginx
- Thiết lập redirect HTTP → HTTPS

### 4.2. Kiểm tra auto-renewal

```bash
# Test renewal (dry-run, không thật sự renew)
sudo certbot renew --dry-run

# Kiểm tra systemd timer
sudo systemctl status certbot.timer
```

Certbot tự động gia hạn chứng chỉ trước khi hết hạn.

---

## 5. Cấu hình biến môi trường

### 5.1. Tạo script gen-env.sh

```bash
cd ~
nano gen-env.sh
```

Nội dung:

```bash
#!/bin/bash
# Generate production .env with strong passwords

set -euo pipefail

DOMAIN="${1:-ride-api.yourdomain.com}"
OUTPUT_FILE=".env"

# Generate random password
gen_pass() {
    openssl rand -base64 32 | tr -d "=+/" | cut -c1-32
}

cat > "$OUTPUT_FILE" <<EOF
# Production Environment Variables
# Generated: $(date)

# Domain
DOMAIN=$DOMAIN

# Database passwords (IMPORTANT: Change these from default)
POSTGRES_PASSWORD=$(gen_pass)
USER_DB_PASSWORD=$(gen_pass)
LOCATION_DB_PASSWORD=$(gen_pass)
DISPATCH_DB_PASSWORD=$(gen_pass)
PAYMENT_DB_PASSWORD=$(gen_pass)

# Redis password
REDIS_PASSWORD=$(gen_pass)

# JWT secret (min 256 bits)
JWT_SECRET=$(gen_pass)

# Internal service key
INTERNAL_KEY=$(gen_pass)

# Port bindings (only localhost)
API_GATEWAY_PORT=127.0.0.1:8000
WS_GATEWAY_PORT_1=127.0.0.1:8001
WS_GATEWAY_PORT_2=127.0.0.1:8011

# Service URLs
USER_SERVICE_URL=http://user-service:8081
LOCATION_SERVICE_URL=http://location-service:8082
DISPATCH_SERVICE_URL=http://dispatch-service:8083
PRICING_SERVICE_URL=http://pricing-service:8084
PAYMENT_SERVICE_URL=http://payment-service:8085
WS_GATEWAY_URL=http://ws-gateway:8001

# Replica configuration
WS_GATEWAY_REPLICAS=2
EOF

chmod 600 "$OUTPUT_FILE"
echo "✓ .env generated: $OUTPUT_FILE"
echo "⚠ Passwords only apply to NEW volumes. For existing volumes, use ALTER USER."
```

### 5.2. Tạo file .env

```bash
# Thêm quyền thực thi
chmod +x gen-env.sh

# Chạy script
./gen-env.sh ride-api.yourdomain.com

# Kiểm tra
cat .env
```

**LƯU Ý QUAN TRỌNG:** Mật khẩu Postgres chỉ áp dụng khi khởi tạo volume lần đầu. Nếu volume đã tồn tại, cần ALTER USER thủ công.

---

## 6. Deployment

### 6.1. Clone repository

```bash
cd ~
git clone <your-repo-url> ride-hailing
cd ride-hailing

# Copy .env vào thư mục project
cp ~/.env .env

# Kiểm tra
ls -la .env
```

### 6.2. Tạo script deploy.sh

```bash
nano deploy.sh
```

Nội dung:

```bash
#!/bin/bash
# Production deployment script

set -euo pipefail

COMPOSE_FILE="docker-compose.yml"
HEALTH_TIMEOUT=120
LOG_LINES=50

echo "=========================================="
echo "Starting deployment..."
echo "=========================================="

# Pull latest code
echo "→ Pulling latest code..."
git pull origin main

# Build services
echo "→ Building services..."
docker compose --profile apps build

# Deploy ws-gateway replicas one by one to avoid mass WebSocket disconnect
echo "→ Deploying ws-gateway replica 1..."
docker compose up -d ws-gateway-1
sleep 5

echo "→ Deploying ws-gateway replica 2..."
docker compose up -d ws-gateway-2
sleep 5

# Deploy other services
echo "→ Deploying other services..."
docker compose --profile apps up -d

# Wait for health checks
echo "→ Waiting for services to be healthy..."
start_time=$(date +%s)

while true; do
    unhealthy=$(docker compose ps --format json | jq -r 'select(.Health != "" and .Health != "healthy") | .Service' 2>/dev/null || true)
    
    if [ -z "$unhealthy" ]; then
        echo "✓ All services healthy"
        break
    fi
    
    elapsed=$(($(date +%s) - start_time))
    if [ $elapsed -gt $HEALTH_TIMEOUT ]; then
        echo "✗ Health check timeout after ${HEALTH_TIMEOUT}s"
        echo "Unhealthy services: $unhealthy"
        echo
        echo "Last $LOG_LINES lines of logs:"
        echo "$unhealthy" | xargs -I {} docker compose logs --tail=$LOG_LINES {}
        exit 1
    fi
    
    echo "  Waiting for: $unhealthy"
    sleep 5
done

# Show status
echo
echo "=========================================="
echo "Deployment complete"
echo "=========================================="
docker compose ps

echo
echo "Service URLs:"
echo "  API Gateway: https://$DOMAIN/api/v1/"
echo "  WebSocket: wss://$DOMAIN/ws"
echo
echo "Check logs: docker compose logs -f <service-name>"
```

### 6.3. Deploy

```bash
# Thêm quyền thực thi
chmod +x deploy.sh

# Chạy deployment
./deploy.sh
```

**LƯU Ý về WS rolling update:**
- ws-gateway-1 deploy trước, đợi 5s
- ws-gateway-2 deploy sau
- Client WebSocket tự reconnect sang replica còn lại
- Giảm số lượng disconnect đồng loạt

---

## 7. Backup tự động

### 7.1. Tạo script backup

```bash
mkdir -p ~/backups
nano ~/backups/backup-databases.sh
```

Nội dung:

```bash
#!/bin/bash
# Backup all PostgreSQL databases

set -euo pipefail

BACKUP_DIR="$HOME/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RETENTION_DAYS=7

# Database list
DATABASES=("user" "location" "dispatch" "payment")
CONTAINER="postgres"

echo "Starting backup at $(date)"

for DB in "${DATABASES[@]}"; do
    BACKUP_FILE="$BACKUP_DIR/${DB}_${TIMESTAMP}.sql.gz"
    
    echo "→ Backing up database: $DB"
    docker exec -t $CONTAINER pg_dump -U ${DB}_app $DB | gzip > "$BACKUP_FILE"
    
    if [ -f "$BACKUP_FILE" ]; then
        SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
        echo "  ✓ Backup complete: $BACKUP_FILE ($SIZE)"
    else
        echo "  ✗ Backup failed: $DB"
    fi
done

# Clean up old backups (keep last 7 days)
echo "→ Cleaning up old backups (retention: $RETENTION_DAYS days)"
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete

echo "Backup completed at $(date)"
```

```bash
chmod +x ~/backups/backup-databases.sh
```

### 7.2. Cấu hình cron

```bash
# Mở crontab
crontab -e

# Thêm dòng này (chạy hàng ngày lúc 2:00 AM)
0 2 * * * $HOME/backups/backup-databases.sh >> $HOME/backups/backup.log 2>&1
```

### 7.3. Script restore thử nghiệm

```bash
nano ~/backups/test-restore.sh
```

Nội dung:

```bash
#!/bin/bash
# Test restore to temporary database

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <backup_file.sql.gz>"
    exit 1
fi

BACKUP_FILE="$1"
CONTAINER="postgres"
TEST_DB="test_restore_$(date +%s)"

echo "→ Creating test database: $TEST_DB"
docker exec -t $CONTAINER psql -U postgres -c "CREATE DATABASE $TEST_DB;"

echo "→ Restoring from: $BACKUP_FILE"
gunzip -c "$BACKUP_FILE" | docker exec -i $CONTAINER psql -U postgres -d $TEST_DB

echo "→ Verifying tables"
docker exec -t $CONTAINER psql -U postgres -d $TEST_DB -c "\dt"

echo "→ Cleaning up test database"
docker exec -t $CONTAINER psql -U postgres -c "DROP DATABASE $TEST_DB;"

echo "✓ Restore test successful"
```

```bash
chmod +x ~/backups/test-restore.sh

# Test với backup mới nhất
./backups/test-restore.sh ~/backups/user_$(ls -t ~/backups/user_*.sql.gz | head -1 | xargs basename)
```

---

## 8. Smoke Testing

### 8.1. Health check

```bash
# API health
curl https://ride-api.yourdomain.com/api/v1/health

# Kết quả: HTTP 200 OK
```

### 8.2. WebSocket test

```bash
# Cài wscat nếu chưa có
npm install -g wscat

# Test WebSocket connection
wscat -c wss://ride-api.yourdomain.com/ws

# Gửi auth message
{"type":"auth","token":"<your-token>"}

# Ctrl+C để thoát
```

### 8.3. Register và Login test

```bash
# Register
curl -X POST https://ride-api.yourdomain.com/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "+84901234567",
    "password": "testpass123",
    "name": "Test User",
    "role": "CUSTOMER"
  }'

# Login
curl -X POST https://ride-api.yourdomain.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "+84901234567",
    "password": "testpass123"
  }'

# Kết quả: {"userId": ..., "token": "..."}
```

### 8.4. Load test từ máy khác

```bash
# Từ máy local (không phải VPS)
cd tools/loadtest

# Cài dependencies
pip install -r requirements.txt

# Update URL trong scripts (hoặc dùng --url flag)
# API_BASE = "https://ride-api.yourdomain.com"

# Prepare drivers
python prepare.py 100

# Run load test
python drivers.py --drivers 100 --url wss://ride-api.yourdomain.com/ws --duration 300

# Measure latency (terminal khác)
python latency.py --url wss://ride-api.yourdomain.com/ws --duration 300
```

---

## 9. Common Issues & Diagnostics

### Issue 1: DNS chưa lan truyền

**Triệu chứng:** `nslookup` không trả về IP VPS

**Chẩn đoán:**
```bash
# Kiểm tra DNS
nslookup ride-api.yourdomain.com
dig ride-api.yourdomain.com +short

# Kiểm tra từ DNS public
nslookup ride-api.yourdomain.com 8.8.8.8
```

**Giải pháp:**
- Đợi 5-60 phút để DNS lan truyền
- Kiểm tra cấu hình DNS tại nhà cung cấp domain
- Xóa DNS cache: `sudo systemd-resolve --flush-caches`

---

### Issue 2: 502 Bad Gateway - Container chưa healthy

**Triệu chứng:** Nginx trả về 502 khi truy cập API

**Chẩn đoán:**
```bash
# Kiểm tra trạng thái containers
docker compose ps

# Kiểm tra health check
docker compose ps --format json | jq -r '.[] | "\(.Service): \(.Health)"'

# Xem logs
docker compose logs api-gateway
docker compose logs ws-gateway-1
```

**Giải pháp:**
- Đợi services khởi động xong (health check pass)
- Kiểm tra logs để tìm lỗi khởi động
- Verify `.env` có đầy đủ biến môi trường

---

### Issue 3: WebSocket đứt sau 60 giây

**Triệu chứng:** WebSocket connection bị đóng sau 1 phút

**Chẩn đoán:**
```bash
# Kiểm tra Nginx timeout config
sudo nginx -T | grep -i timeout

# Kiểm tra ws-gateway logs
docker compose logs ws-gateway-1 | grep -i timeout
```

**Giải pháp:**
- Kiểm tra `proxy_read_timeout` và `proxy_send_timeout` trong Nginx config (phải là 3600s)
- Reload Nginx: `sudo systemctl reload nginx`
- Implement WebSocket ping/pong trong client

---

### Issue 4: Chứng chỉ SSL không tự động gia hạn

**Triệu chứng:** Chứng chỉ hết hạn sau 90 ngày

**Chẩn đoán:**
```bash
# Kiểm tra certbot timer
sudo systemctl status certbot.timer

# Kiểm tra certbot service
sudo systemctl status certbot.service

# Test renewal
sudo certbot renew --dry-run

# Xem log renewal
sudo journalctl -u certbot.service
```

**Giải pháp:**
- Enable certbot timer: `sudo systemctl enable certbot.timer`
- Kiểm tra Nginx config không block `/.well-known/acme-challenge/`
- Chạy manual renewal: `sudo certbot renew --force-renewal`

---

### Issue 5: UFW bị Docker bypass

**Triệu chứng:** Services accessible từ internet dù UFW deny

**Chẩn đoán:**
```bash
# Kiểm tra UFW rules
sudo ufw status verbose

# Kiểm tra Docker port bindings
docker compose ps --format "table {{.Service}}\t{{.Ports}}"

# Kiểm tra listening ports
sudo netstat -tulpn | grep LISTEN
```

**Giải pháp:**
- Đảm bảo docker-compose.yml chỉ bind `127.0.0.1:<port>`
- KHÔNG bind `0.0.0.0:<port>` (sẽ bypass UFW)
- Config Docker để respect UFW (phức tạp, không khuyến khích):
  ```bash
  sudo nano /etc/docker/daemon.json
  # Add: {"iptables": false}
  sudo systemctl restart docker
  ```

**LƯU Ý:** Setup hiện tại đã bind đúng `127.0.0.1` nên không gặp vấn đề này.

---

## 10. Monitoring & Maintenance

### Kiểm tra logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f api-gateway

# Last 100 lines
docker compose logs --tail=100 ws-gateway-1

# Nginx logs
sudo tail -f /var/log/nginx/ride-api.access.log
sudo tail -f /var/log/nginx/ride-api.error.log
```

### Kiểm tra resource usage

```bash
# Docker stats
docker stats

# System resources
htop
free -h
df -h
```

### Restart services

```bash
# Restart specific service
docker compose restart api-gateway

# Restart all services
docker compose restart

# Rebuild and restart
docker compose up -d --build api-gateway
```

### Update deployment

```bash
# Simple update
./deploy.sh

# Full rebuild
docker compose --profile apps build --no-cache
./deploy.sh
```

---

## Security Checklist

- [ ] SSH chỉ dùng key, tắt password login
- [ ] Root login disabled
- [ ] UFW enabled với chỉ 22/80/443
- [ ] .env file có chmod 600
- [ ] Tất cả mật khẩu đã thay đổi từ mặc định
- [ ] SSL certificate active và auto-renewal enabled
- [ ] /internal/ routes trả về 404
- [ ] Docker ports chỉ bind 127.0.0.1
- [ ] Backup cronjob đang chạy
- [ ] Swap enabled
- [ ] Regular security updates: `sudo apt update && sudo apt upgrade`

---

## Useful Commands

```bash
# Check service status
docker compose ps
systemctl status nginx
systemctl status docker

# View all logs
docker compose logs --tail=1000

# Database access
docker exec -it postgres psql -U user_app -d user

# Redis access
docker exec -it redis redis-cli -a <REDIS_PASSWORD>

# Cleanup
docker system prune -a  # Remove unused images/containers
docker volume prune     # Remove unused volumes (CAREFUL!)

# Check disk space
df -h
docker system df
```

---

**Hoàn tất!** Hệ thống đã sẵn sàng phục vụ production.
