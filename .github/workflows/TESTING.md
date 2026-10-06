# CI Pipeline Testing Guide

## Quick Start

### 1. Commit và push workflow
```powershell
git checkout -b test-ci
git add .github/workflows/
git commit -m "ci: add GitHub Actions pipeline"
git push origin test-ci
```

→ Vào GitHub Actions tab để xem pipeline chạy

### 2. Tạo Pull Request (optional)
```powershell
# Trên GitHub, tạo PR từ test-ci → main
# Pipeline sẽ chạy tự động cho PR
```

## Test Scenarios

### Scenario 1: All Green (Happy Path)
```powershell
# Push code hiện tại
git checkout test-ci
git push origin test-ci
```

**Expected:**
- ✓ Job `test`: All tests pass (~5-8 min)
- ✓ Job `contracts`: All contracts match (~30s)
- ✓ Job `images`: All 7 images build successfully (~2-3 min parallel)
- ✓ Total time: <15 minutes

### Scenario 2: Test Failure
```powershell
# Tạo test failure
.\scripts\test-ci-failures.ps1 -Scenario test

# Commit và push
git add user-service/src/test/java/com/ridehailing/userservice/CiTestFailure.java
git commit -m "test: intentional failure for CI testing"
git push origin test-ci
```

**Expected:**
- ✗ Job `test`: Failed
- ✓ Upload artifact `surefire-reports` available
- ✓ Job `contracts`: Still runs (independent)
- ✓ Job `images`: Still runs (independent)

**Verify:**
```powershell
# Download reports từ GitHub Actions UI
# Hoặc dùng gh CLI:
gh run list --branch test-ci
gh run view <run-id>
gh run download <run-id> -n surefire-reports
```

### Scenario 3: Contract Mismatch
```powershell
# Tạo contract mismatch
.\scripts\test-ci-failures.ps1 -Scenario contract

# Commit và push
git add user-service/src/test/resources/contracts/http/user-get-user.response.json
git commit -m "test: contract mismatch for CI testing"
git push origin test-ci
```

**Expected:**
- ✗ Job `contracts`: Failed with "MISMATCH: ..." message
- ✓ Job `test`: Still runs
- ✓ Job `images`: Still runs

### Scenario 4: Cleanup
```powershell
# Cleanup test files
.\scripts\test-ci-failures.ps1 -Scenario cleanup

# Restore clean state
git restore .
git clean -fd
```

## Monitoring Pipeline

### Via GitHub UI
1. Vào repository → **Actions** tab
2. Click vào workflow run
3. Xem logs của từng job
4. Download artifacts nếu có

### Via gh CLI
```powershell
# List recent runs
gh run list --workflow=ci.yml --limit 5

# View specific run
gh run view <run-id>

# Watch live
gh run watch <run-id>

# Download artifacts
gh run download <run-id>
```

## Pipeline Structure

```
CI Workflow
├── test (ubuntu-latest, ~5-8 min)
│   ├── Checkout
│   ├── Setup JDK 21 (temurin) + Maven cache
│   ├── mvn -B verify
│   └── Upload surefire-reports (on failure)
│
├── contracts (ubuntu-latest, ~30s)
│   ├── Checkout
│   └── Check contracts via pwsh script
│
└── images (ubuntu-latest, matrix, ~2-3 min each)
    ├── user-service
    ├── location-service
    ├── dispatch-service
    ├── pricing-service
    ├── payment-service
    ├── ws-gateway
    └── api-gateway
        ├── Checkout
        ├── Setup Docker Buildx
        └── Build (no push, with GHA cache)
```

## Tiêu chí đạt

- [x] Pipeline xanh trên nhánh test-ci
- [x] Tổng thời gian <15 phút
- [x] Test failure → job `test` đỏ + upload surefire-reports
- [x] Contract mismatch → job `contracts` đỏ
- [x] Jobs chạy độc lập (1 job fail không block jobs khác)
- [x] Maven cache hoạt động (lần chạy thứ 2 nhanh hơn)
- [x] Docker cache hoạt động (GHA cache type)

## Troubleshooting

### Job bị skip
- Kiểm tra branch trigger: `on.push.branches`
- Workflow file phải ở `.github/workflows/ci.yml`

### Maven download chậm
- Lần đầu Maven tải dependencies (~3-4 min thêm)
- Lần sau cache hit, nhanh hơn nhiều
- Check cache hit/miss trong logs: "Cache restored from key"

### Docker build chậm
- Layer cache từ GitHub Actions (type=gha)
- Scope riêng cho mỗi module
- Lần sau build nhanh hơn do cache

### Test timeout
- Default timeout: 360 min (6h)
- Có thể set: `timeout-minutes: 30` trong job

### Out of space
- GitHub runners: ~14GB available
- Clean workspace sau mỗi run

## Advanced: Push Images (Optional)

Để push images lên GHCR khi tag version:

```yaml
# Thêm vào .github/workflows/ci.yml

jobs:
  images:
    # ... existing config ...
    steps:
      # ... existing steps ...

      - name: Login to GHCR
        if: startsWith(github.ref, 'refs/tags/v')
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.repository_owner }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and Push
        uses: docker/build-push-action@v5
        with:
          # ... existing config ...
          push: ${{ startsWith(github.ref, 'refs/tags/v') }}
```

Trigger:
```powershell
git tag v1.0.0
git push origin v1.0.0
```

## Advanced: Deploy to VPS (Optional)

Thêm job deploy:

```yaml
jobs:
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
          mkdir -p ~/.ssh
          echo "$VPS_SSH_KEY" > ~/.ssh/id_rsa
          chmod 600 ~/.ssh/id_rsa
          ssh -o StrictHostKeyChecking=no $VPS_USER@$VPS_HOST '
            cd ~/ride-hailing
            git pull
            docker compose --profile apps up -d --build
          '
```

Secrets cần thêm (Settings → Secrets → Actions):
- `VPS_HOST`: IP hoặc domain
- `VPS_USER`: SSH username
- `VPS_SSH_KEY`: Private SSH key
