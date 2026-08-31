@echo off
REM ============================================================
REM  Yunyu Assistant - Frontend Startup (Vite dev server)
REM  URL: http://localhost:5173
REM  Requires Node.js >= 20. Start backend first (start-backend.bat)
REM ============================================================
setlocal
cd /d "%~dp0frontend"

echo.
echo [Yunyu] Starting frontend dev server (http://localhost:5173) ...
echo.

if not exist "node_modules" (
    echo [Yunyu] Dependencies missing. Running npm install ...
    call npm install
)

call npm run dev

endlocal