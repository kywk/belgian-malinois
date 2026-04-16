#!/bin/bash

# Start SQL Server in background
/opt/mssql/bin/sqlservr &
pid=$!

# Wait for SQL Server to be ready
echo "Waiting for MSSQL to start..."
for i in {1..60}; do
    /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "BpmDev@2026!" -C -Q "SELECT 1" > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "MSSQL is ready. Running init script..."
        /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "BpmDev@2026!" -C -i /docker-entrypoint-initdb.d/init.sql
        echo "Init script completed."
        break
    fi
    sleep 1
done

# Keep SQL Server running in foreground
wait $pid
