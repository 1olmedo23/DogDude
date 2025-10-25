@echo off
setlocal

REM --- Always run from the folder where this .bat lives
pushd "%~dp0"

REM --- Find the packaged jar under target (ignore the .original file)
set "JAR="
for /f "delims=" %%F in ('dir /b /a:-d "target\*.jar" ^| findstr /i /v ".original"') do (
  set "JAR=target\%%F"
  goto :found
)

echo [ERROR] No runnable jar found under: %cd%\target
echo         Build it first (in IntelliJ: Maven -> Lifecycle -> package).
popd
exit /b 1

:found
echo Starting DogDaycare (prod) using "%JAR%"...

REM --- DB env vars (edit if needed)
set "SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dogdaycare"
set "SPRING_DATASOURCE_USERNAME=postgres"
set "SPRING_DATASOURCE_PASSWORD=YOUR_REAL_PASSWORD"

REM --- Optional: pin Java 17 if PATH ever drifts
REM set "JAVA_HOME=C:\Users\Juan\.jdks\ms-17.0.15"
REM set "PATH=%JAVA_HOME%\bin;%PATH%"

REM --- Run
java -jar "%JAR%" --spring.profiles.active=prod
set "EXITCODE=%ERRORLEVEL%"

popd
endlocal & exit /b %EXITCODE%
