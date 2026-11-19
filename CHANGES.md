# 版本记录

本仓库的版本号与 APK 内的 `versionCode` / `versionName` 无关（沿用基础包 `33` / `1.9.5-v73-sm8650`），
下列 `+apiN` 是 `ApiServer.VERSION`，安装后用 `curl http://127.0.0.1:8081/status` 查看。

| 版本 | 服务版本 | APK | sha256（完整） |
|---|---|---|---|
| v6 | `1.9.5-v73+api6` | `HexAnima-1.9.5-v73-localapi-v6.apk` | `fbec8c8f2a63c9e9fb4f5dba4b4ef4b6b5798096b91f0c1108333677fd0f5d38` |
| v5 | `1.9.5-v73+api5` | `HexAnima-1.9.5-v73-localapi-v5.apk` | `892c8e35af9b44fc2f82ed9b7ad2d01008a3d8365a11e4d7002f149d8a1402f4` |
| v4 | `1.9.5-v73+api4` | `HexAnima-1.9.5-v73-localapi-v4.apk` | `7626cb8ba45d8e4601e3987ea17eee11f9a5f46e9f1a99036c0ce7fb8c84f907` |
| v3 | `1.9.5-v73+api3d` | `HexAnima-1.9.5-v73-localapi-v3.apk` | `d1ead0a46cbafda39a25930b4bccdebf237d15559898f9ce34a111de04063297` |
| v2 | `1.9.5-v73+api3` | `HexAnima-1.9.5-v73-localapi-v2.apk` | `56304bb73c9959c65af37c59e512e818dfc84edc074f7b1bce3dc567fc91c3d7` |

---

## v6 — 2026-11-19

修复 v5 转换时“无法读取文件 / 秒失败”。

* **原因**：v5 的 `run()` 把转换器的原始错误（`error cannot read …`）直接翻成了本地化文案，
  而调用方是靠 `err.contains("cannot read")` 决定要不要「复制一份再转换」的——翻译之后这个判断永远不成立，兜底失效。
  另外 Android 的 `ProcessBuilder` 不继承父进程 fd，`/proc/<pid>/fd/<n>` 这条老路子本身就不可靠。
* **现在按三级顺序取源文件**：
  ① 解析 SAF 文档的真实路径（`primary:` 卷与 SD 卡卷）并 `canRead()` + 长度校验，直接交给转换器（零复制）；
  ② 退一步用 `/proc/<pid>/fd/<n>`；
  ③ 都不行就把源文件复制进应用内（`files/anima/import/`）再转换，带 GB 级进度，开始前先算好「复制 + 模型」所需空间。
* 转换器报错只在最后翻译一次，不再参与控制流。

安装：与 v2–v5 同签名，可覆盖安装。

---

## v5 — 2026-11-19

修复本地 `.safetensors` 转换，并把转换界面做成中英双语。

* **识别规则按 `libanimaconv.so` 重写**。v3/v4 找的是「不在 `blocks.` 下的 `self_attn.q_proj.weight`」，
  而 Anima DiT 没有这种全局张量，于是纯主干、带 VAE/文本编码器的打包版**全部**被判成 “not an Anima DiT”。
  现在找 `(任意前缀)blocks.0.self_attn.q_proj.weight`、要求 `[2048, 2048]`，再用最大的 `blocks.N` 判 28 / 40 块，
  与转换器本身的判定一致。实测 4.18 GB 的 `oneObsessionAnima_v50`（前缀 `model.diffusion_model.`、F16、28 块）识别正常。
* **输出改 q8**：块 83,365,888 B，28 块 + `small.bin` ≈ 2.6 GB，与目录下载的 2B 模型同规格；
  所需空间按 `83,365,888 × 块数 + 310 MB` 估算。
* **补写 `hdr.bin`**（源文件的 safetensors 头），stub 由它生成，模型备份导出/再导入才能重建 stub；
  新增 `.custom` 标记；转换前检查基础文件（文本编码器 / VAE）是否已下载，避免“转完在列表里看不见”。
* **中文化**：转换列表、进度、失败原因、转换器报错（缺 adaln / dtype 不支持 / 目录提供方不给真实文件…）全部中英双语。

