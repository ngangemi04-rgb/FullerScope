@echo off
rem Starts the FullerScope Installer window.
rem
rem   -STA                      Windows Forms needs a single-threaded apartment
rem   -ExecutionPolicy Bypass   for this one process only; a script unzipped from a
rem                             download is otherwise refused on most PCs
rem   -WindowStyle Hidden       the installer has its own window and log

start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0FullerScope Installer.ps1"
