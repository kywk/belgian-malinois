#!/usr/bin/env bash
# acceptance-test.sh — BPM Platform Phase 1-5 驗收測試
# 使用方式：./scripts/acceptance-test.sh [BPM_URL] [FORM_URL]

BPM_URL="${1:-http://localhost:8080}"
FORM_URL="${2:-http://localhost:8081}"

PASS=0; FAIL=0
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1: $2"; FAIL=$((FAIL+1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

# ── 工具函數 ──────────────────────────────────────────────────
bpm_post() { curl -s -X POST "$BPM_URL$1" -H "Content-Type: application/json" -H "X-User-Id: $2" -d "$3"; }
bpm_put()  { curl -s -X PUT  "$BPM_URL$1" -H "Content-Type: application/json" -H "X-User-Id: $2" -d "$3"; }
bpm_get()  { curl -s         "$BPM_URL$1" -H "X-User-Id: $2"; }

start_process() {
  local processKey="$1" initiator="$2" vars="$3"
  bpm_post "/api/process-instances" "$initiator" \
    "{\"processDefinitionKey\":\"$processKey\",\"initiator\":\"$initiator\",\"variables\":$vars}"
}

get_task_for() {
  # get_task_for userId [assignee|candidateUser] [processInstanceId]
  local userId="$1" type="${2:-assignee}" procId="${3:-}"
  local url="/api/tasks?$type=$userId"
  bpm_get "$url" "$userId" | python3 -c "
import sys,json
procId='$procId'
data=json.load(sys.stdin)
tasks=data if isinstance(data,list) else data.get('data',[])
if procId:
    tasks=[t for t in tasks if t.get('processInstanceId')==procId]
print(tasks[0]['taskId'] if tasks else '')
" 2>/dev/null || echo ""
}

