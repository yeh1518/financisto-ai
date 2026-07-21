@echo off
rem Headless AVD launcher. Kept as a file on purpose: passing this command line
rem through bash -> ssh -> PowerShell eats the quoting (see DEV-BUILD.md).
set ANDROID_HOME=C:\tools\android-dev\android-sdk
set ANDROID_SDK_ROOT=C:\tools\android-dev\android-sdk
"C:\tools\android-dev\android-sdk\emulator\emulator.exe" -avd fin35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
