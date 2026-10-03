#!/usr/bin/env bash
# seed-data.sh — 初始化 BPM 平台測試資料
# 使用方式：./scripts/seed-data.sh [BPM_URL] [FORM_URL]

set -e

BPM_URL="${1:-http://localhost:8080}"
# Stage 3（ADR-001）：form-service 已併入 bpm-core，
# 表單 API 由同一個服務提供，因此預設與 BPM_URL 相同。
FORM_URL="${2:-http://localhost:8080}"

GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

# ── 等待服務就緒 ──────────────────────────────────────────────
# ── 身分（R-01：平台已啟用認證）────────────────────────────────────
#
# 改動前這些呼叫靠 X-User-Id 標頭自報身分。現在後端要求 JWT
# （使用者路徑）或閘道密鑰（server 之間）。
#
# 這裡走 JWT，因為驗收測試模擬的是「使用者操作」—— 走閘道會繞過
# 真正的驗證路徑，那就驗不到 R-01 實際保護了什麼。
#
# token 由 scripts/dev-token.sh 簽發（應用只驗證、不簽發）。
# 同一個身分只簽一次，避免每個請求都 fork 一次 python。
declare -A _TOKEN_CACHE
dev_token() {
  local user="$1"
  if [[ -z "${_TOKEN_CACHE[$user]:-}" ]]; then
    _TOKEN_CACHE[$user]=$("$(dirname "${BASH_SOURCE[0]}")/dev-token.sh" "$user")
  fi
  printf '%s' "${_TOKEN_CACHE[$user]}"
}
auth_header() { printf 'Authorization: Bearer %s' "$(dev_token "$1")"; }

wait_for() {
  local url="$1" name="$2" retries=30
  echo "Waiting for $name..."
  for i in $(seq 1 $retries); do
    # Accept UP or UNKNOWN (mail health may be DOWN but app is running)
    status=$(curl -sf "$url/actuator/health" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',''))" 2>/dev/null)
    if [[ "$status" == "UP" || "$status" == "UNKNOWN" ]]; then ok "$name is up"; return; fi
    # Fallback: check if port is responding at all
    if curl -sf "$url/actuator/health" 2>/dev/null | grep -q '"status"'; then ok "$name is up (degraded)"; return; fi
    sleep 3
  done
  fail "$name did not start in time"
}

wait_for "$BPM_URL"  "bpm-core"
wait_for "$FORM_URL" "form-service"

# ── 部署 BPMN ────────────────────────────────────────────────
deploy_bpmn() {
  local file="$1" name="$2"
  echo "Deploying $name..."
  resp=$(curl -sf -X POST "$BPM_URL/api/deployments" \
    -H "$(auth_header admin001)" \
    -F "file=@$file" \
    -F "deploymentName=$name") || fail "Failed to deploy $name"
  ok "Deployed $name: $(echo "$resp" | grep -o '"id":"[^"]*"' | head -1)"
}

deploy_bpmn "bpm-core/src/main/resources/processes/leave-approval.bpmn20.xml"    "leave-approval"
deploy_bpmn "bpm-core/src/main/resources/processes/purchase-approval.bpmn20.xml" "purchase-approval"

# 特定子流程加簽模板（#4，spec §4.4.2）。
# ⚠️ name 必須含 .bpmn20.xml：Flowable 只把 .bpmn20.xml／.bpmn 後綴的資源
# 當 BPMN 解析（ResourceNameUtil.BPMN_RESOURCE_SUFFIXES），少了後綴會
# 「部署成功」但不產生流程定義 —— 設計器的 Call Activity 下拉就看不到
# countersign-review。上面兩支是靠 classpath 自動部署才有定義，這裡刻意
# 讓部署本身產生定義。
deploy_bpmn "bpm-core/src/main/resources/processes/countersign-review.bpmn20.xml" "countersign-review.bpmn20.xml"

# ── 驗證表單定義（form-service 已透過 data.sql 初始化）────────
echo "Verifying form definitions..."
for formKey in leave-request leave-review purchase-request purchase-review; do
  resp=$(curl -sf "$FORM_URL/api/forms/$formKey" -H "$(auth_header admin001)") || fail "Form $formKey not found"
  ok "Form $formKey exists"
done

# ── 驗證 Mock Org/Perm ────────────────────────────────────────
echo "Verifying mock org/perm..."
mgr=$(curl -sf "$BPM_URL/mock/org/api/users/user001/manager" | grep -o '"managerId":"[^"]*"')
ok "user001 manager: $mgr"

finance_approvers=$(curl -sf "$BPM_URL/mock/perm/api/permissions/finance:payment:approve/users")
ok "finance:payment:approve users: $finance_approvers"

echo ""
ok "=== Seed data completed ==="
echo ""
echo "Test users:"
echo "  申請人: user001 / user002 / user003"
echo "  主管:   mgr001 (dept001), mgr002 (dept002)"
echo "  總監:   dir001"
echo "  管理員: admin001"
echo ""
echo "Deployed processes: leave-approval, purchase-approval, countersign-review"
echo "Form definitions:   leave-request, leave-review, purchase-request, purchase-review"
