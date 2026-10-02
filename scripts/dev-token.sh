#!/usr/bin/env bash
# 產生開發用的 JWT。
#
# ⚠️ 這個腳本刻意放在應用之外。
#
# 本服務只「驗證」JWT，不簽發、不處理 OIDC 流程 —— 那由另一個服務負責。
# 如果把簽發做成應用的端點，那就變成一個可以簽出任何身分的 API，
# 而它一旦誤留在 production，整套認證就等於不存在。
#
# prod 的 token 由企業 IdP 簽發，應用透過 bpm.security.jwt.issuer-uri
# 取得 JWKS 驗證。這個腳本只在 dev 有意義（對稱密鑰）。
#
# 用法：
#   ./scripts/dev-token.sh                 # 預設 user001
#   ./scripts/dev-token.sh admin001        # 指定身分
#   ./scripts/dev-token.sh admin001 admin  # 指定身分與 roles claim
#
# 取得後：curl -H "Authorization: Bearer $(./scripts/dev-token.sh admin001)" ...
#
# ── dev 帳號與權限碼（MockPermController 是事實來源）───────────────
#
#   user001～user005  什麼權限都沒有      → 一般員工（user001 是預設身分）
#   mgr001            hr:leave:approve、finance:payment:approve、
#                     purchase:order:approve、bpm:form:design
#                                       → 部門主管，可設計表單（非管理員）
#   mgr002            hr:leave:approve、purchase:order:approve → 部門主管
#   dir001            hr:leave:approve、finance:payment:approve、
#                     purchase:order:approve、legal:contract:review、
#                     purchase:self:approve、audit:log:read、
#                     bpm:external:revision
#                                       → 總監，同時是稽核職能
#   admin001          *（通配）→ ROLE_ADMIN
#
# ⚠️ **不要用 `admin001 admin` 讀稽核。**
# `/api/audit-logs/**` 只接受 audit:log:read，刻意不接受 ROLE_ADMIN ——
# 稽核紀錄含全公司薪資與簽核意見，而 ProcessAccessGuard 早已拒絕
# ROLE_ADMIN 讀案件流程變數；放行等於留下一條側門。
# 正確用法（dir001 由權限中心持有 audit:log:read）：
#
#   curl "http://localhost:8080/api/audit-logs?size=5" \
#     -H "Authorization: Bearer $(./scripts/dev-token.sh dir001)"
#
# ⚠️ 第二個參數（roles claim）會**完全取代**權限中心查詢
# （SecurityConfig.authoritiesFromJwt：claim 有角色就不回頭查）。
# 所以帶了 roles claim 就等於宣告「這個人的權限就是這些」——
# `dev-token.sh dir001 admin` 會讓 dir001 變成管理員，且**不會**有
# audit:log:read。要用權限中心的權限碼，就**不要**帶第二個參數。
set -euo pipefail

SUBJECT="${1:-user001}"
ROLES="${2:-}"
# 與 application.yml 的 bpm.security.jwt.dev-secret 一致。
SECRET="${BPM_DEV_JWT_SECRET:-bpm-dev-jwt-secret-do-not-use-in-production}"
TTL_SECONDS="${BPM_DEV_JWT_TTL:-3600}"

python3 - "$SUBJECT" "$ROLES" "$SECRET" "$TTL_SECONDS" <<'PY'
import base64, hashlib, hmac, json, sys, time

subject, roles, secret, ttl = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])

def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

now = int(time.time())
claims = {"sub": subject, "iat": now, "exp": now + ttl}
if roles:
    # roles claim 存在時，應用會優先採用它而不查權限中心
    # （政策決策 2026-09-29）。逗號分隔。
    claims["roles"] = [r.strip() for r in roles.split(",") if r.strip()]

header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64(json.dumps(claims, separators=(",", ":"), ensure_ascii=False).encode())
signing_input = f"{header}.{payload}".encode()
signature = b64(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
print(f"{header}.{payload}.{signature}")
PY
