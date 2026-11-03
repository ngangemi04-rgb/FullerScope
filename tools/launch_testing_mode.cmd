@echo off
rem ---------------------------------------------------------------------------
rem Launcher for the PC testing mode, used by the desktop shortcut.
rem
rem Why a .cmd rather than pointing the shortcut straight at python.exe:
rem
rem   * the working directory has to be the project root, so tools/ imports and
rem     the default captures/ output path resolve;
rem   * python.exe here is the Store alias in WindowsApps, whose path moves when
rem     Python updates - resolving it from PATH at launch survives that;
rem   * a console window is deliberate. testing_mode.py prints the path of every
rem     recording it writes, and prints why it gave up when no board is found.
rem     pythonw.exe would swallow both, and "nothing happened" is the worst
rem     possible response to an unplugged board.
rem ---------------------------------------------------------------------------

cd /d "%~dp0.."

title StethoScope testing mode
python "tools\testing_mode.py" %*
set EXITCODE=%ERRORLEVEL%

if not "%EXITCODE%"=="0" (
    echo.
    echo ---------------------------------------------------------------
    echo Testing mode exited with code %EXITCODE%.
    echo   2 = no board found ^(check the USB cable, then run again^)
    echo   1 = something else went wrong; the message is above
    echo ---------------------------------------------------------------
    echo Press any key to close this window.
    pause >nul
)
exit /b %EXITCODE%
