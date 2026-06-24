@echo off
setlocal
cd /d "%~dp0\.."
py -3.12 "_legacy_python\kurex_engine_capture.py" %*
exit /b %ERRORLEVEL%
