@echo off
setlocal
cd /d "%~dp0\.."
if "%~1"=="" (
  set DMP=C:\Users\yukim\Documents\Kurex AI V1.1.exe_2026-06-18_08-11-09.dmp
) else (
  set DMP=%~1
)
py -3.12 "_legacy_python\kurex_dump_onnx_pull.py" "%DMP%"
exit /b %ERRORLEVEL%
