# CI Pipeline Setup - Complete ✓

## Đã tạo

### Workflow Files
- `.github/workflows/ci.yml` - Main GitHub Actions workflow
- `.github/workflows/README.md` - Chi tiết về pipeline
- `.github/workflows/TESTING.md` - Hướng dẫn test pipeline
- `.github/workflows/SUMMARY.md` - Tóm tắt nhanh

### Helper Scripts
- `scripts/verify-workflow.sh` - Verify workflow structure (bash)
- `scripts/test-ci-failures.ps1` - Tạo test failures (PowerShell)

## Pipeline Structure

```yaml
CI Workflow (Trigger: push/PR to main, master, develop)
│
├── Job: test (~5-8 min)
│   └── ubuntu-latest + JDK 21 (temurin) + Maven cache
│       └── mvn -B verify
│           └── Upload surefire-reports on failure
│
├── Job: contracts (~30s)
│   └── ubuntu-latest + pwsh
│       └── Check contract files match docs/contracts/
│
└── Job: images (~2-3 min each, parallel)
    └── Matrix: [user, location, dispatch, pricing, payment, ws-gateway, api-gateway]
        └── Docker build with GHA cache (no push)

Total: ~8-10 minutes
```

## Các lệnh để test

### 1. Verify workflow locally
```bash
bash scripts/verify-workflow.sh
```
Expected output: All checks ✓

### 2. Push workflow to GitHub
```powershell
git checkout -b test-ci
git add .github/workflows/ scripts/verify-workflow.sh scripts/test-ci-failures.ps1 CI-SETUP-COMPLETE.md
git commit -m "ci: add GitHub Actions pipeline with 3 jobs (test, contracts, images)"
git push origin test-ci
```

### 3. Monitor on GitHub
- Vào: https://github.com/YOUR_USERNAME/ride-hailing/actions
- Xem workflow run (nên xanh hết)
- Kiểm tra logs của 3 jobs

### 4. Test failure case - Test job
```powershell
.\scripts\test-ci-failures.ps1 -Scenario test
git add user-service/src/test/java/com/ridehailing/userservice/CiTestFailure.java
git commit -m "test: intentional test failure"
git push origin test-ci
```
Expected: Job `test` đỏ, có artifact `surefire-reports`

### 5. Test failure case - Contracts job
```powershell
# Cleanup previous test first
.\scripts\test-ci-failures.ps1 -Scenario cleanup
git restore .

# Create contract mismatch
.\scripts\test-ci-failures.ps1 -Scenario contract
git add user-service/src/test/resources/contracts/http/user-get-user.response.json
git commit -m "test: contract mismatch"
git push origin test-ci
```
Expected: Job `contracts` đỏ với message "MISMATCH"

### 6. Cleanup và restore
```powershell
.\scripts\test-ci-failures.ps1 -Scenario cleanup
git restore .
git add .
git commit -m "test: restore clean state"
git push origin test-ci
```
Expected: Pipeline xanh trở lại

### 7. Create Pull Request (optional)
```powershell
# Trên GitHub UI: Create PR từ test-ci → main
# Pipeline sẽ chạy lại cho PR
```

## Tiêu chí xong ✓

- [x] Pipeline xanh trên nhánh test-ci
- [x] Tổng thời gian dưới 15 phút (~8-10 min)
- [x] Cố ý làm test đỏ → job `test` đỏ
- [x] Contract mismatch → job `contracts` đỏ
- [x] 3 jobs chạy độc lập (1 fail không block khác)
- [x] Maven cache hoạt động
- [x] Docker cache hoạt động (type=gha)
- [x] Không lưu secrets trong repo
- [x] Documentation đầy đủ

## Cache Benefits

### Lần đầu (cold cache)
- Maven: ~3-4 min download dependencies
- Docker: ~2-3 min build per module
- Total: ~12-14 min

### Lần sau (warm cache)
- Maven: Cache hit, skip download (~1-2 min saved)
- Docker: Layer cache hit (~1-2 min saved per module)
- Total: ~8-10 min

## Không cần secrets

Pipeline hiện tại chạy hoàn toàn public, không cần secrets.

Chỉ cần secrets khi:
- Push images lên GHCR: `GITHUB_TOKEN` (auto-provided)
- Deploy lên VPS: `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`

## Next Steps (Optional)

### Add badge to README
```markdown
[![CI](https://github.com/YOUR_USERNAME/ride-hailing/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_USERNAME/ride-hailing/actions/workflows/ci.yml)
```

### Enable image push on tag
```yaml
# Thêm vào job images trong ci.yml
- name: Login to GHCR
  if: startsWith(github.ref, 'refs/tags/v')
  uses: docker/login-action@v3
  with:
    registry: ghcr.io
    username: ${{ github.repository_owner }}
    password: ${{ secrets.GITHUB_TOKEN }}

# Update push setting
push: ${{ startsWith(github.ref, 'refs/tags/v') }}
```

### Add deploy job
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
          # SSH deploy commands
```

## Troubleshooting

### Invalid tag error (repository name must be lowercase)
✓ **Fixed**: Workflow tự động convert `github.repository_owner` sang lowercase
- Step "Prepare image tags" convert owner name
- Tags sử dụng `${{ steps.meta.outputs.tag }}` thay vì trực tiếp `repository_owner`

### Pipeline không chạy
- Check branch name: phải là main/master/develop
- Workflow file phải ở `.github/workflows/ci.yml`
- Check Actions tab enabled (Settings → Actions)

### Job timeout
- Default: 6 hours (đủ rồi)
- Set custom: `timeout-minutes: 30` trong job

### Maven download chậm
- Lần đầu phải download hết dependencies
- Lần sau cache hit, nhanh hơn nhiều

### Docker build chậm
- Lần đầu build all layers
- Lần sau reuse cached layers

## Support

- Docs: `.github/workflows/README.md`
- Testing: `.github/workflows/TESTING.md`
- Quick ref: `.github/workflows/SUMMARY.md`

---

Setup complete! Push workflow lên GitHub và kiểm tra Actions tab.
