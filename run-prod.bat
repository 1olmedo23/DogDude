@echo off
setlocal

REM --- Always run from the folder where this .bat lives
pushd "%~dp0"

REM --- Find the packaged Boot jar under target (ignore the .original file)
set "JAR="
for /f "delims=" %%F in ('dir /b /a:-d "target\*.jar" ^| findstr /i /v ".original"') do (
  set "JAR=target\%%F"
  goto :found
)

echo [ERROR] No runnable jar found under: %cd%\target
echo         Build it first (Maven -> Lifecycle -> package).
popd
exit /b 1

:found
echo Starting DogDaycare (prod) using "%JAR%"...

REM --- DB env vars (edit these to your local prod DB)
set "SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dogdaycare"
set "SPRING_DATASOURCE_USERNAME=postgres"
set "SPRING_DATASOURCE_PASSWORD=@1Diamant3Mor@le5"

REM --- Guard: stop if password placeholder not changed
if "%SPRING_DATASOURCE_PASSWORD%"=="YOUR_REAL_PASSWORD" (
  echo [ERROR] Update SPRING_DATASOURCE_PASSWORD in run-prod.bat before running.
  popd
  exit /b 1
)

REM --- Optional: pin Java 17 if PATH ever drifts
REM set "JAVA_HOME=C:\Users\Juan\.jdks\ms-17.0.15"
REM set "PATH=%JAVA_HOME%\bin;%PATH%"

REM --- Show what we’re using (mask password)
echo Using URL: %SPRING_DATASOURCE_URL%
echo Using user: %SPRING_DATASOURCE_USERNAME%

REM --- Run with prod profile
java -jar "%JAR%" --spring.profiles.active=prod
set "EXITCODE=%ERRORLEVEL%"

popd
endlocal & exit /b %EXITCODE%
