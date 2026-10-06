# Fix Applied: Lowercase Image Tags

## Issue
```
ERROR: failed to build: invalid tag "ghcr.io/Cunos982003/ride-hailing-user-service:..."
repository name must be lowercase
```

## Root Cause
GitHub username `Cunos982003` chứa chữ hoa, nhưng Docker image tags phải lowercase.

## Solution Applied

Thêm step "Prepare image tags" trong job `images`:

```yaml
- name: Prepare image tags
  id: meta
  run: |
    OWNER_LOWER=$(echo "${{ github.repository_owner }}" | tr '[:upper:]' '[:lower:]')
    echo "owner=${OWNER_LOWER}" >> $GITHUB_OUTPUT
    echo "tag=${OWNER_LOWER}/ride-hailing-${{ matrix.module }}" >> $GITHUB_OUTPUT

- name: Build Docker image
  uses: docker/build-push-action@v5
  with:
    tags: |
      ghcr.io/${{ steps.meta.outputs.tag }}:${{ github.sha }}
      ghcr.io/${{ steps.meta.outputs.tag }}:latest
```

## Changes
- Convert `github.repository_owner` sang lowercase bằng `tr '[:upper:]' '[:lower:]'`
- Store trong `$GITHUB_OUTPUT` để dùng ở steps sau
- Tags bây giờ luôn lowercase: `ghcr.io/cunos982003/ride-hailing-user-service:...`

## Verification

```bash
# Xem diff
git diff .github/workflows/ci.yml

# Commit fix
git add .github/workflows/ci.yml CI-SETUP-COMPLETE.md
git commit -m "fix(ci): convert repository owner to lowercase for Docker tags"
git push origin test-ci
```

## Result
- ✓ Image tags bây giờ valid: `ghcr.io/cunos982003/ride-hailing-*:sha`
- ✓ Workflow sẽ pass job `images`
- ✓ Không cần thay đổi gì khác

---

**File đã update:**
- `.github/workflows/ci.yml` - Thêm step convert lowercase
- `CI-SETUP-COMPLETE.md` - Thêm troubleshooting section
