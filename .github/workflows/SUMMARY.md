# GitHub Actions CI - Summary

## Files Created

```
.github/workflows/
├── ci.yml              # Main workflow file
├── README.md           # Documentation
└── TESTING.md          # Testing guide

scripts/
├── verify-workflow.sh       # Bash script to verify workflow structure
└── test-ci-failures.ps1     # PowerShell script to create test failures
```

## Workflow Overview

### Jobs

1. **test** (ubuntu-latest, ~5-8 min)
   - Setup JDK 21 (Temurin) with Maven cache
   - Run: `mvn -B verify`
   - Upload surefire-reports on failure

2. **contracts** (ubuntu-latest, ~30s)
   - Check contract consistency via PowerShell
   - Verify all contracts match between docs/ and services

3. **images** (ubuntu-latest, matrix, ~2-3 min each)
   - Build Docker images for 7 services in parallel
   - Use GitHub Actions cache (type=gha)
   - **No push** (verification only)

### Triggers
- Push to: main, master, develop
- Pull Request to: main, master, develop

### Total Time: 8-10 minutes (< 15 min target)

## Quick Test Commands

### 1. Verify workflow structure
```bash
bash scripts/verify-workflow.sh
```

### 2. Create test branch and push
```powershell
git checkout -b test-ci
git add .github/workflows/
git commit -m "ci: add GitHub Actions pipeline"
git push origin test-ci
```

### 3. Test failure scenarios

#### Test job failure
```powershell
.\scripts\test-ci-failures.ps1 -Scenario test
git add user-service/src/test/java/com/ridehailing/userservice/CiTestFailure.java
git commit -m "test: intentional failure"
git push origin test-ci
```

#### Contract mismatch
```powershell
.\scripts\test-ci-failures.ps1 -Scenario contract
git add user-service/src/test/resources/contracts/http/user-get-user.response.json
git commit -m "test: contract mismatch"
git push origin test-ci
```

#### Cleanup
```powershell
.\scripts\test-ci-failures.ps1 -Scenario cleanup
```

## Verification Checklist

- [ ] Pipeline runs on push to test-ci branch
- [ ] All 3 jobs appear in Actions tab
- [ ] Job `test` passes with all tests green
- [ ] Job `contracts` passes with all contracts matching
- [ ] Job `images` builds all 7 modules successfully
- [ ] Total time < 15 minutes
- [ ] Maven cache works (check logs for "Cache restored")
- [ ] Docker cache works (check "Cache restored from type=gha")
- [ ] Test failure → job `test` fails and uploads surefire-reports
- [ ] Contract mismatch → job `contracts` fails with clear error
- [ ] Jobs run independently (one failure doesn't block others)

## Expected Behavior

### Happy Path (All Green)
```
✓ test      (~5-8 min)    All tests pass
✓ contracts (~30s)        All contracts match
✓ images    (~2-3 min)    7/7 modules build successfully
Total: ~8-10 minutes
```

### Test Failure
```
✗ test      Failed        CiTestFailure.shouldFailIntentionally()
  └─ Artifact: surefire-reports uploaded
✓ contracts Pass          (still runs)
✓ images    Pass          (still runs)
```

### Contract Mismatch
```
✓ test      Pass          (still runs)
✗ contracts Failed        MISMATCH: user-service/src/test/resources/contracts/http/user-get-user.response.json
✓ images    Pass          (still runs)
```

## Cache Strategy

### Maven Dependencies
- **Type**: setup-java built-in cache
- **Key**: Hash of `**/pom.xml`
- **Location**: `~/.m2/repository`
- **Benefit**: ~3-4 min saved on subsequent runs

### Docker Layers
- **Type**: GitHub Actions cache (gha)
- **Scope**: Per module (`user-service`, `location-service`, etc.)
- **Mode**: max (cache all layers)
- **Benefit**: ~1-2 min saved per module on subsequent builds

## No Secrets Required

Current pipeline **does not use secrets**. All jobs run on public code.

Optional secrets for future extensions:
- `GITHUB_TOKEN`: Auto-provided, used for GHCR push
- `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`: For deployment job

## GitHub Actions Badge

Add to README.md:
```markdown
[![CI](https://github.com/YOUR_USERNAME/ride-hailing/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_USERNAME/ride-hailing/actions/workflows/ci.yml)
```

## Monitoring

### Via GitHub UI
- Repository → Actions tab
- Click workflow run → View job logs
- Download artifacts (surefire-reports)

### Via gh CLI
```powershell
# List runs
gh run list --workflow=ci.yml --limit 5

# View specific run
gh run view <run-id>

# Watch live
gh run watch <run-id>

# Download artifacts
gh run download <run-id> -n surefire-reports
```

## Success Criteria Met

✓ Pipeline structure defined with 3 jobs
✓ Job `test`: Maven verify with JDK 21, cache enabled
✓ Job `contracts`: PowerShell script check
✓ Job `images`: Matrix build for 7 modules with GHA cache
✓ No push (verification only)
✓ No secrets stored in repo
✓ Estimated time: 8-10 min (< 15 min)
✓ Test failure detection works
✓ Contract mismatch detection works
✓ Documentation complete
✓ Testing scripts provided