安装：与 v2–v4 同签名，可覆盖安装。

---

## v4 — 2026-11-19

同步上游 HexAnima 1.8.3（ayanoby/hexanima）的六项体验功能，并把模型备份界面做成中英双语。
本版起不再用 smali 钩子，改为**从 GrimmDev 的 fork 源码重编 7 个类**；生成算法、NPU 内核、模型格式未改。

* **图片保存位置**（设置页）：“手机相册（Pictures/Anima）”或任意文件夹（SAF 持久授权，失权自动回落相册）。
* **生成后自动保存**（默认关）：每张成品自动复制到上面选的位置，应用内历史始终保留一份。
* **查看器下滑关闭**：未缩放时图片跟手下滑，超过屏高 1/6 或快速甩下即关闭，上下栏同时淡出；左右翻页与缩放不变。
* **删除 / 停止确认**：模型页删除、生成中“停止”都会先弹确认。
* **模型加载失败原因**：`anima.sh` 等待启动最多 5 分钟，失败把日志尾部写进 `files/anima/load_error`，
  App 弹窗显示芯片型号与最后几行日志，不再一直停在 “Loading the model…”。
* **时间预估学习 + 校准 + 分段计时**：每张图记录“实测 / 公式估算 / 设置签名 / token 数”到 `files/timings.json`（每模型 20 条）；
  按钮时间取同设置中位数，不同尺寸按 token 数对数插值校正；启动后首跑不计入。
  菜单新增“时间校准”（最小/中间/最大三个尺寸，单张预计 ≤150 s）；Anima 图还会记录 text / DiT / decode 秒数并在查看器显示。
* **批量张数修复**：生成中改张数只影响下一次，不再延长正在跑的批次（上游 1.8.3 修复）。
* **模型备份中文化**：MODEL BACKUP 卡片与备份弹窗全部中英双语，默认跟随手机语言，弹窗底部可切换（只影响该界面）。

安装：与 v2/v3 同签名，可直接覆盖安装。

---

## v3 — 2026-11-19

模型备份与离线目录，清单格式与上游 1.8.3 逐字段一致，可与上游版本互换备份。

* 模型页顶部 **MODEL BACKUP** 卡片：单个/全部导出到 SAF 文件夹；一键导出到 `Download/HexAnimaBackup`；
* **导入**模型文件夹：逐文件校验 `size + sha256`，用 `hdr.bin` 重建稀疏 stub，标记已安装；
* **离线目录**：每次在线打开模型页后写 `files/catalog.cache`，断网时显示缓存列表，已装模型仍可管理/删除；
* **检查点文件夹转换**：把文件夹里的 `.safetensors` 批量转换成应用内模型（`libanimaconv.so`，
  优先用 `/proc/<pid>/fd/<n>` 免复制；读 8 字节头长 + JSON 判断 2B/2.9B）；
* 新增类 `Backup`、`Ckpt`；模型页 5 个调用点（卡片、目录缓存、离线回退、SAF 回执）。

安装：与 v2 同签名，可覆盖安装。

---

## v2 — 2026-11-18

参数继承：请求里没给（或给空值/0）的 `steps`、`cfg`、`sampler`、`scheduler`、`negative_prompt`、尺寸，
一律取 App 当前界面/设置里真正生效的值；`null` 有明确语义（例如 `negative_prompt: null` = 空负向词）。
只回写请求里确实给了的字段，不会改动 App 里保存的配置。

安装：与 v1 同签名，可覆盖安装。

---

## v1 — 2026-11-18

第一版本地接口。

* `127.0.0.1:8081` 明文 HTTP，LocalDream 兼容协议：
  `POST /generate` 返回 `text/event-stream`，进度事件 `{"type":"progress",…}`，
  完成事件 `{"type":"complete","image":"<base64 原始 RGB>",…}`；
* 反射驱动 App 自己的生成流程（同一套设置、同一条 NPU 管线），后台运行、保活、超时与取消；
* 酒馆脚本 `酒馆助手脚本-手机本地生图v7.6.json` 零改动可用；
* 调试端点：`/status`、`/props`、`/log`、`/preview.png`、`/last.png`、`/test`。

安装：需卸载原版 HexAnima（签名不同），模型需重新下载。
