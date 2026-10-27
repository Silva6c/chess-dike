@echo off
rem Build gate for chess-dike: unit tests + release APKs (both flavors).
rem Equivalent of updateApk.sh (Git Bash). Keep this file ASCII-only:
rem cmd uses codepage 936 which garbles UTF-8 comments (same lesson as
rem cabinet repo's package-portable.bat). Chinese docs: updateApk.sh / AGENTS.md (local).

setlocal
cd /d "%~dp0.."

if not defined JAVA_HOME set "JAVA_HOME=D:\Android\jdk-17.0.20.1+1"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=D:\Android\gradle-home"

echo [1/3] unit tests ...
call gradlew.bat :app:testArmv8-dotprodDebugUnitTest || goto :fail
echo [2/3] assemble release APKs ...
call gradlew.bat assembleArmv8Release assembleArmv8-dotprodRelease || goto :fail
echo [3/3] outputs:
dir /b app\build\outputs\apk\armv8\release\*.apk
dir /b app\build\outputs\apk\armv8-dotprod\release\*.apk
echo BUILD OK
exit /b 0

:fail
echo BUILD FAILED
exit /b 1
