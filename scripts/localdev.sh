#!/usr/bin/env bash
# localdev.sh — 本機開發環境入口（以 ~/nanshan/scripts/localenv 為準）
#
# 規則（使用者指示 2026-10-13）：
#   1. 偵測得到 localenv（$LOCALENV_SH 或 ~/nanshan/scripts/localenv.sh）→ 一律調用它，
#      專案端不重複 infra／建置邏輯。
#   2. 偵測不到 → 自足 fallback：專案 compose 只起 infra，bpm-core 以
#      「主機 Maven 建置＋主機 jar」執行（與 localenv 相同的埠位語意）。
#      ⚠️ fallback 的建置需要 uni-form SDK 已安裝在 ~/.m2（見 vendor/uni-form：
#      `mvn -f java install`）；這是本機產物，不是 registry 依賴。
#
# 用法：
#   ./scripts/localdev.sh up [--debug]      起服務（含 infra）
#   ./scripts/localdev.sh down              停服務（localenv 只停 greyhound；fallback 全停）
#   ./scripts/localdev.sh status
#   ./scripts/localdev.sh logs [-f]
#   ./scripts/localdev.sh frontend          本機 Vite dev（proxy 指向本機 API）
#   ./scripts/localdev.sh seed [URL]        BPMN 部署＋表單驗證（scripts/seed-data.sh）
#   ./scripts/localdev.sh acceptance [URL]  平台 curl 驗收（scripts/acceptance-test.sh）
#
# 埠：localenv 由 `localenv.sh greyhound env` 的 SERVER_PORT 決定（預設 18110）；
#     fallback 為主機 jar 的 http://127.0.0.1:8080。
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

LOCALENV_SH="${LOCALENV_SH:-}"
if [[ -z "$LOCALENV_SH" ]]; then
  for cand in "$HOME/nanshan/scripts/localenv.sh" "$HOME/Dropbox/nanshan/scripts/localenv.sh"; do
    [[ -f "$cand" ]] && { LOCALENV_SH="$cand"; break; }
  done
fi
MODE="compose"; [[ -n "$LOCALENV_SH" ]] && MODE="localenv"

compose() { docker compose -f docker-compose.yml -f docker-compose.dev.yml "$@"; }
RUN_DIR="$ROOT/.localdev"
JAR="$ROOT/bpm-core/target/bpm-core-0.0.1-SNAPSHOT.jar"

localenv_port() { bash "$LOCALENV_SH" greyhound env 2>/dev/null | awk -F= '/^SERVER_PORT=/{print $2; exit}'; }
api_url() {
  if [[ "$MODE" == localenv ]]; then
    local p; p="$(localenv_port)"; printf 'http://127.0.0.1:%s' "${p:-18110}"
  else
    printf 'http://127.0.0.1:8080'
  fi
}

compose_up() {
  # 只起 infra：bpm-core 以主機 jar 執行（SNAPSHOT 相依不在任何 registry，
  # 容器內 `mvn dependency:go-offline` 抓不到 —— 見 localenv 為準的說明）。
  compose up -d mssql rabbitmq redis mailhog
  compose up -d --wait mssql rabbitmq redis mailhog >/dev/null 2>&1 || true
}

compose_start_app() {
  [[ -f "$JAR" ]] || {
    echo "[localdev] 建置 bpm-core（mvn -o package -DskipTests）…"
    (cd bpm-core && mvn -o package -DskipTests -q)
  }
  mkdir -p "$RUN_DIR"
  if [[ -f "$RUN_DIR/bpm-core.pid" ]] && kill -0 "$(cat "$RUN_DIR/bpm-core.pid")" 2>/dev/null; then
    echo "[localdev] bpm-core 已在執行（pid $(cat "$RUN_DIR/bpm-core.pid")）"; return 0
  fi
  BPM_API_KEY_HMAC_SECRET="${BPM_API_KEY_HMAC_SECRET:-bpm-dev-api-key-hmac-secret-do-not-use-in-production}" \
    nohup java -jar "$JAR" > "$RUN_DIR/bpm-core.log" 2>&1 &
  echo $! > "$RUN_DIR/bpm-core.pid"
  echo "[localdev] bpm-core 啟動中（pid $(cat "$RUN_DIR/bpm-core.pid")，log: $RUN_DIR/bpm-core.log）"
  for _ in $(seq 1 60); do
    curl -sf "http://127.0.0.1:8080/actuator/health" >/dev/null && { echo "[localdev] bpm-core 就緒"; return 0; }
    sleep 2
  done
  echo "[localdev] bpm-core 未就緒，請看 $RUN_DIR/bpm-core.log" >&2
  return 1
}

cmd="${1:-}"; shift || true
case "$cmd" in
  up)
    if [[ "$MODE" == localenv ]]; then
      echo "[localdev] localenv 模式：bash $LOCALENV_SH greyhound ready $*"
      bash "$LOCALENV_SH" greyhound ready "$@"
      echo "[localdev] API: $(api_url)（前端：./scripts/localdev.sh frontend）"
    else
      echo "[localdev] 未偵測到 localenv → 專案 fallback（compose infra ＋ 主機 jar）"
      compose_up
      compose_start_app
      echo "[localdev] API: $(api_url)"
    fi
    ;;
  down)
    if [[ "$MODE" == localenv ]]; then
      bash "$LOCALENV_SH" greyhound stop
    else
      if [[ -f "$RUN_DIR/bpm-core.pid" ]]; then
        kill "$(cat "$RUN_DIR/bpm-core.pid")" 2>/dev/null || true
        rm -f "$RUN_DIR/bpm-core.pid"
      fi
      compose down
    fi
    ;;
  status)
    if [[ "$MODE" == localenv ]]; then
      bash "$LOCALENV_SH" greyhound status
    else
      if [[ -f "$RUN_DIR/bpm-core.pid" ]] && kill -0 "$(cat "$RUN_DIR/bpm-core.pid")" 2>/dev/null; then
        echo "bpm-core: running (pid $(cat "$RUN_DIR/bpm-core.pid"))"
      else
        echo "bpm-core: stopped"
      fi
      compose ps
    fi
    ;;
  logs)
    if [[ "$MODE" == localenv ]]; then
      bash "$LOCALENV_SH" greyhound logs "$@"
    else
      tail -n 100 -f "$RUN_DIR/bpm-core.log" 2>/dev/null || echo "沒有 log（尚未啟動？）"
    fi
    ;;
  frontend)
    local_api="$(api_url)"
    echo "[localdev] Vite dev；API proxy → $local_api"
    (cd bpm-frontend && VITE_API_PROXY="$local_api" npm run dev "$@")
    ;;
  seed)
    url="${1:-$(api_url)}"; bash scripts/seed-data.sh "$url" "${2:-$url}"
    ;;
  acceptance)
    url="${1:-$(api_url)}"; bash scripts/acceptance-test.sh "$url" "${2:-$url}"
    ;;
  *)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
