@echo off
setlocal
cd /d "%~dp0\.."
py -3.12 "_legacy_python\kurex_live_onnx_pull.py" %*
if exist "models\val_outline.onnx" (
  echo.
  echo OK: models\val_outline.onnx ready for KurexAim
) else (
  echo.
  echo Failed - memory scan did not find ONNX.
  echo Prefer: tools\capture_kurex_onnx.bat install  ^(then load OUTLINE in Kurex^)
  echo Or: Admin CMD + Kurex on OUTLINE in-game, then retry this script.
)
exit /b %ERRORLEVEL%
