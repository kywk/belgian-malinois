#!/bin/bash
set -uo pipefail

# SA 密碼一律取自 MSSQL_SA_PASSWORD 環境變數（mssql 官方 image 用同一個變數建立 SA 帳號）。
# 不要在此寫死密碼：dev 由 docker-compose.yml 提供，prod 由 ${MSSQL_SA_PASSWORD} 注入，
# 兩者若不一致會導致 prod 首次部署的 DB 初始化失敗。
if [ -z "${MSSQL_SA_PASSWORD:-}" ]; then
    echo "ERROR: MSSQL_SA_PASSWORD is not set. Refusing to start." >&2
    exit 1
fi

SQLCMD=/opt/mssql-tools18/bin/sqlcmd
INIT_SQL=/docker-entrypoint-initdb.d/init.sql

# Start SQL Server in background
/opt/mssql/bin/sqlservr &
pid=$!

# Wait for SQL Server to be ready
echo "Waiting for MSSQL to start..."
ready=0
for i in {1..60}; do
    if "$SQLCMD" -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -Q "SELECT 1" > /dev/null 2>&1; then
        echo "MSSQL is ready. Running init script..."
        if "$SQLCMD" -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -i "$INIT_SQL"; then
            echo "Init script completed."
            ready=1
        else
            echo "ERROR: init script failed." >&2
            exit 1
        fi
        break
    fi
    sleep 1
done

if [ "$ready" -ne 1 ]; then
    echo "ERROR: MSSQL did not become ready within 60s; init script was not run." >&2
    exit 1
fi

# Keep SQL Server running in foreground
wait $pid
