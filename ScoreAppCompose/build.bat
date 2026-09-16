@echo off
REM ============================================================
REM  乐谱管理 · 命令行打包脚本（Windows）
REM  无需 Android Studio，只需 JDK 17+ 与 Android SDK。
REM
REM  用法：
REM    build.bat                 打包 debug APK
REM    build.bat assembleRelease 打包 release APK
REM    build.bat clean           清理构建产物
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0"

REM ---- 1. 定位 JDK ----
REM 依次尝试：已有 JAVA_HOME → PATH 中的 javac → 常见安装目录

if not defined JAVA_HOME (
    for /f "delims=" %%J in ('where javac 2^>nul') do (
        if not defined JAVA_HOME (
            for %%I in ("%%~dpJ..") do set "JAVA_HOME=%%~fI"
        )
    )
)

if not defined JAVA_HOME (
    for %%P in (
        "%ProgramFiles%\Microsoft"
        "%ProgramFiles%\Eclipse Adoptium"
        "%ProgramFiles%\Java"
        "%ProgramFiles%\Amazon Corretto"
    ) do (
        if not defined JAVA_HOME if exist "%%~fP" (
            for /d %%D in ("%%~fP\*") do (
                if not defined JAVA_HOME if exist "%%~fD\bin\javac.exe" set "JAVA_HOME=%%~fD"
            )
        )
    )
)

if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\javac.exe" (
        set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    )
)

if not defined JAVA_HOME (
    echo [错误] 未找到 JDK。请安装 JDK 17 后设置 JAVA_HOME，例如：
    echo        set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
    exit /b 1
)

REM ---- 2. 定位 Android SDK ----
if not defined ANDROID_HOME if defined LOCALAPPDATA (
    if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME if exist "%USERPROFILE%\AppData\Local\Android\Sdk" (
    set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
)

if not defined ANDROID_HOME (
    echo [错误] 未找到 Android SDK。请设置 ANDROID_HOME，或手动在 local.properties 写入 sdk.dir。
    exit /b 1
)

REM ---- 3. 生成 local.properties ----
REM 反斜杠必须转义，否则 Java Properties 会把 \U 之类当转义序列解析
> local.properties echo sdk.dir=%ANDROID_HOME:\=\\%

REM ---- 4. 执行构建 ----
set "TASK=%~1"
if "%TASK%"=="" set "TASK=assembleDebug"

echo.
echo [构建] gradlew :composeApp:%TASK%
echo        JAVA_HOME    = %JAVA_HOME%
echo        ANDROID_HOME = %ANDROID_HOME%
echo.

call gradlew.bat :composeApp:%TASK% --console=plain
if errorlevel 1 (
    echo.
    echo [失败] 构建未通过，请查看上方日志。
    exit /b 1
)

echo.
echo [完成] 产物位置：
echo        composeApp\build\outputs\apk\debug\composeApp-debug.apk
echo.
echo 安装到已连接设备：
echo        adb install -r composeApp\build\outputs\apk\debug\composeApp-debug.apk

endlocal
