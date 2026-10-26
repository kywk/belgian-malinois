#!/usr/bin/env bash
# One-command isolated browser workflow; no localenv data is read or reset.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
prepared=false
usage() {
  cat <<'HELP'
Usage: scripts/browser-e2e.sh [--prepared] [--help]

需要已安裝 JDK 21、Node 24、Maven、Docker；首次預備另需 UniForm 指定版本 pnpm。
預設：安裝 pinned UniForm Java SDK、建置 npm tarballs、npm ci、下載 Chromium，執行隔離 E2E。
--prepared  省略依賴預備與瀏覽器下載，直接重新建置後端並執行 E2E。
E2E_INSTALL_WITH_DEPS=1  Linux CI 下載 Chromium 時同時安裝系統依賴。
報告：bpm-frontend/playwright-report/、bpm-frontend/test-results/、bpm-core/target/failsafe-reports/。
HELP
}
for arg in "$@"; do
  case "$arg" in
    --prepared) prepared=true ;;
    --help|-h) usage; exit 0 ;;
    *) printf '不支援的參數：%s\n' "$arg" >&2; usage >&2; exit 2 ;;
  esac
done
for tool in java node npm mvn docker; do
  command -v "$tool" >/dev/null 2>&1 || { printf '缺少工具：%s；請先配置 PATH。\n' "$tool" >&2; exit 2; }
done
[[ "$(node -p 'process.versions.node.split(".")[0]')" == 24 ]] || { printf '需要 Node 24。\n' >&2; exit 2; }
java_version="$(java -version 2>&1)"
[[ "$java_version" == *'version "21.'* ]] || { printf '需要 JDK 21；請檢查 JAVA_HOME 與 PATH。\n' >&2; exit 2; }
maven_version="$(mvn --version 2>&1)"
[[ "$maven_version" == *'Java version: 21.'* ]] || { printf 'Maven 必須使用 JDK 21；JAVA_HOME 可能與 PATH 不一致。\n' >&2; exit 2; }
docker info >/dev/null
[[ -f "$repo_root/vendor/uni-form/java/pom.xml" ]] || { printf '請先執行 git submodule update --init vendor/uni-form。\n' >&2; exit 2; }
if [[ "$prepared" == false ]]; then
  mvn -B -f "$repo_root/vendor/uni-form/java/pom.xml" \
    -pl uniform-spring-boot4-starter -am -DskipTests clean install
  bash "$repo_root/scripts/build-uniform-frontend.sh"
  (cd "$repo_root/bpm-frontend" && npm ci)
  if [[ "${E2E_INSTALL_WITH_DEPS:-0}" == 1 ]]; then
    (cd "$repo_root/bpm-frontend" && npx --no-install playwright install --with-deps chromium)
  else
    (cd "$repo_root/bpm-frontend" && npx --no-install playwright install chromium)
  fi
fi
cd "$repo_root"
exec mvn -B -f bpm-core/pom.xml -Pbrowser-e2e verify
