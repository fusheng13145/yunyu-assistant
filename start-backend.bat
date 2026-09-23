@echo off
REM ============================================================
REM  Yunyu Assistant - Backend Startup (local dev)
REM  Health check:  http://localhost:8080/actuator/health
REM  Requires JDK21 & MySQL8 (schema initialized via index.sql)
REM ============================================================
setlocal
cd /d "%~dp0backend"

REM ---- Database (override via env vars if provided) ----
if not defined DB_HOST     set "DB_HOST=localhost"
if not defined DB_PORT     set "DB_PORT=3306"
if not defined DB_NAME     set "DB_NAME=yunyu_assistant"
if not defined DB_USER     set "DB_USER=yunyu_app"
if not defined DB_PASSWORD set "DB_PASSWORD=yunyu_dev_2026"

REM ---- JWT (override via env vars if provided) ----
if not defined JWT_SECRET     set "JWT_SECRET=dev-jwt-secret-key-0123456789abcdef0123456789abcdef"
if not defined JWT_EXPIRATION set "JWT_EXPIRATION=86400000"
if not defined SERVER_PORT    set "SERVER_PORT=8080"

REM ---- LLM (v2.29: no longer defaulted in application.yaml; dev placeholder keeps local startup working) ----
REM ---- NOTE: .env is NOT auto-loaded by the JVM; export vars yourself (see README "配置说明") ----
if not defined OPENAI_API_KEY   set "OPENAI_API_KEY=dev-placeholder-llm-key"
if not defined MAPPER_LOG_LEVEL set "MAPPER_LOG_LEVEL=DEBUG"

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