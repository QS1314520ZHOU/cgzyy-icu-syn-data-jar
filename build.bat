@echo off
echo [build] Killing Java processes...
taskkill /F /IM java.exe 2>nul
timeout /t 2 /nobreak >nul

echo [build] Cleaning target...
rd /s /q target 2>nul
timeout /t 1 /nobreak >nul

echo [build] Packaging...
F:\maven\apache-maven-3.6.0-bin\apache-maven-3.6.0\bin\mvn clean package -DskipTests -q

if %errorlevel% equ 0 (
    echo [build] SUCCESS - target\cgzyy-icu-syn-data.jar
) else (
    echo [build] FAILED
)
