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
