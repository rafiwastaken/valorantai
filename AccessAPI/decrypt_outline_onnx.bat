@echo off
setlocal
cd /d "%~dp0\.."
py -3.12 "_legacy_python\kurex_decrypt_to_onnx.py" %*
exit /b %ERRORLEVEL%
