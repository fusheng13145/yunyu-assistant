@echo off
REM ============================================================
REM  Yunyu Assistant - Backend Startup (local dev)
REM  Health check:  http://localhost:8080/actuator/health
REM  Requires JDK21 & MySQL8 (schema initialized via index.sql)
REM ============================================================
setlocal
cd /d "%~dp0backend"

REM ---- Database (local dev account created during setup) ----
set DB_HOST=localhost
set DB_PORT=3306
set DB_NAME=yunyu_assistant
set DB_USER=yunyu_app
set DB_PASSWORD=yunyu_dev_2026

REM ---- JWT (fixed dev value; use strong random in production) ----
set JWT_SECRET=dev-jwt-secret-key-0123456789abcdef0123456789abcdef
set JWT_EXPIRATION=86400000
set SERVER_PORT=8080

echo.
echo [Yunyu] Starting backend on port %SERVER_PORT% ...
echo.

if exist "target\backend-0.0.1-SNAPSHOT.jar" (
    java -jar "target\backend-0.0.1-SNAPSHOT.jar"
) else (
    echo [Yunyu] Jar not found. Building first ...
    call mvnw.cmd -q -DskipTests clean package
    java -jar "target\backend-0.0.1-SNAPSHOT.jar"
)

endlocal