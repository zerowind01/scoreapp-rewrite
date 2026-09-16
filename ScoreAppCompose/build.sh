#!/usr/bin/env bash
# ============================================================
#  乐谱管理 · 命令行打包脚本（macOS / Linux / Git Bash）
#  无需 Android Studio，只需 JDK 17+ 与 Android SDK。
#
#  用法：
#    ./build.sh                 打包 debug APK
#    ./build.sh assembleRelease 打包 release APK
#    ./build.sh clean           清理构建产物
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# ---- 1. 定位 JDK ----
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in \
    "/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot" \
    "/c/Program Files/Eclipse Adoptium/jdk-17" \
    "/c/Program Files/Java/jdk-17" \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"; do
    if [ -d "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "[错误] 未找到 JAVA_HOME，请安装 JDK 17 并设置 JAVA_HOME。" >&2
  exit 1
fi

# ---- 2. 定位 Android SDK ----
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in \
    "$HOME/AppData/Local/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk"; do
    if [ -d "$candidate" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "[错误] 未找到 Android SDK，请设置 ANDROID_HOME 或在 local.properties 中指定 sdk.dir。" >&2
  exit 1
fi

# ---- 3. 生成 local.properties ----
# Git Bash 下 ANDROID_HOME 常是 /c/Users/... 形式，先归一化成 C:\Users\...，
# 再按 Java Properties 规则转义（反斜杠与冒号），否则 Gradle 会解析出错误的盘符。
to_win_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$p"
  elif [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
    # 只在 Windows 的 MSYS/Cygwin 下把 /c/Users/... 还原成 C:\Users\...
    # macOS / Linux 的 /Users/... 是真实路径，不能当成盘符处理
    if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
      printf '%s:\\%s\n' "${BASH_REMATCH[1]^^}" "${BASH_REMATCH[2]//\//\\}"
    else
      printf '%s\n' "$p"
    fi
  else
    printf '%s\n' "$p"
  fi
}

escape_props() {
  printf '%s' "$1" | sed 's|\\|\\\\|g; s|:|\\:|g'
}

printf 'sdk.dir=%s\n' "$(escape_props "$(to_win_path "$ANDROID_HOME")")" > local.properties

TASK="${1:-assembleDebug}"

echo
echo "[构建] ./gradlew :composeApp:$TASK"
echo "       JAVA_HOME    = $JAVA_HOME"
echo "       ANDROID_HOME = $ANDROID_HOME"
echo

./gradlew ":composeApp:$TASK" --console=plain

echo
echo "[完成] 产物位置：composeApp/build/outputs/apk/debug/composeApp-debug.apk"
echo
echo "安装到已连接设备："
echo "       adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk"
