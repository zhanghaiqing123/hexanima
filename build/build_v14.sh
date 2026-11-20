#!/bin/bash
# HexAnima 1.9.5-v73 + localapi v14: hires fix redo (a picture from the album or the History runs the hires second pass),
# on top of v13 (QNN 2.51 / V81 skels, mirror default hf-mirror.com for Models.open AND ParDl.get, com<->net fallback).
# Rebuilds nine app classes (MainEdit joins: the EXTRAS card holds the new hires row) from source (repo/forkmod) and adds the localapi classes (repo/src).
# Needs in /tmp: apktool.jar, android34.jar (robolectric android-all 14), r8.jar, fork_sp/ (sources of the base
# APK: the fork's own classes, used only for signatures), ha_r/ (apktool d -r of the base APK), ha.keystore.
# The compile happens under /tmp because the workspace path is not ASCII (Java's file.encoding turns it into "?").
set -e
WS="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$WS/HexAnima-1.9.5-v73-localapi-v14.apk"
B=/tmp/v14build; PKG=com/hexanima/npu
rm -rf "$B"; mkdir -p "$B/out" "$B/dex" "$B/smali_out" "$B/src/$PKG"
rm -rf /tmp/ha_p; cp -a /tmp/ha_r /tmp/ha_p

cp "$WS/repo/forkmod/$PKG"/*.java "$B/src/$PKG/"
# the three new classes: the workspace copy ($WS/src) is the one to edit; repo/src is its mirror for git
SRC="$WS/src/$PKG"; [ -f "$SRC/ApiServer.java" ] || SRC="$WS/repo/src/$PKG"
cp "$SRC"/{ApiServer,Backup,Ckpt,LoraSync}.java "$B/src/$PKG/"

cp "$WS/repo/assets/anima.sh" /tmp/ha_p/assets/anima.sh
# mod: the QNN 2.51 stack adds the V79 / V81 skeletons (8 Elite / 8 Elite Gen 5 run the same v73 model packs). The V69 ones go: QNN 2.51
# no longer ships them, so an 8 Gen 1 / 8+ Gen 1 phone loses support (the trade the user accepted).
V81="$WS/work/v81libs"
for f in libQnnHtp.so libQnnSystem.so libQnnHtpV73Skel.so libQnnHtpV73Stub.so libQnnHtpV75Skel.so libQnnHtpV75Stub.so libQnnHtpV79Skel.so libQnnHtpV79Stub.so libQnnHtpV81Skel.so libQnnHtpV81Stub.so; do
  [ -f "$V81/$f" ] || { echo "missing $V81/$f" >&2; exit 1; }
  cp -f "$V81/$f" /tmp/ha_p/lib/arm64-v8a/
done
rm -f /tmp/ha_p/lib/arm64-v8a/libQnnHtpV69Skel.so /tmp/ha_p/lib/arm64-v8a/libQnnHtpV69Stub.so

# only ASCII paths reach javac: the sources are compiled under /tmp, the fork's own classes come from /tmp/fork_sp
javac -nowarn -source 8 -target 8 -encoding UTF-8 -implicit:none \
      -sourcepath /tmp/fork_sp -classpath /tmp/android34.jar -d "$B/out" "$B/src/$PKG"/*.java
java -cp /tmp/r8.jar com.android.tools.r8.D8 --min-api 29 --release --lib /tmp/android34.jar --output "$B/dex" "$B/out/$PKG"/*.class
baksmali d "$B/dex/classes.dex" -o "$B/smali_out"
python3 - "$B/smali_out" <<'PY'
import os,re,shutil,sys
src=sys.argv[1]+'/com/hexanima/npu'; dst='/tmp/ha_p/smali/com/hexanima/npu'
top=['Main','MainEdit','MainLora','MainRun','MainTools','Settings','Gallery','Viewer','ModelsActivity','ApiServer','Backup','Ckpt','LoraSync','Tr','Timings','Compat','Mirror']
pat=re.compile('^('+'|'.join(top)+r')(\$.*)?\.smali$')
for f in os.listdir(dst):
    if pat.match(f): os.remove(os.path.join(dst,f))
for f in os.listdir(src): shutil.copy(os.path.join(src,f), os.path.join(dst,f))
PY
# mod: every download goes through the chosen mirror. Models.open() covers the catalog, the manifests and the small files; ParDl.get() is
# the parallel big-file downloader and opens its own connections (the big weights go through it). Both get one Mirror.fix(url) call injected.
python3 - <<'PY'
def patch(path, needle, first):
    s = open(path, encoding='utf-8').read()
    if 'Mirror;->fix' in s:
        return
    i = s.index(needle)
    k = s.index(first, i)
    ins = ('    move-object/from16 v0, p0\n\n'
           '    invoke-static {v0}, Lcom/hexanima/npu/Mirror;->fix(Ljava/lang/String;)Ljava/lang/String;\n\n'
           '    move-result-object v0\n\n'
           '    move-object/from16 p0, v0\n\n')
    open(path, 'w', encoding='utf-8').write(s[:k] + ins + s[k:])
patch('/tmp/ha_p/smali/com/hexanima/npu/Models.smali',
      '.method static open(Ljava/lang/String;J)Ljava/net/HttpURLConnection;',
      '    new-instance v0, Ljava/net/URL;')
patch('/tmp/ha_p/smali/com/hexanima/npu/ParDl.smali',
      '.method static get(Ljava/lang/String;Ljava/io/File;JLcom/hexanima/npu/ParDl$Prog;Ljava/util/concurrent/atomic/AtomicBoolean;)Z',
      '    .line 37')
print('download entry points patched')
PY

java -Xmx4g -jar /tmp/apktool.jar b /tmp/ha_p -o "$B/out.apk"
zipalign -p -f 4 "$B/out.apk" "$B/aligned.apk"
apksigner sign --ks /tmp/ha.keystore --ks-pass pass:hexanima --key-pass pass:hexanima --ks-key-alias hexanima \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$B/signed.apk" "$B/aligned.apk"
cp "$B/signed.apk" "$OUT"; ls -la "$OUT"; sha256sum "$OUT"
