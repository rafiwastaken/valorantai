@echo off
setlocal
cd /d "%~dp0\.."
py -3.12 "_legacy_python\kurex_ort_capture.py" %*
exit /b %ERRORLEVEL%
