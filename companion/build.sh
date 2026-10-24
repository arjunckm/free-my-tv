#!/usr/bin/env bash
# Builds FreeMyTV-Remote.apk (the phone app) without Gradle, like ../build.sh. Same tools, same signing key.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }
[ -f freemytv.keystore ] || { echo "freemytv.keystore not found: build the TV app first (./build.sh)."; exit 1; }

CPSEP=:; BAT=; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";"; BAT=.bat ;; esac
WP() { if [ -n "$BAT" ] && command -v cygpath >/dev/null; then cygpath -m "$1"; else printf "%s" "$1"; fi; }

OUT=out/companion
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"
"$BT/aapt2" compile --dir companion/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" --manifest companion/AndroidManifest.xml --java "$OUT/gen" "$OUT/res.zip" \
  --min-sdk-version 26 --target-sdk-version 33
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$(WP "$AJ")$CPSEP$(WP "$BT/core-lambda-stubs.jar")" -classpath "$(WP "$AJ")" \
  -encoding UTF-8 -d "$OUT/classes" $(find companion/src shared "$OUT/gen" -name '*.java')
"$BT/d8$BAT" --release --min-api 26 --lib "$AJ" --output "$OUT" $(find "$OUT/classes" -name '*.class')
if command -v zip >/dev/null; then (cd "$OUT" && zip -qj base.apk classes.dex)
else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f "$OUT/base.apk" -C "$OUT" classes.dex; fi
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner$BAT" sign --ks freemytv.keystore --ks-pass pass:freemytv --key-pass pass:freemytv --out FreeMyTV-Remote.apk "$OUT/aligned.apk"
echo "Built FreeMyTV-Remote.apk ($(du -h FreeMyTV-Remote.apk | cut -f1)). Install on the phone: adb install -r FreeMyTV-Remote.apk"
