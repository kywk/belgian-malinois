#!/usr/bin/env bash
# localdev.sh — 本機開發環境入口（以 ~/nanshan/scripts/localenv 為準）
#
# 規則（使用者指示 2026-10-13）：
#   1. 偵測得到 localenv（$LOCALENV_SH 或 ~/nanshan/scripts/localenv.sh）→ 一律調用它，
#      專案端不重複 infra／建置邏輯。
#   2. 偵測不到 → 自足 fallback：完整專案 compose。容器內先從 vendor/uni-form
#      （pinned submodule）建置 uni-form SDK，再建 bpm-core —— SNAPSHOT 不在任何
#      registry，由 submodule 原始碼可重現；需要已初始化的 submodule。
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
#     fallback：API http://127.0.0.1:8080、網站（nginx）http://localhost:80。
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
localenv_port() { bash "$LOCALENV_SH" greyhound env 2>/dev/null | awk -F= '/^SERVER_PORT=/{print $2; exit}'; }
api_url() {
  if [[ "$MODE" == localenv ]]; then
    local p; p="$(localenv_port)"; printf 'http://127.0.0.1:%s' "${p:-18110}"
  else
    printf 'http://127.0.0.1:8080'
  fi
}

compose_up() {
  [[ -d vendor/uni-form/java ]] || {
    echo "[localdev] 缺少 vendor/uni-form（容器內要從它建 SDK）——請先 git submodule update --init vendor/uni-form" >&2
    exit 1
  }
  echo "[localdev] 未偵測到 localenv → 專案 fallback（完整 compose；容器內從 pinned submodule 建 SDK）"
  compose up -d --build --scale bpm-frontend=0
  echo "[localdev] 等待 bpm-core 健康…"
  for _ in $(seq 1 150); do
    if curl -sf http://127.0.0.1:8080/actuator/health >/dev/null; then
      echo "[localdev] bpm-core 就緒（$(api_url)）"; return 0
    fi
    sleep 2
  done
  echo "[localdev] bpm-core 未就緒：docker compose logs bpm-core" >&2
  exit 1
}

cmd="${1:-}"; shift || true
case "$cmd" in
  up)
    if [[ "$MODE" == localenv ]]; then
      bash "$LOCALENV_SH" greyhound ready "$@"
      echo "[localdev] API: $(api_url)（前端：./scripts/localdev.sh frontend）"
    else
      compose_up
    fi
    ;;
  down)
    if [[ "$MODE" == localenv ]]; then bash "$LOCALENV_SH" greyhound stop; else compose down; fi
    ;;
  status)
    if [[ "$MODE" == localenv ]]; then bash "$LOCALENV_SH" greyhound status; else compose ps; fi
    ;;
  logs)
    if [[ "$MODE" == localenv ]]; then bash "$LOCALENV_SH" greyhound logs "$@"; else compose logs -f --tail=100 bpm-core; fi
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
    sed -n '2,17p' "$0"
    exit 2
    ;;
esac
