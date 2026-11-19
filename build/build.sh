#!/bin/bash
# HexAnima local-API patch: compile ApiServer.java, inject it into a patched
# apktool decode, rebuild, align and sign.
#
# Usage:
#   1) apktool d -r HexAnima-1.9.5-v73.apk -o /tmp/ha_r
#   2) cp -a /tmp/ha_r /tmp/ha_p && python3 build/patch_hooks.py /tmp/ha_p
#   3) bash build/build.sh
#
# Everything can be overridden with env vars:
#   DEC       patched decode dir that already has the smali hooks  (default /tmp/ha_p)
#   OUT       output apk                                            (default <repo>/dist/HexAnima-localapi.apk)
#   WORK      scratch dir for javac/d8/baksmali                     (default /tmp/hexanima-api-build)
#   APKTOOL   apktool 2.11.1 jar                                    (default /tmp/apktool.jar)
#   ANDROID_JAR  android.jar stubs for javac                        (default /tmp/android25.jar)
#   R8JAR     r8.jar containing d8                                  (default /tmp/r8.jar)
#   KEYSTORE / KS_PASS / KS_ALIAS   signing key                     (default <repo>/keystore/hexanima.jks, hexanima, hexanima)
set -e
set -o pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/src/com/hexanima/npu/ApiServer.java"
DEC="${DEC:-/tmp/ha_p}"
OUT="${OUT:-$ROOT/dist/HexAnima-localapi.apk}"
WORK="${WORK:-/tmp/hexanima-api-build}"
APKTOOL="${APKTOOL:-/tmp/apktool.jar}"
ANDROID_JAR="${ANDROID_JAR:-/tmp/android25.jar}"
R8JAR="${R8JAR:-/tmp/r8.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/hexanima.jks}"
KS_PASS="${KS_PASS:-hexanima}"
KS_ALIAS="${KS_ALIAS:-hexanima}"

for f in "$APKTOOL" "$ANDROID_JAR" "$R8JAR" "$KEYSTORE"; do
    [ -e "$f" ] || { echo "missing: $f (see the header of this script)"; exit 1; }
done
[ -d "$DEC/smali/com/hexanima/npu" ] || { echo "not a patched decode: $DEC"; exit 1; }

mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/smali_out" "$(dirname "$OUT")"
WORK="$(cd "$WORK" && pwd)"
rm -rf "$WORK/classes"/* "$WORK/dex"/* "$WORK/smali_out"/*
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/smali_out"

# javac chokes on non-ASCII paths, so compile from a copy inside the scratch dir
mkdir -p "$WORK/src/com/hexanima/npu"
cp "$SRC" "$WORK/src/com/hexanima/npu/ApiServer.java"

echo "[1/6] javac"
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
      -d "$WORK/classes" "$WORK/src/com/hexanima/npu/ApiServer.java"

echo "[2/6] d8"
java -cp "$R8JAR" com.android.tools.r8.D8 --min-api 29 --release \
     --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/classes/com/hexanima/npu/"*.class

echo "[3/6] baksmali"
baksmali d "$WORK/dex/classes.dex" -o "$WORK/smali_out"

echo "[4/6] install smali + build apk"
rm -f "$DEC/smali/com/hexanima/npu/ApiServer"*.smali
cp "$WORK/smali_out/com/hexanima/npu/ApiServer"*.smali "$DEC/smali/com/hexanima/npu/"
java -Xmx4g -jar "$APKTOOL" b "$DEC" -o "$WORK/out.apk"

echo "[5/6] zipalign"
rm -f "$WORK/out_aligned.apk" "$WORK/out_signed.apk"
zipalign -p -f 4 "$WORK/out.apk" "$WORK/out_aligned.apk"

echo "[6/6] sign"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --v1-signing-enabled true --v2-signing-enabled true \
    --v3-signing-enabled true --out "$WORK/out_signed.apk" "$WORK/out_aligned.apk"

apksigner verify --verbose "$WORK/out_signed.apk" | sed -n "1,4p"
cp "$WORK/out_signed.apk" "$OUT"
ls -la "$OUT"
sha256sum "$OUT"
echo "done"
