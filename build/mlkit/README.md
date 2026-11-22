# ML Kit 离线翻译的依赖闭包（v17 用）

`com.google.mlkit:translate:17.0.3` 不是单个 AAR：它拖 `mlkit-common`、`play-services-tasks/basement/base`、
`firebase-components/encoders`、`datatransport-*`、`okhttp/okio`、`androidx.core` 等 24 个 artifact。
这里记录怎么把那堆东西变成能塞进 apktool 工程的一份 smali（`/tmp/mlk/sm2`）。

## 1. 下载（24 个 artifact）

见 `get.py`（Google Maven + Maven Central，AAR 优先，退回 JAR）。
`translate` 的 AAR 有 27 MB，其中 `jni/arm64-v8a/libtranslate_jni.so` 16.4 MB 是引擎本体，
classes.jar 只有 563 KB。

## 2. 解出 class → 一起 dex

```bash
# AAR → classes.jar
python3 extract.py            # 或手工 unzip -p x.aar classes.jar
java -cp /tmp/r8.jar com.android.tools.r8.D8 --min-api 29 --release \
     --lib /tmp/android34.jar --output /tmp/mlk/out2 /tmp/mlk/jars/*.jar
baksmali d /tmp/mlk/out2/classes.dex -o /tmp/mlk/sm2
```

结果：2.7 MB dex、21,965 个方法。主 APK 本来是 4,225 个方法，合起来 26,410，离 64K 很远
（真超了也不怕：minSdk 29 原生支持多 dex）。

## 3. 三个必须手动处理的地方（否则运行时会崩）

1. `com/google/mlkit/nl/translate/internal/zzt.smali` 用 `R.raw.translate_models_metadata` 读模型目录，
   但 AAR 不提供 `R.class`。v17 把它改成 `AssetManager.open("translate_models_metadata.json")`，
   文件放在 `assets/`（见 build_v17.sh 第 4 步）。
2. 同一个类 `<clinit>` 里的 `sget ... R$raw` 改成常量 0。
3. `zzad.smali` 里 `R$xml.rapid_response_client_defaults`（广告用的）直接删掉。

其余 `R$id/$string/$drawable` 引用（androidx.core、GMS 的登录对话框）用 0 值 stub 类补上：
我们从不走那些代码路径，只要类能加载就行（build_v17.sh 第 3 步自动生成）。

## 4. 模型怎么来

ML Kit 只支持「按需下载」这一种安装方式（没有 bundled 选项），
下载地址在 SDK 里写死：`https://dl.google.com/translate/offline/<ver>/high/<pair>/<hash>.zip`，
中英包 35.3 MB / 解压 45.1 MB，落在 App 私有目录，之后永久离线。
v17 的「翻译 ⚙ → 导出翻译模型包」就是把这堆文件打包出来，用来验证「一个用户下载、其他人导入」这条路。
