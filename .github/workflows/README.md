# GitHub Actions CI Pipeline

## Tổng quan

Pipeline CI tự động chạy khi push hoặc tạo PR vào nhánh `main`, `master`, hoặc `develop`.

## Jobs

### 1. Test Job
- **Runner**: ubuntu-latest
- **JDK**: 21 (Temurin distribution)
- **Cache**: Maven dependencies
- **Thực hiện**: `mvn -B verify`
- **Output**: Upload surefire reports khi test failed
- **Thời gian**: ~5-8 phút

```yaml
steps:
  - Checkout code
  - Setup JDK 21 (temurin) với Maven cache
  - Run: mvn -B verify
  - Upload test reports nếu failed
```

### 2. Contracts Job
- **Runner**: ubuntu-latest
- **Shell**: PowerShell Core (pwsh)
- **Thực hiện**: Kiểm tra đồng bộ contract files
- **Thời gian**: ~30 giây

```yaml
steps:
  - Checkout code
  - Run PowerShell script check contracts
```

Kiểm tra:
- Tất cả contract trong `docs/contracts/manifest.json` đã được copy đúng vào từng service
- Nội dung copy phải khớp byte-to-byte với source (case-sensitive)
- Không có file contract nào chưa được đăng ký trong manifest
- JSON files phải valid

### 3. Images Job
- **Runner**: ubuntu-latest
- **Strategy**: Matrix build cho 7 modules
- **Thực hiện**: Build Docker images với cache
- **Push**: Không push (chỉ verify build)
- **Thời gian**: ~2-3 phút mỗi module (song song)

```yaml
matrix:
  module:
    - user-service
    - location-service
    - dispatch-service
    - pricing-service
    - payment-service
    - ws-gateway
    - api-gateway

steps:
  - Checkout code
  - Setup Docker Buildx
  - Build image với ARG MODULE
  - Cache layers với GitHub Actions cache
```

**Tags** (không push):
- `ghcr.io/$OWNER/ride-hailing-$MODULE:$SHA`
- `ghcr.io/$OWNER/ride-hailing-$MODULE:latest`

## Tổng thời gian

- **Test**: ~5-8 phút
- **Contracts**: ~30 giây
- **Images**: ~2-3 phút (song song)
- **Tổng**: ~8-10 phút (dưới 15 phút)

## Khi nào pipeline failed

### Test Job Failed
```bash
# Kiểm tra test reports
gh run view <run-id>
gh run download <run-id> -n surefire-reports

# Chạy test local
mvn verify
```

### Contracts Job Failed
```bash
# Kiểm tra contracts
pwsh scripts/check-contracts.ps1

# Fix: sync contracts
cp docs/contracts/http/user-get-user.response.json user-service/src/test/resources/contracts/http/
```

### Images Job Failed
```bash
# Build local để debug
docker build --build-arg MODULE=user-service -t test .

# Kiểm tra Dockerfile và module structure
ls -la user-service/target/
```

## Secrets (không có trong repo hiện tại)

Pipeline hiện tại **không dùng secrets**. Nếu cần push images hoặc deploy, thêm:

```yaml
# Push to GHCR
- name: Login to GHCR
  uses: docker/login-action@v3
  with:
    registry: ghcr.io
    username: ${{ github.repository_owner }}
    password: ${{ secrets.GITHUB_TOKEN }}

- name: Build and Push
  uses: docker/build-push-action@v5
  with:
    push: true
    # ... other configs
```

```yaml
# Deploy to VPS (optional job)
deploy:
  needs: [test, images]
  if: github.ref == 'refs/heads/main'
  runs-on: ubuntu-latest
  steps:
    - name: Deploy to VPS
      env:
        VPS_HOST: ${{ secrets.VPS_HOST }}
        VPS_USER: ${{ secrets.VPS_USER }}
        VPS_SSH_KEY: ${{ secrets.VPS_SSH_KEY }}
      run: |
        # SSH và deploy scripts
```

## Test Pipeline

### 1. Push vào nhánh thử
```bash
git checkout -b test-ci
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions pipeline"
git push origin test-ci
```

### 2. Kiểm tra trên GitHub
- Vào tab **Actions**
- Xem workflow run
- Kiểm tra logs của 3 jobs

### 3. Test failure case
```bash
# Làm fail một test
echo "// @Test public void shouldFail() { fail(); }" >> user-service/src/test/java/com/ridehailing/userservice/FailTest.java
git add .
git commit -m "test: intentional failure"
git push origin test-ci
```

→ Job `test` phải đỏ và upload surefire-reports

### 4. Test contract mismatch
```bash
# Sửa một contract copy không đúng
echo "invalid" >> user-service/src/test/resources/contracts/http/user-get-user.response.json
git add .
git commit -m "test: contract mismatch"
git push origin test-ci
```

→ Job `contracts` phải đỏ với message "MISMATCH: ..."

## Badge Status

Thêm vào README.md:
```markdown
![CI](https://github.com/YOUR_ORG/ride-hailing/actions/workflows/ci.yml/badge.svg)
```

## Tối ưu hóa

### Maven Cache
- GitHub Actions cache Maven dependencies tự động
- Cache key dựa vào `pom.xml` checksum
- Hit rate cao khi dependencies ổn định

### Docker Cache
- Dùng GitHub Actions cache (type=gha)
- Scope riêng cho mỗi module
- Mode=max cache tất cả layers

### Matrix Strategy
- 7 modules build song song
- Mỗi module độc lập → không ảnh hưởng nhau
- Có thể tăng thêm matrix (arch: amd64, arm64) nếu cần

## Troubleshooting

### Job bị skip
- Kiểm tra branch name trong `on.push.branches`
- Kiểm tra `needs:` dependency

### Timeout
- Default timeout: 6 hours
- Có thể set `timeout-minutes: 30` nếu cần

### Out of disk space
- Clean cache: `docker system prune -af`
- GitHub runners có ~14GB available space

### Maven download slow
- Dùng Maven mirror trong `settings.xml` (optional)
- Cache đã enable nên lần sau nhanh hơn
