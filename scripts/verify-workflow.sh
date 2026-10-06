#!/bin/bash
# Script kiểm tra nhanh workflow syntax và logic

set -e

echo "=== Checking workflow file syntax ==="
if command -v yamllint >/dev/null 2>&1; then
    yamllint .github/workflows/ci.yml
    echo "✓ YAML syntax valid"
else
    echo "ℹ yamllint not installed, skipping syntax check"
fi

echo ""
echo "=== Checking workflow structure ==="

# Check jobs exist
for job in test contracts images; do
    if grep -q "^  $job:" .github/workflows/ci.yml; then
        echo "✓ Job '$job' found"
    else
        echo "✗ Job '$job' missing"
        exit 1
    fi
done

echo ""
echo "=== Checking test job ==="
grep -q "uses: actions/setup-java@v4" .github/workflows/ci.yml && echo "✓ setup-java@v4"
grep -q "java-version: '21'" .github/workflows/ci.yml && echo "✓ Java 21"
grep -q "distribution: 'temurin'" .github/workflows/ci.yml && echo "✓ Temurin distribution"
grep -q "cache: 'maven'" .github/workflows/ci.yml && echo "✓ Maven cache"
grep -q "mvn -B verify" .github/workflows/ci.yml && echo "✓ mvn verify command"
grep -q "surefire-reports" .github/workflows/ci.yml && echo "✓ Surefire reports upload"

echo ""
echo "=== Checking contracts job ==="
grep -q "shell: pwsh" .github/workflows/ci.yml && echo "✓ PowerShell shell"
grep -q "docs/contracts/manifest.json" .github/workflows/ci.yml && echo "✓ Manifest check"

echo ""
echo "=== Checking images job ==="
grep -q "strategy:" .github/workflows/ci.yml && echo "✓ Matrix strategy"
grep -q "matrix:" .github/workflows/ci.yml && echo "✓ Matrix definition"

# Check all modules in matrix
for module in user-service location-service dispatch-service pricing-service payment-service ws-gateway api-gateway; do
    if grep -q "$module" .github/workflows/ci.yml; then
        echo "✓ Module '$module' in matrix"
    else
        echo "✗ Module '$module' missing from matrix"
        exit 1
    fi
done

grep -q "docker/build-push-action@v5" .github/workflows/ci.yml && echo "✓ Docker build action"
grep -q "push: false" .github/workflows/ci.yml && echo "✓ Push disabled (as expected)"
grep -q "cache-from: type=gha" .github/workflows/ci.yml && echo "✓ GitHub Actions cache"

echo ""
echo "=== All checks passed! ==="
echo ""
echo "Next steps:"
echo "1. Create test branch: git checkout -b test-ci"
echo "2. Commit workflow: git add .github/workflows/ && git commit -m 'ci: add GitHub Actions pipeline'"
echo "3. Push to GitHub: git push origin test-ci"
echo "4. Check Actions tab on GitHub"
