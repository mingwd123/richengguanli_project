@echo off
setlocal

rem Load the ignored root .env for local development, if present.
if exist "%~dp0.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0.env") do (
        if not "%%A"=="" set "%%A=%%B"
    )
)

set "JAVA_HOME=D:\JDK(java)"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 17 was not found at "%JAVA_HOME%".
    echo Update JAVA_HOME in start-backend-java17.cmd and try again.
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using Java from "%JAVA_HOME%"
"%JAVA_HOME%\bin\java.exe" -version

cd /d "%~dp0src\backend"
if "%~1"=="" (
    call mvnw.cmd spring-boot:run
) else (
    call mvnw.cmd %*
)
set "EXIT_CODE=%ERRORLEVEL%"

endlocal & exit /b %EXIT_CODE%