complete_task() {
  # vars: JSON object like {"approved":true,"approverComment":"..."}
  # converts to List<VariableRequest> format
  local taskId="$1" userId="$2" vars="$3"
  local varList
  varList=$(echo "$vars" | python3 -c "
import sys,json
d=json.loads(sys.stdin.read())
print(json.dumps([{'name':k,'value':v} for k,v in d.items()]))
")
  bpm_put "/api/tasks/$taskId" "$userId" \
    "{\"action\":\"complete\",\"variables\":$varList}"
}

# ── TC-L01: 請假流程 — 主管同意 ──────────────────────────────
info "TC-L01: 請假流程 — 主管同意"
resp=$(start_process "leave-approval" "user001" \
  '{"leaveType":"annual","dateRange":"2026-05-01~2026-05-03","reason":"年假"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-L01" "無法啟動流程: $resp"; else
  sleep 1
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-L01" "mgr001 沒有待辦任務"; else
    result=$(complete_task "$taskId" "mgr001" '{"approved":true,"approverComment":"同意請假"}')
    echo "$result" | grep -q '"status":"ok"' && pass "TC-L01: 請假流程主管同意" || fail "TC-L01" "complete 失敗: $result"
  fi
fi

# ── TC-L02: 請假流程 — 主管退件 ──────────────────────────────
info "TC-L02: 請假流程 — 主管退件"
resp=$(start_process "leave-approval" "user002" \
  '{"leaveType":"sick","dateRange":"2026-05-05~2026-05-05","reason":"身體不適"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-L02" "無法啟動流程: $resp"; else
  sleep 1
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-L02" "mgr001 沒有待辦任務"; else
    result=$(complete_task "$taskId" "mgr001" '{"approved":false,"approverComment":"資料不足，請補充"}')
    echo "$result" | grep -q '"status":"ok"' && pass "TC-L02: 請假流程主管退件" || fail "TC-L02" "complete 失敗: $result"
  fi
fi

# ── TC-L03: 請假流程 — 主管拒絕 ──────────────────────────────
info "TC-L03: 請假流程 — 主管拒絕"
resp=$(start_process "leave-approval" "user003" \
  '{"leaveType":"personal","dateRange":"2026-05-10~2026-05-10","reason":"私事"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-L03" "無法啟動流程: $resp"; else
  sleep 1
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-L03" "mgr001 沒有待辦任務"; else
    result=$(complete_task "$taskId" "mgr001" '{"approved":false,"rejected":true,"rejectReason":"人力不足，無法核准"}')
    echo "$result" | grep -q '"status":"ok"' && pass "TC-L03: 請假流程主管拒絕" || fail "TC-L03" "complete 失敗: $result"
  fi
fi

# ── TC-L04: 請假流程 — 批註後同意 ────────────────────────────
info "TC-L04: 請假流程 — 批註後同意"
resp=$(start_process "leave-approval" "user001" \
  '{"leaveType":"annual","dateRange":"2026-06-01~2026-06-02","reason":"旅遊"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-L04" "無法啟動流程: $resp"; else
  sleep 1
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-L04" "mgr001 沒有待辦任務"; else
    bpm_post "/api/tasks/$taskId/comments" "mgr001" '{"message":"請注意交接事項"}' > /dev/null
    result=$(complete_task "$taskId" "mgr001" '{"approved":true,"approverComment":"同意，請做好交接"}')
    echo "$result" | grep -q '"status":"ok"' && pass "TC-L04: 請假流程批註後同意" || fail "TC-L04" "complete 失敗: $result"
  fi
fi

# ── TC-P01: 採購流程 — 完整同意 ──────────────────────────────
info "TC-P01: 採購流程 — 完整同意（主管 + 財務）"
resp=$(start_process "purchase-approval" "user001" \
  '{"itemName":"筆記型電腦","quantity":2,"amount":60000,"reason":"業務需求"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-P01" "無法啟動流程: $resp"; else
  sleep 1
  # 主管審核
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-P01" "mgr001 沒有主管審核任務"; else
    complete_task "$taskId" "mgr001" '{"approved":true,"approverComment":"同意採購"}' > /dev/null
    sleep 1
    # 財務審核（candidateUser）
    taskId=$(get_task_for "mgr001" "candidateUser" "$procId")
    [ -z "$taskId" ] && taskId=$(get_task_for "dir001" "candidateUser" "$procId")
    if [ -z "$taskId" ]; then fail "TC-P01" "財務審核任務不存在"; else
      bpm_put "/api/tasks/$taskId" "mgr001" '{"action":"claim"}' > /dev/null
      result=$(complete_task "$taskId" "mgr001" '{"approved":true,"approverComment":"財務審核通過"}')
      echo "$result" | grep -q '"status":"ok"' && pass "TC-P01: 採購流程完整同意" || fail "TC-P01" "財務 complete 失敗: $result"
    fi
  fi
fi

# ── TC-P02: 採購流程 — 主管退件 ──────────────────────────────
info "TC-P02: 採購流程 — 主管退件"
resp=$(start_process "purchase-approval" "user002" \
  '{"itemName":"辦公椅","quantity":5,"amount":25000,"reason":"汰換舊椅"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-P02" "無法啟動流程: $resp"; else
  sleep 1
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-P02" "mgr001 沒有待辦任務"; else
    result=$(complete_task "$taskId" "mgr001" '{"approved":false,"approverComment":"請重新評估數量"}')
    echo "$result" | grep -q '"status":"ok"' && pass "TC-P02: 採購流程主管退件" || fail "TC-P02" "complete 失敗: $result"
  fi
fi

# ── TC-P03: 採購流程 — 財務拒絕 ──────────────────────────────
info "TC-P03: 採購流程 — 財務拒絕"
resp=$(start_process "purchase-approval" "user003" \
  '{"itemName":"伺服器","quantity":1,"amount":500000,"reason":"擴充機房"}')
procId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
if [ -z "$procId" ]; then fail "TC-P03" "無法啟動流程: $resp"; else
  sleep 1
  taskId=$(get_task_for "mgr001" "assignee" "$procId")
  if [ -z "$taskId" ]; then fail "TC-P03" "mgr001 沒有主管審核任務"; else
    complete_task "$taskId" "mgr001" '{"approved":true,"approverComment":"同意，請財務審核"}' > /dev/null
    sleep 1
    taskId=$(get_task_for "dir001" "candidateUser" "$procId")
    [ -z "$taskId" ] && taskId=$(get_task_for "mgr001" "candidateUser" "$procId")
    if [ -z "$taskId" ]; then fail "TC-P03" "財務審核任務不存在"; else
      bpm_put "/api/tasks/$taskId" "dir001" '{"action":"claim"}' > /dev/null
      result=$(complete_task "$taskId" "dir001" '{"approved":false,"rejected":true,"rejectReason":"超出預算，本年度不予核准"}')
      echo "$result" | grep -q '"status":"ok"' && pass "TC-P03: 採購流程財務拒絕" || fail "TC-P03" "財務 complete 失敗: $result"
    fi
  fi
fi

# ── 結果摘要 ──────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════"
echo " 驗收測試結果"
echo "════════════════════════════════════"
echo -e " ${GREEN}PASS${NC}: $PASS"
echo -e " ${RED}FAIL${NC}: $FAIL"
echo "════════════════════════════════════"
[ "$FAIL" -eq 0 ] && echo -e "${GREEN}All tests passed!${NC}" || echo -e "${RED}Some tests failed.${NC}"
exit $FAIL
