#!/bin/bash
# HexAnima 1.9.5-v73 + localapi v29：联想改用独立 PopupWindow
#
#   * 翻译卡片夹在「提示词框」和「反向提示词框」之间，可折叠；
#   * 输入中文 → 防抖 700ms → 整段英文写进提示词框（实时模式下必然要整段替换，旧的先清掉）；
#   * 词典优先（assets/tr_dict.tsv，danbooru 中英对照 4.5 万条，用户表 files/tr_user.tsv 覆盖），
#     词典吃不掉的片段交给 ML Kit 的离线翻译模型（com.google.mlkit:translate 17.0.3）；
#   * ML Kit 模型只能按需从 Google 下载（约 35 MB，一次，之后永久离线），
#     所以翻译引擎是「懒初始化 + 全程 try/catch」：初始化失败只会退化成词典模式，不会让 App 起不来。
#
# 和 v14–v16 的构建差别（重要）：
#   1. 基线是 v16 的 APK（原始基础包已经不在工作区），要 **完整解码**（不能 -r），
#      因为 apktool 重建 resources.arsc 时才会把改过的 AndroidManifest.xml 编译成二进制；
#      `res/values/public.xml` 保留了原资源 ID，重建后 ID 不变（已用 aapt2 dump resources 对比过）。
#   2. 本机的 apktool.jar 自带 x86_64 的 aapt2，在 aarch64 容器里跑会 SIGILL；
#      改用 apktool_arm.jar 里那份 aarch64 的 aapt2（Debian 2.19），
#      它不认识 apktool 2.11 传的 --no-compile-sdk-metadata / --warn-manifest-validation，
#      所以用一个 shim 把这些参数过滤掉再 exec。
#   3. ML Kit 的 jar 不能只放 APK 里，必须把 classes dex 进来（见 /tmp/mlk），
#      资源引用（R.raw.translate_models_metadata、R.xml.rapid_response_client_defaults）在 smali 里改成
#      从 assets 读 / 直接去掉；缺失的 R 类补 0 值 stub（只影响我们从不会走到的 GMS 对话框路径）。
#
# /tmp 里需要（重建方法见 build/mlkit/README.md 的说明）：
#   apktool.jar, apktool_arm.jar, android34.jar, r8.jar, ha.keystore, shim/aapt2
#   mlk/jars/*.jar  （ML Kit + 依赖的 class）
#   mlk/sm2/        （上面这些 jar 一起 d8 出来的 smali）
set -e
WS="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$WS/HexAnima-1.9.5-v73-localapi-v29.apk"
BASE="$WS/HexAnima-1.9.5-v73-localapi-v28.apk"
B=/tmp/v29build; PKG=com/hexanima/npu
MLK=/tmp/mlk
AAPT=/tmp/shim/aapt2
T=/tmp/ha_p17

[ -f "$BASE" ] || { echo "缺少基线 APK: $BASE" >&2; exit 1; }
[ -d "$MLK/sm2" ] || { echo "缺少 ML Kit 的 smali: $MLK/sm2" >&2; exit 1; }
[ -x "$AAPT" ] || { echo "缺少 aapt2 shim: $AAPT" >&2; exit 1; }

rm -rf "$B"; mkdir -p "$B/out" "$B/dex" "$B/smali_out" "$B/src/$PKG"

# ── 1. 基线：完整解码 v16（资源也要解，manifest 才会被重新编译） ───────────────────────────
cp "$BASE" /tmp/v29base.apk
rm -rf "$T"
java -jar /tmp/apktool.jar d -f /tmp/v29base.apk -o "$T"

