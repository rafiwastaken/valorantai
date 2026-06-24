# Kurex AI V1.1 — Recovered Code (Ghidra decompilation)

> ⚠️ This is **auto-generated pseudo-C** produced by decompiling `Kurex AI V1.1.exe`.
> It is **not** the original source. Function/variable names that look like `FUN_140xxxxx`,
> `local_28`, `param_1`, `DAT_140xxxxx` were invented by the decompiler. The logic is
> faithful, but types, names, and comments are reconstructed/approximate.

## What the binary is
- **Native 64-bit C/C++** executable, compiled with **clang (clang-cl) + MSVC runtime**
  (`MSVCP140`, `VCRUNTIME140`, `__CxxFrameHandler`). Not Python, not .NET.
- **No PDB/debug symbols** were shipped, so ~40% of functions kept compiler-generated
  names; the other ~2,596 were recovered from C++ RTTI metadata.

### Detected libraries / stack
| Area | Library (evidence) |
|------|--------------------|
| GUI | **Dear ImGui** (`##aim_point`, `DragDropTarget`, embedded ProggyClean font), **SDL2**, DirectX 11 (`d3d11`, `dxgi`, `dwmapi`) |
| Screen capture | DXGI desktop duplication, Media Foundation (`MF`, `MFReadWrite`), **NDI** (`Processing.NDI.Lib.x64`) |
| AI inference | **TensorRT** (`nvinfer_10`, `.engine`), **ONNX Runtime** + `nvonnxparser`, **OpenCV** (`opencv_world4110`, `cv::dnn`), **CUDA/cuDNN** |
| Networking / licensing | **libcurl** + OpenSSL (`libcrypto-3-x64`), **KeyAuth** licensing (`{"licenseKey":"`, `auth=Bearer %s`, `USE_NEW_SERVER_AUTH`) |
| Input | XInput, `hidapi` (likely KMBox/hardware mouse) |

## Files in this folder
- `Kurex_decompiled.c` — all 6,532 functions decompiled to C (~344k lines, 11 MB).
- `function_index.txt` — `lineNumber  address  functionName` for every function. Use this to jump.
- `strings_clean.txt` — readable embedded strings (5–200 chars).
- `strings_ascii.txt` — raw full strings dump (includes font/PNG noise).

## Key locations (line numbers in `Kurex_decompiled.c`)
| What | Line | Notes |
|------|------|-------|
| KeyAuth `login::login` | ~119440 | logs `key_len`, `USE_NEW_SERVER_AUTH`, `DEV_LOCAL_SERVER`; XOR-decrypts embedded strings (loops with `^ (lVar+0x30)`) |
| License request body | ~121429 | builds `{"licenseKey":"..."}` |
| Server-auth branch | ~119757 | `ENTERING USE_NEW_SERVER_AUTH branch` |
| Signature verification | ~243567 | TensorRT/response signature checks, `MessageBoxA(... "KeyAuth")` |
| Config validation | ~164578 | `Config.game_title is empty. Pass the exact window title.` |
| CRT entry / WinMain | ~370951 | `_get_narrow_winmain_command_line()` startup path |

Tip: open `function_index.txt`, find the address/name you care about, then jump to that line
in `Kurex_decompiled.c`.

## Limitations / how to make it readable
- Original names, comments, and class layouts are gone — recovering meaning is manual work.
- For deeper interactive analysis (renaming, retyping structs, cross-references, call graphs),
  open the project in the **Ghidra GUI** (installed at `C:\Users\yukim\re_tools\ghidra_12.1.2_PUBLIC`):
  run `ghidraRun.bat`, create/open a project, import the exe, and use the Decompiler window.
