#!/usr/bin/env bash
# 构建优化助手 APK。不依赖 Gradle，使用 aapt2 + javac + d8 + apksigner 六步完成。
#
# 依赖：
#   JDK 8 或更高版本，需要 javac、jar、keytool
#   Android SDK build-tools 34.0.0 与 platform android-34
#
# 通过环境变量指定工具位置，未设置时尝试常见默认路径：
#   ANDROID_SDK_ROOT 或 ANDROID_HOME   Android SDK 根目录
#   BUILD_TOOLS                       build-tools 目录，默认取 34.0.0
#   JAVA_HOME                         JDK 根目录，未设置时使用 PATH 中的 javac

set -e

cd "$(dirname "$0")"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat"; WINDOWS=1 ;;
  *)                    EXE="";     BAT="";     WINDOWS=0 ;;
esac

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "找不到 android.jar，请设置 ANDROID_SDK_ROOT 指向 Android SDK 根目录" >&2
  echo "当前尝试的路径：$ANDROID_JAR" >&2
  exit 1
fi

if [ -z "$JAVA_HOME" ]; then
  JAVAC_PATH="$(command -v javac || true)"
  if [ -z "$JAVAC_PATH" ]; then
    echo "找不到 javac，请设置 JAVA_HOME 或者把 JDK 加入 PATH" >&2
    exit 1
  fi
  JAVA_HOME="$(dirname "$(dirname "$JAVAC_PATH")")"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# d8 与 apksigner 是批处理文件，需要 Windows 风格的 JAVA_HOME
if [ "$WINDOWS" = "1" ] && command -v cygpath >/dev/null 2>&1; then
  export JAVA_HOME="$(cygpath -w "$JAVA_HOME")"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

APK_NAME="${APK_NAME:-lx04-doctor.apk}"

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "第 1 步，编译资源"
"$BT/aapt2$EXE" compile --dir res -o build/res.zip

echo "第 2 步，链接资源与清单"
"$BT/aapt2$EXE" link \
  -o build/base.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 21 --target-sdk-version 27 \
  build/res.zip

echo "第 3 步，编译 Java 源码"
find src build/gen -name "*.java" > build/sources.txt
"$JAVA_HOME/bin/javac$EXE" -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -d build/classes @build/sources.txt

echo "第 4 步，生成 dex"
find build/classes -name "*.class" > build/classes.txt
"$BT/d8$BAT" --lib "$ANDROID_JAR" --min-api 21 --output build/dex @build/classes.txt

echo "第 5 步，打包与对齐"
cp build/base.apk build/unsigned.apk
( cd build/dex && "$JAVA_HOME/bin/jar$EXE" uf ../unsigned.apk classes.dex )
"$BT/zipalign$EXE" -f 4 build/unsigned.apk build/aligned.apk

echo "第 6 步，签名"
# 首次构建会自动生成密钥库。正式发布前请替换为自己的密钥并妥善保管，
# 密钥库丢失之后无法再对已安装的应用做覆盖升级。
if [ ! -f keystore.jks ]; then
  echo "未找到 keystore.jks，自动生成一个测试用密钥库"
  "$JAVA_HOME/bin/keytool$EXE" -genkeypair -keystore keystore.jks -alias lx04 \
    -storepass lx04pass -keypass lx04pass -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=LX04 Doctor,O=LX04,C=CN" 2>/dev/null
fi
"$BT/apksigner$BAT" sign --ks keystore.jks --ks-pass pass:lx04pass --key-pass pass:lx04pass \
  --min-sdk-version 21 --out "$APK_NAME" build/aligned.apk
"$BT/apksigner$BAT" verify "$APK_NAME"

ls -lh "$APK_NAME"