# ── 2. ML Kit 的 smali 并进来（一次即可；重复构建会覆盖） ────────────────────────────────
cp -a "$MLK/sm2"/* "$T/smali/"

# ── 3. R 类 stub（AAR 里没有 R.class，代码里却 sget 它们） ───────────────────────────────
cd "$T/smali" && python3 - <<'PY'
import os,re,collections
root='.'
refs=collections.defaultdict(dict)
pat=re.compile(r'sget(?:-object|-boolean|-byte|-char|-short|-wide)?\s+[vp]\d+,\s*L([\w/$]+);->([\w$]+):(\[?[IJZBCSFD])')
have=set()
for dp,_,fs in os.walk(root):
    for f in fs:
        if f.endswith('.smali'): have.add(os.path.join(dp,f)[len(root)+1:-6])
for dp,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.smali'): continue
        b=open(os.path.join(dp,f),encoding='utf-8',errors='ignore').read()
        for m in pat.finditer(b):
            cls,field,typ=m.group(1),m.group(2),m.group(3)
            if '/R$' in cls and cls not in have: refs[cls][field]=typ
for cls,fields in refs.items():
    lines=['.class public final L%s;'%cls,'.super Ljava/lang/Object;','','# stub: AAR 不提供 R.class','']
    clinit=[]
    for fld,typ in sorted(fields.items()):
        if typ.startswith('['):
            lines.append('.field public static final %s:%s'%(fld,typ))
            clinit += ['    const/4 v0, 0x0','    new-array v0, v0, %s'%typ,'    sput-object v0, L%s;->%s:%s'%(cls,fld,typ)]
        else:
            lines.append('.field public static final %s:%s = 0x0'%(fld,typ))
    if clinit:
        lines += ['', '.method static constructor <clinit>()V', '    .registers 1'] + clinit + ['    return-void', '.end method']
    open(cls+'.smali','w',encoding='utf-8').write('\n'.join(lines)+'\n')
print('R stubs:',len(refs))
PY

# ── 4. 两处资源引用改成不依赖 resources.arsc ─────────────────────────────────────────────
python3 - <<'PY'
p='/tmp/ha_p17/smali/com/google/mlkit/nl/translate/internal/zzt.smali'
s=open(p,encoding='utf-8').read()
s=s.replace("""    .line 1
    sget v0, Lcom/google/mlkit/nl/translate/R$raw;->translate_models_metadata:I

    sput v0, Lcom/google/mlkit/nl/translate/internal/zzt;->zza:I""",
"""    const/4 v0, 0x0

    sput v0, Lcom/google/mlkit/nl/translate/internal/zzt;->zza:I""")
s=s.replace("""    invoke-virtual {p1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object p1

    sget v1, Lcom/google/mlkit/nl/translate/internal/zzt;->zza:I

    invoke-virtual {p1, v1}, Landroid/content/res/Resources;->openRawResource(I)Ljava/io/InputStream;

    move-result-object p1""",
"""    invoke-virtual {p1}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;

    move-result-object p1

    const-string v1, "translate_models_metadata.json"

    invoke-virtual {p1, v1}, Landroid/content/res/AssetManager;->open(Ljava/lang/String;)Ljava/io/InputStream;

    move-result-object p1""")
open(p,'w',encoding='utf-8').write(s)

p='/tmp/ha_p17/smali/com/google/mlkit/nl/translate/internal/zzad.smali'
s=open(p,encoding='utf-8').read()
s=s.replace("""    sget p0, Lcom/google/mlkit/nl/translate/R$xml;->rapid_response_client_defaults:I

    invoke-virtual {p1, p0}, Lcom/google/android/gms/internal/mlkit_translate/zzvy;->zzi(I)V""",
"    # rapid-response 的默认 XML 没有随包发出：这条注册去掉")
open(p,'w',encoding='utf-8').write(s)
print('mlkit resource refs patched')
PY

# ── 5. assets / native lib / manifest ────────────────────────────────────────────────────
cp /tmp/tr_dict.tsv "$T/assets/tr_dict.tsv"
cp /tmp/mlkit/res/raw/translate_models_metadata.json "$T/assets/"
unzip -o -q /tmp/mlk/deps2/com.google.mlkit-translate-17.0.3.aar jni/arm64-v8a/libtranslate_jni.so -d /tmp/mlk/so
cp /tmp/mlk/so/jni/arm64-v8a/libtranslate_jni.so "$T/lib/arm64-v8a/"
# Hy-MT 的常驻翻译进程（llama.cpp + STQ 内核，交叉编译见 work/android-toolchain/）
cp /tmp/hymt/hymt-serve-android "$T/lib/arm64-v8a/libhymt.so"

python3 - <<'PY'
p='/tmp/ha_p17/AndroidManifest.xml'
s=open(p,encoding='utf-8').read()
if 'MlKitComponentDiscoveryService' not in s:
    s=s.replace('    <application ', '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>\n    <application ', 1)
    add = '''        <!-- mod v25: ML Kit 离线翻译。MlKitInitProvider 故意不注册：初始化交给 TrMlKit 懒加载，
             失败时只把翻译降级成词典模式，不会在启动时把 App 拖死 -->
        <service android:name="com.google.mlkit.common.internal.MlKitComponentDiscoveryService" android:exported="false" android:directBootAware="true">
            <meta-data android:name="com.google.firebase.components:com.google.mlkit.common.internal.CommonComponentRegistrar" android:value="com.google.firebase.components.ComponentRegistrar"/>
            <meta-data android:name="com.google.firebase.components:com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar" android:value="com.google.firebase.components.ComponentRegistrar"/>
        </service>
        <service android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService" android:exported="false" android:permission="android.permission.BIND_JOB_SERVICE"/>
        <receiver android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver" android:exported="false"/>
        <service android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" android:exported="false">
            <meta-data android:name="backend:com.google.android.datatransport.cct.CctBackendFactory" android:value="cct"/>
        </service>
        <activity android:name="com.google.android.gms.common.api.GoogleApiActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false"/>
'''
    s=s.replace('    </application>', add+'    </application>')
    open(p,'w',encoding='utf-8').write(s)
print('manifest ok')
PY

# ── 6. 编译我们自己的类（22 个）→ dex → smali → 覆盖 ─────────────────────────────────────
cp "$WS/repo/forkmod/$PKG"/*.java "$B/src/$PKG/"
SRC="$WS/repo/src/$PKG"   # v26 起只用这一份，免得工作区的 src/ 副本与仓库漂移
cp "$SRC"/{ApiServer,Backup,Ckpt,LoraSync}.java "$B/src/$PKG/"

javac -nowarn -source 8 -target 8 -encoding UTF-8 -implicit:none \
      -sourcepath /tmp/fork_sp -classpath "/tmp/android34.jar:/tmp/mlk/jars/*" -d "$B/out" "$B/src/$PKG"/*.java
java -cp /tmp/r8.jar com.android.tools.r8.D8 --min-api 29 --release --lib /tmp/android34.jar \
     --output "$B/dex" "$B/out/$PKG"/*.class
baksmali d "$B/dex/classes.dex" -o "$B/smali_out"
python3 - "$B/smali_out" <<'PY'
import os,re,shutil,sys
src=sys.argv[1]+'/com/hexanima/npu'; dst='/tmp/ha_p17/smali/com/hexanima/npu'
top=['Main','MainEdit','MainLora','MainRun','MainTools','Settings','Gallery','Viewer','ModelsActivity','ApiServer','Backup','Ckpt','LoraSync','Tr','Timings','Compat','Mirror','TrDict','TrEngine','TrMlKit','MainTr','TrLlm','TrCloud','TrTpl','TrNav','TrCsv']
pat=re.compile('^('+'|'.join(top)+r')(\$.*)?\.smali$')
for f in os.listdir(dst):
    if pat.match(f): os.remove(os.path.join(dst,f))
for f in os.listdir(src): shutil.copy(os.path.join(src,f), os.path.join(dst,f))
print('classes replaced')
PY

# ── 7. 打包 ───────────────────────────────────────────────────────────────────────────────
java -Xmx6g -jar /tmp/apktool.jar b "$T" --aapt "$AAPT" -o "$B/out.apk"
zipalign -p -f 4 "$B/out.apk" "$B/aligned.apk"
apksigner sign --ks /tmp/ha.keystore --ks-pass pass:hexanima --key-pass pass:hexanima --ks-key-alias hexanima \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$B/signed.apk" "$B/aligned.apk"
cp "$B/signed.apk" "$OUT"; ls -la "$OUT"; sha256sum "$OUT"
