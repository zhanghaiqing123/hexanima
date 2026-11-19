#!/bin/bash
# HexAnima 1.9.5-v83 + localapi + upstream-1.8.3 A-batch (build v8)
# Rebuilds eight app classes (MainLora joins: the mod rows of the LoRA library) from source (repo/forkmod) and adds the localapi classes (repo/src).
# Needs in /tmp: apktool.jar, android34.jar (robolectric android-all 14), r8.jar, fork_sp/ (sources of the base
# APK: the fork's own classes, used only for signatures), ha_r/ (apktool d -r of the base APK), ha.keystore.
set -e
WS="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$WS/HexAnima-1.9.5-v73-localapi-v8.apk"
B=/tmp/v8build; PKG=com/hexanima/npu
rm -rf "$B"; mkdir -p "$B/out" "$B/dex" "$B/smali_out"
rm -rf /tmp/ha_p; cp -a /tmp/ha_r /tmp/ha_p

mkdir -p "$B/src/$PKG"
cp "$WS/repo/forkmod/$PKG"/*.java "$B/src/$PKG/"
# the three new classes: the workspace copy ($WS/src) is the one to edit; repo/src is its mirror for git
SRC="$WS/src/$PKG"; [ -f "$SRC/ApiServer.java" ] || SRC="$WS/repo/src/$PKG"
cp "$SRC"/{ApiServer,Backup,Ckpt,LoraSync}.java "$B/src/$PKG/"
cp "$WS/repo/assets/anima.sh" /tmp/ha_p/assets/anima.sh

javac -nowarn -source 8 -target 8 -encoding UTF-8 -implicit:none \
      -sourcepath "$WS/repo/forkmod:/tmp/fork_sp" -classpath /tmp/android34.jar -d "$B/out" "$B/src/$PKG"/*.java
java -cp /tmp/r8.jar com.android.tools.r8.D8 --min-api 29 --release --lib /tmp/android34.jar --output "$B/dex" "$B/out/$PKG"/*.class
baksmali d "$B/dex/classes.dex" -o "$B/smali_out"
python3 - "$B/smali_out" <<'PY'
import os,re,shutil,sys
src=sys.argv[1]+'/com/hexanima/npu'; dst='/tmp/ha_p/smali/com/hexanima/npu'
top=['Main','MainLora','MainRun','MainTools','Settings','Gallery','Viewer','ModelsActivity','ApiServer','Backup','Ckpt','LoraSync','Tr','Timings']
pat=re.compile('^('+'|'.join(top)+r')(\$.*)?\.smali$')
for f in os.listdir(dst):
    if pat.match(f): os.remove(os.path.join(dst,f))
for f in os.listdir(src): shutil.copy(os.path.join(src,f), os.path.join(dst,f))
PY
java -Xmx4g -jar /tmp/apktool.jar b /tmp/ha_p -o "$B/out.apk"
zipalign -p -f 4 "$B/out.apk" "$B/aligned.apk"
apksigner sign --ks /tmp/ha.keystore --ks-pass pass:hexanima --key-pass pass:hexanima --ks-key-alias hexanima \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$B/signed.apk" "$B/aligned.apk"
cp "$B/signed.apk" "$OUT"; ls -la "$OUT"; sha256sum "$OUT"
