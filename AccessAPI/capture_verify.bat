@echo off
setlocal
cd /d "%~dp0\.."
py -3.12 "_legacy_python\kurex_capture_verify.py" %*
exit /b %ERRORLEVEL%
