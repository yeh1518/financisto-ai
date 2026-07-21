@echo off
rem Windowed AVD launcher -- run this from YOUR OWN interactive PowerShell,
rem NOT via mesh-ssh. A window only appears on the interactive desktop; an ssh /
rem session-0 launch would draw it on an invisible desktop (see DEV-BUILD.md).
rem
rem Use this when you want to watch tests or type the API key directly.
rem For headless CI-style runs (Claude via mesh-ssh) use start-emu.cmd instead.
rem
rem -gpu auto uses the host GPU: proper rendering AND screencap works (the
rem swiftshader black-screen on MainActivity/TransactionActivity goes away here).
set ANDROID_HOME=C:\tools\android-dev\android-sdk
set ANDROID_SDK_ROOT=C:\tools\android-dev\android-sdk
"C:\tools\android-dev\android-sdk\emulator\emulator.exe" -avd fin35 -gpu auto -no-boot-anim
