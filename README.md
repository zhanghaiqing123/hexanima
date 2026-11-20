# HexAnima 本地生图接口 · SillyTavern 适配（V73 内核 / 8 Gen 2 · 8 Gen 3 · 8 Elite · 8 Elite Gen 5）

> **本项目是 [ayanoby/hexanima](https://github.com/ayanoby/hexanima) 的 fork。**
> 原版是最初的 HexAnima：Hexagon NPU 内核、采样器、设备端推理、App、模型包，整件事的基础。
>
> **Snapdragon 8 Gen 2（SM8550 / Hexagon V73）的移植与配套功能来自 [GrimmDev-Glitch/hexanima](https://github.com/GrimmDev-Glitch/hexanima)**
> （MOSS-Image、Qwen-Image 编辑、Mage-Flow、LoRA 管理、Civitai、放大、修正、批量等）。
> 本 fork 的 APK 基于它的 `1.9.5-v73` 发布版，并在此基础上做了三件事：
>
> 1. 加一个 **LocalDream 协议兼容的本地 HTTP 服务**（`127.0.0.1:8081`），让酒馆（SillyTavern）脚本、浏览器、`curl` 直接出图；
> 2. 加 **模型导出 / 导入 / 离线目录 / 检查点转换**（与上游 1.8.3 的数据格式互通）；
> 3. 把上游 **1.8.3 的功能**（保存位置、自动保存、下滑关闭、删除/停止确认、加载失败原因、时间预估与校准、批量张数修复）同步进 V73 版本。

```
ayanoby/hexanima                 原版：NPU 内核、模型包、App 本体（MIT）
  └─ GrimmDev-Glitch/hexanima     二创：Snapdragon 8 Gen 2 / Hexagon V73 移植 + 大量新功能
       └─ 本项目（本仓库）          V73 版 + 本地 8081 接口 + 模型备份 + 上游 1.8.3 体验功能
```

> **兼容性（v10 起）**：8 Gen 2 / 8 Gen 3 / 8 Elite（SM8750）/ 8 Elite Gen 5（SM8850）可用。
> **8 Gen 1 / 8+ Gen 1（SM8450 / SM8475）从 v10 起不再支持**（QNN 2.51 不再提供 V69 skel），
> 这两颗 SoC 请下载 **v9 或更早**的版本。
>
> 仓库同时保留上游 ayanoby/hexanima 的源码树（`app/`、`chainrun/`、`docs/` 等，MIT）作为参考；
> 本项目的构建使用的是 GrimmDev `1.9.5-v73` 的源码（见[自己重新打包](#自己重新打包)）。

---

## 目录

* [版本与产物](#版本与产物)
* [来源与致谢](#来源与致谢)
* [下载与安装](#下载与安装)
* [8081 端口说明](#8081-端口说明)
* [接口文档](#接口文档)
* [新增功能（v1 → v4）](#新增功能v1--v4)
* [它是怎么接进去的](#它是怎么接进去的)
* [自己重新打包](#自己重新打包)
* [已测试](#已测试)
* [已知限制](#已知限制)
* [许可证与第三方](#许可证与第三方)

---

## 版本与产物

| 版本 | 产物 | 服务版本 `/status` | 内容 |
|---|---|---|---|
| v2 | `HexAnima-1.9.5-v73-localapi-v2.apk` | `1.9.5-v73+api3` | 8081 接口 + 参数继承 |
| v3 | `HexAnima-1.9.5-v73-localapi-v3.apk` | `1.9.5-v73+api3d` | 模型导出/导入、离线目录、检查点文件夹转换、模型页 MODEL BACKUP 卡片 |
| v4 | `HexAnima-1.9.5-v73-localapi-v4.apk` | `1.9.5-v73+api4` | 上游 1.8.3 六项功能 + 备份界面中英双语 |
| v5 | `HexAnima-1.9.5-v73-localapi-v5.apk` | `1.9.5-v73+api5` | 修复本地 `.safetensors` 转换识别 + 转换界面中文化 |
| v10 | `HexAnima-1.9.5-v73-localapi-v10.apk` | `1.9.5-v73+api10` | 去掉 App 自带的温控暂停；RAM 条加 SoC 温度；QNN 2.51，支持 8 Elite / 8 Elite Gen 5（8 Gen 1 不再支持） |
| v9 | `HexAnima-1.9.5-v73-localapi-v9.apk` | `1.9.5-v73+api9` | 每个 LoRA 三个开关：启用（决定加载/重载）、智能（按触发词写标签）、写标签（永远写） |
| v8 | `HexAnima-1.9.5-v73-localapi-v8.apk` | `1.9.5-v73+api8` | 激活改为存储开关（决定加载，只有切换才重载）；标签只作标记，随触发词自动增删 |
| v7 | `HexAnima-1.9.5-v73-localapi-v7.apk` | `1.9.5-v73+api7` | LoRA 触发词/自动挂载、库界面重排、主界面下拉、温控上限可调 |
| v6 | `HexAnima-1.9.5-v73-localapi-v6.apk` | `1.9.5-v73+api6` | 修复转换时取不到源文件路径（真实路径 → fd → 复制三级兜底） |

逐版本细节见 [CHANGES.md](CHANGES.md)。

> APK 的 `versionCode` / `versionName` 与基础包一致（`33` / `1.9.5-v73-sm8650`），
> 安装后判断版本只能看 `curl http://127.0.0.1:8081/status` 里的 `version` 字段。

---

## 来源与致谢

| 项目 | 作者 | 贡献 |
|---|---|---|
| [ayanoby/hexanima](https://github.com/ayanoby/hexanima) | [@ayanoby](https://github.com/ayanoby) | **原版**：Hexagon NPU 内核、稳定扩散采样器、设备端推理、App、模型包。本仓库即它的 fork。 |
| [GrimmDev-Glitch/hexanima](https://github.com/GrimmDev-Glitch/hexanima) | [@GrimmDev-Glitch](https://github.com/GrimmDev-Glitch) | **Snapdragon 8 Gen 2（Hexagon V73）支持**与大量扩展功能；本项目的 APK 基于其 `1.9.5-v73` 发布版。 |
| 本项目 | [@zhanghaiqing123](https://github.com/zhanghaiqing123) | 本地 HTTP 接口、酒馆脚本适配、模型备份、上游 1.8.3 功能同步，见 [NOTICE.md](NOTICE.md)。 |

**V73 移植的功劳属于 GrimmDev-Glitch**：没有那次移植，这个接口不可能在 8 Gen 2 / 8 Gen 3 上跑起来。
本仓库只做"把接口和体验功能接到已经移植好的 V73 版本上"，没有修改任何 NPU 内核或生成算法。

---

## 下载与安装

优先使用 [Releases](../../releases) 里的 APK；没有 release 时可按[自己重新打包](#自己重新打包)构建（产物约 174 MB）。

**安装前必读：**

1. **先卸载手机上原有的 HexAnima**（官方版 / GrimmDev 版都算）。本项目用独立 key 签名，和上游签名不兼容。
2. ⚠️ **卸载会清掉 `files/anima` 里的模型**（几个 GB），需要在 App 里重新下载，或提前用 v3+ 的「导出」备份。
3. 装好打开一次，选模型、下载模型，然后可以切到后台（**别从最近任务里划掉**）。
4. 之后本项目的小版本升级都用同一个 key，可以直接覆盖安装，数据不再丢。

已装过本项目旧版（同 key）时直接覆盖安装即可，模型不用重下。

---

## 8081 端口说明

这是本项目唯一对外的东西，也是最容易踩坑的地方。

### 监听范围

| 项 | 值 |
|---|---|
| 地址 | **`127.0.0.1:8081`**（只绑定回环，不监听 `0.0.0.0`） |
| 协议 | 明文 HTTP，无 TLS、无鉴权 |
| 端口 | **固定 8081**，运行时不可改（见[自己重新打包](#自己重新打包)） |
| 启动时机 | `HexAnima` 进程启动（`Main.onCreate`）时起线程；**进程被杀后要重新打开一次 App** |
| 开机自启 | 没有（需要改 Manifest，本项目刻意不做） |

因为只绑回环，**同一台手机上的 App 都能访问，局域网/公网访问不到**。

### 为什么是 8081

酒馆的 [LocalDream](https://github.com/xororz/local-dream) 生图脚本把地址写死成 `http://127.0.0.1:8081`，
协议也是它定的（SSE + base64 原始 RGB）。为了**酒馆脚本一行不改**就能用，这里照抄了协议和端口。

### ⚠️ 和 Local Dream 冲突

**Local Dream 也占 `127.0.0.1:8081`。** 两者不能同时工作：

* 两个 App 都开着时，**谁先绑定谁赢**，后来者绑定失败（本项目会在 `/log` 里留一条 `bind failed`）。
* 切换：把另一个 App **彻底退出**（最近任务划掉，或系统设置里强制停止），再打开要用的那个。
* 想换端口：改 `src/com/hexanima/npu/ApiServer.java` 的 `PORT` 常量重新打包，酒馆脚本同步改。

### 谁能调它 / 怎么调

| 调用方 | 做法 |
|---|---|
| 酒馆（SillyTavern） | 酒馆跑在 `127.0.0.1:8000`，用仓库里的 `sillytavern/酒馆助手脚本-手机本地生图v7.6.json`，地址填 `127.0.0.1:8081`（默认就是） |
| 手机浏览器 | 打开 **`http://127.0.0.1:8081/test`**，自带自测页 |
| 其它脚本 / 命令行 | `curl -N -X POST http://127.0.0.1:8081/generate -H 'Content-Type: application/json' -d '{"prompt":"1girl, solo"}'` |
| 从电脑 | 需要 `adb reverse tcp:8081 tcp:8081`（会把端口暴露给 adb 侧，见安全提醒） |

CORS 全开（`Access-Control-Allow-Origin: *`，并带 `Access-Control-Allow-Private-Network: true`），
酒馆那个 `127.0.0.1:8000` 的页面能直接跨端口调用，不需要代理。

### 安全提醒

* **没有鉴权、没有加密**。手机里任何一个 App（包括恶意 App）都能调这个接口，占着 NPU 出图、拿到生成的图片。
* **不要**通过 `adb reverse`、路由器端口映射、内网穿透之类手段暴露到局域网/公网。
* 只在你自己可控的手机上使用；用完可以「强制停止」App 把端口收掉。

### 排障

| 现象 | 原因 / 处理 |
|---|---|
| 浏览器打不开 `/test` | App 没在跑：重新打开一次，确认没被系统杀掉 |
| `connection refused` | 同上；或进程刚启动还没起线程（等 1 秒） |
| `bind failed` | 8081 被占了：多半是 Local Dream 在跑，退出它 |
| 请求返回 `HexAnima is not running` | App 进程还活着但 Activity 已销毁，重新打开 App |
| 出图报 `the phone got too hot` | 撞温控了，等手机凉下来，或降低步数/分辨率 |
| 模型加载一直转 | 见「时间与加载」；v4 起最多等 5 分钟并弹窗说明原因 |

---

## 接口文档

### `POST /generate`（SSE，和 LocalDream 一致）

请求体：

```json
{
  "prompt": "1girl, solo, ...",
  "negative_prompt": "worst quality, ...",
  "steps": 6,
  "cfg": 1,
  "scheduler": "euler_a",
  "size": 512,
  "width": 512, "height": 512,
  "aspect_ratio": "2:3",
  "seed": 123456
}
```

* 尺寸三选一：`size`（正方形边长）/ `width`+`height` / `aspect_ratio`（会算成最接近的合法尺寸）。
* App 只接受：宽高都 ≥256 且是 16 的倍数，并且 `(宽/16)×(高/16)` 能被 32 整除。接口会自动往上修正到最近合法值。
* `scheduler` 和 `sampler` 都认；只给 `sampler` 时调度器沿用 App 里的。
* `seed` 省略则随机，并在返回里如实回报。
* `denoise_strength`、`show_diffusion_process`、`lora_dir`、`lora_strength` 目前**忽略**（纯文生图）。

### 参数继承（请求优先，缺省听 App 的）

**请求里给了的参数优先；没给、或给空值/0 的，一律用 App 里当前那份配置。**

| 字段 | 传了 | 没传 / `""` / 0 | `null` |
|---|---|---|---|
| `prompt` | 用 | 报错（必填） | 报错 |
| `negative_prompt` | 用 | 用 App 里的负向词 | **明确要空负向词** |
| `steps` | 用（夹到 1–60） | 用 App 里的步数 | 同左 |
| `cfg` | 用 | 用 App 里的 CFG | 同左 |
| `scheduler` / `sampler` | 用 | 用 App 里的采样器 + 调度器（成对） | 同左 |
| 尺寸 | 用 | 用 App 当前分辨率 | 同左 |
| `seed` | 用 | 随机 | 随机 |

"用 App 里的"取的是**出图时真正生效的那份配置**：步数 / CFG / 负向 / 采样器 / 调度器走 App 自己的
`Settings.steps/cfg/negative/sampler/scheduler`，尺寸取界面上的宽高。所以 App 里刚改完，下一次极简请求立刻跟着变。

而且**只回写请求里真给了的字段**：极简脚本调用一百次，也不会动 App 里存的配置。

最小请求可以只有一行：

```json
{"prompt": "1girl, solo"}
```

日志里会标出这次哪些字段是继承来的，例如 `inherit=[negative,steps,cfg,sampler,size]`。

### 响应（`text/event-stream`）

```
data: {"type":"progress","step":4,"total_steps":6,"percent":66}

data: {"type":"complete","image":"<base64 原始 RGB>","width":512,"height":512,"seed":123456,"generation_time_ms":13671}

data: {"type":"error","message":"..."}
```

`image` 是裸 RGB（每像素 3 字节，无行填充、无 alpha），和 LocalDream 一致，前端用 canvas 还原即可。
`512×512` 对应 base64 `1,048,576` 字符。

### 其它端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` 或 `/health` | 在线探测（酒馆脚本用它判断服务是否在线） |
| GET | `/status` | 状态：`version`、模型、是否生成中、当前界面、最后一条错误 |
| GET | `/props` | App 当前生效的 steps / cfg / sampler / scheduler / 负向 / 分辨率 |
| GET | `/log` | 纯文本日志（最近 400 行），出问题先看这个 |
| GET | `/diag` | 诊断合集（v4）：版本、files 目录、目录缓存、已装模型、备份日志 |
| GET | `/card` | 在模型页重建 MODEL BACKUP 卡片（调试用） |
| GET | `/preview.png` | 当前这一步的预览图 |
| GET | `/last.png` | 上一张成品原图 |
| GET | `/test` | 内置自测页（含「极简模式」勾选框） |

> `/diag`、`/card`、`/props` 是调试口，正式发布前可以删除。

---

## 新增功能（v1 → v10）

### v1 · 本地接口
8081 HTTP/SSE；反射驱动 App 自己的生成流程（同一套 UI 管线、同一份设置）。

### v2 · 参数继承
请求缺省字段从 App 当前设置继承，酒馆脚本不用再传一堆参数。

### v3 · 模型备份（与上游 1.8.3 数据格式互通）
模型页顶部 **MODEL BACKUP** 卡片：
* 单个/全部**导出**到自选文件夹（SAF）或一键 `Download/HexAnimaBackup`；
* **导入**文件夹（校验 `size + sha256`，用 `hdr.bin` 重建 stub）；
* **离线目录**（`files/catalog.cache`，断网也能看/删模型）；
* **文件夹批量 `.safetensors` 转换**（`libanimaconv.so`，免复制、读 header 判断 2B/2.9B；识别规则在 v5 修正）。

### v4 · 同步上游 1.8.3 六项功能 + 中文化
1. **图片保存位置 + 生成后自动保存**（设置页；手机相册或任意文件夹，SAF 持久授权）；
2. **查看器下滑关闭**（跟手、上下栏淡出，左右翻页/缩放不变）；
3. **删除 / 停止确认**（模型删除、生成中「停止」都要先确认）；
4. **模型加载失败原因**（`anima.sh` 最多等 5 分钟，失败写 `load_error`，弹窗显示芯片与日志尾）；
5. **时间预估学习 + 校准 + 分段计时**（`files/timings.json`；菜单「时间校准」；查看器显示 text / DiT / decode 秒数）；
6. **批量张数修复**（生成中改张数只影响下一次）。

另外：模型备份卡片与弹窗中英双语，默认跟随手机语言，弹窗底部可切换。

### v5 · 本地检查点转换修复 + 中文化
v3/v4 的转换界面会把**任何**文件都判成 “not an Anima DiT”（连官方纯主干都不行），原因是识别用的张量写错了：
找的是「不在 `blocks.` 下的 `self_attn.q_proj.weight`」，而 Anima DiT 根本没有这种全局张量。

* 识别规则按 `libanimaconv.so` 重写：找 `(任意前缀)blocks.0.self_attn.q_proj.weight`、要求形状 `[2048, 2048]`，
  再用最大的 `blocks.N` 判 28（2B）/ 40（2.9B）；带 VAE / 文本编码器的打包版同样能认。
* 转换输出改 **q8**（块 83,365,888 B，28 块 + `small.bin` ≈ 2.6 GB，与目录里下载的 2B 模型同规格），
  所需空间按 `83,365,888 × 块数 + 310 MB` 估算。
* 转换出的模型目录补写 `hdr.bin`（源文件的 safetensors 头），stub 由它生成，导出/再导入可以重建；
  另加 `.custom` 标记，并在转换前检查基础文件（文本编码器 / VAE）是否已下载——否则模型会“转完看不见”。
* 转换列表、进度、失败原因（缺 adaln / dtype 不支持 / 目录提供方不给真实文件…）全部中英双语。

### v10 · 温控交还系统 + SoC 温度 + 8 Elite
* **不再由 App 暂停或降温**：删掉电池温度阈值滑杆、「太热也先出一张」开关、`waitCool()` 冷却等待、高温通知、队列/校准的热门禁，以及跑完后那句「已暂停：手机过热，请让其冷却」——它原本是出图完成之后又查了一次温度，属于误报。系统热等级提示保留，只提示不拦人。
* **降温模式**只剩手动「总是 / 从不」，默认「从不」。
* **RAM 条新增 SoC 温度**：`RAM … · 电池 34.3 °C · SoC 61 °C`；取 CPU/GPU/NPU（`cpuss`、`cpu-*`、`gpuss`、`nsphvx`、`nsphmx`）热区的最大值，读不到就只显示电池温度。
* **8 Elite / 8 Elite Gen 5**：QNN 2.28 → 2.51，新增 V79 / V81 skel；`dspArch` 把 SM8750 / SM8850 也映射到 `v73`，模型包不用重下；`anima.sh` 的 DSPA case 加 SM8850。
* **8 Gen 1 / 8+ Gen 1 失去支持**：QNN 2.51 不再带 V69 skel；这两颗 SoC 请用 v9。
* Mage / MOSS / Qwen 在 8 Elite / 8 Elite Gen 5 上不保证（脚本没给这两颗 SoC 走 v73 内核）；8 Gen 3 上保持原样。

### v9 · 标签变成可控的三件套
实测确认标签对出图没有影响（引擎加载的是「启用」集合）。于是每行三个开关：

| 开关 | 作用 | 重载 |
|---|---|---|
| 启用（`on`） | 引擎加载它 | **会（唯一）** |
| 智能（`smart`，默认开） | 触发词出现 → 写标签；消失 → 撤掉 | 不会 |
| 写标签（`auto`） | 有没有触发词都写（仍需先启用） | 不会 |

两个都不勾 = 完全不动提示词里的标签；没有触发词的 LoRA「智能」自动置灰。

### v8 · 激活与标签分开
v7 把「激活」做成了"把标签写进提示词"，于是提示词一改就可能改变引擎加载的集合 → 重载 40–60 秒。

v8：**激活（存储开关）决定引擎加载哪些 LoRA，只有切换开关才重载；提示词里的 `<lora:…>` 只是"这张图用到它"的标记，随触发词自动增删，怎么改都不重载。**

* 触发词出现 → 自动加标签；触发词消失 → 自动删标签（只对填了触发词的 LoRA；没填触发词的不动）；
* 「自动挂载」= 没有触发词也保留标签，前提同样是先激活；
* 没激活的 LoRA 永不写标签，手写的也会被移除；
* 姿态适配器不受影响；导入 = 加进库并激活；「✦ ChatGPT 风格」会顺带激活 `gpt-image-2`。

### v7 · LoRA 挂载与温控
* **LoRA 只由提示词里的 `<lora:名字:权重>` 决定**：旧版"最后一个导入的常驻"那个槽在首次启动时迁移成该文件的"自动挂载"，行为不变。
* **触发词自动挂载**：提示词里出现某 LoRA 的触发词（整词匹配、`\(` 与 `(` 等价、CJK 视作词边界）→ 生成前自动补标签；
  无触发词的 turbo/画质包用行内"自动挂载"勾选；**只加不删**（避免每次提示词变化都重载模型 40–60 秒）。
* **LoRA 库界面**：三行布局（名字 / 触发词 · 分类 / ✎ 自动挂载 权重 🗑），标题下有用法提示，删除二次确认。
* **主界面 LoRA 行**：显示"提示词当前调用的 LoRA"，点名字从库列表挂载/取消；✕ 改为先选"取消挂载 / 删除文件"再确认。
* **温控可调**：设置页"多少度就暂停生图" 35–55 °C（默认 45，充电 +3），"太热时也允许先出一张"（默认开），
  恢复阈值 2 °C，自动降频阈值 = 上限 − 5 °C。

### v6 · 转换取源文件（真实路径 → fd → 复制）
v5 能识别模型了，但点「转换所选」会立刻提示“无法读取文件：这个目录提供方不提供真实文件”。
原因是 v5 在 `run()` 里就把转换器的原始错误翻成了中文，而调用方靠 `err.contains("cannot read")`
判断要不要「复制一份再转换」——翻译之后判断永远不成立。另外 Android 的 `ProcessBuilder` 不继承父进程 fd，
`/proc/<pid>/fd/<n>` 这条老路子本来就不可靠。

现在按三级顺序取源文件：① 解析 SAF 文档的真实路径并 `canRead()` + 长度校验（零复制）；
② 退回 `/proc/<pid>/fd/<n>`；③ 前两者都不行才把源文件复制进应用内（带 GB 进度，先算好空间）。
转换器报错只在最后翻译一次。

---

## 它是怎么接进去的

App 原本只有「点按钮出图」一条路。本项目**不修改生成算法**，只做两类改动：

**A. 新增类（`src/`）**

| 类 | 作用 |
|---|---|
| `ApiServer` | 8081 HTTP/SSE；`start()` / `onProgress()` / `onFinish()` 三个入口；出图靠反射调用 `Main.generate(false)` |
| `Backup` | 模型导出/导入、离线目录、MODEL BACKUP 卡片、中英双语 |
| `Ckpt` | 文件夹批量 `.safetensors` → 模型转换 |

**B. 从 fork 源码重编的类（`repo/forkmod/`，v4 起）**

| 类 | 改动 |
|---|---|
| `Main` | `ApiServer.start/onFinish` 调用、停止确认、时间预估与校准、张数语义 |
| `MainRun` | 进度回调、加载失败原因、text/DiT/decode 计时 |
| `MainTools` | ⋯ 菜单加「时间校准」 |
| `Settings` | 保存位置、自动保存 |
| `Gallery` | 保存到 SAF 文件夹 / 相册、自动保存、阶段耗时展示 |
| `Viewer` | 下滑关闭 |
| `ModelsActivity` | 删除确认、备份卡片、离线目录 |
| `Tr` / `Timings` | 本改造新增：语言选择、时间学习 |

另外 `assets/anima.sh` 加了「5 分钟超时 + 写 `load_error`」的补丁（v4）。

> v3 及以前是「只改 smali」的二进制补丁；v4 起改成**从 fork 源码重编整类**，
> 可读性和可维护性更好，生成路径依旧逐字节沿用上游。
> 旧的 `build/patch_hooks.py` 仅作历史存档，v4 构建不再使用。

---

## 自己重新打包

### 前置

* JDK 17+（开发用 21，`-source 8 -target 8`）
* `apktool` **2.11.1**（低版本会在 aapt 阶段报 `First type is not attr!`）
* `d8`（r8.jar）、`baksmali`、Android SDK Build-Tools 的 `zipalign` + `apksigner`
* `android-all` 14 的 stub jar（编译用，约 137 MB）
* 上游 `HexAnima-1.9.5-v73` 的 APK（从 GrimmDev 的 Release 下载）与**同版本的 fork 源码**（`-sourcepath` 用）
* 自己的 keystore（本仓库不含签名密钥）

```bash
# 依赖（示例：放进 /tmp）
curl -sL https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar -o /tmp/r8.jar
curl -sL https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar -o /tmp/android34.jar
# fork 源码
curl -sL https://codeload.github.com/GrimmDev-Glitch/hexanima/tar.gz/refs/tags/v1.9.5-v73 -o /tmp/fk.tgz
tar xzf /tmp/fk.tgz -C /tmp && mkdir -p /tmp/fork_sp/com/hexanima/npu
cp /tmp/hexanima-1.9.5-v73/app/src/com/hexanima/npu/*.java /tmp/fork_sp/com/hexanima/npu/
# 解码基础包
java -jar /tmp/apktool.jar d -r -f HexAnima-1.9.5-v73.apk -o /tmp/ha_r
```

### 构建

```bash
bash build/build_v5.sh
```

脚本做七步：干净复制解码目录 → `javac`（`repo/forkmod` + `src/`）→ `d8` → `baksmali` →
替换 12 个类的 smali（含内部类/合成 lambda）→ `apktool b` → `zipalign` + `apksigner`（v3 签名）。

签名（keystore 请务必留好，丢了就只能卸载重装）：

```bash
keytool -genkeypair -keystore hexanima.jks -alias hexanima \
        -keyalg RSA -keysize 2048 -validity 10000 -storepass 你的口令 -keypass 你的口令 -dname "CN=HexAnima Mod"
```

### 构建后自检

把产物 `classes.dex` 反编译后做两项静态检查，确认没有删漏：

1. 所有 `Lcom/hexanima/npu/X;` 类型引用都能找到类定义；
2. 所有 `Lcom/hexanima/npu/X;->name:sig` 成员引用都能在 X 或其父类链中找到。

然后安装，`curl http://127.0.0.1:8081/status` 确认 `version` 是本版。

---

## 已测试

设备：realme GT5 Pro（SD 8 Gen 3 / SM8650 / Android 16），模型 `harem16`（turbo），App 在后台。

| 配置 | 耗时 |
|---|---|
| 512×512 · 6 步 · cfg 1 · euler_a | **≈ 14 秒** |
| 640×960 · 6 步 · cfg 4 · euler_a | ≈ 43 秒 |
| 640×704 · 8 步 · cfg 2 · dpm++2m / exponential | ≈ 38 秒 |

* v10 实测：8 Gen 3（本机）、8 Elite、8 Elite Gen 5 均可正常出图；8 Gen 2 走同一套 v73 路径，预计可用（未实测）。
* 连续出图后系统温控仍会让机器变慢甚至过曝，这是设备侧行为，v10 起 App 不再介入。
* 返回数据逐字节校验过：`512×512 → 786,432 字节 RGB → 1,048,576 字符 base64`。
* 酒馆脚本 v7.6 实测可用（含 SSE 进度、图片落地、画廊）。
* v4 的六项功能在 8 Gen 3 上逐项验证过；8 Gen 2（V73）理论上同样适用（代码层面没有设备分支）。

---

## 已知限制

* **纯文生图**。`init_src.png` 在请求期间会被改名暂存，所以即使 App 里选了图也是 txt2img。
* **LoRA 参数忽略**。HexAnima 的 LoRA 机制（NPU 端合并、按模型族分类）和 LocalDream 完全不同，需要单独设计。
* **端口固定 8081**，且与 Local Dream 冲突（见上）。
* **单任务串行**：同一时间只处理一个 `/generate`，后来的请求排队。
* **步数上限 60**（App 界面本身也只允许 1–60）。
* **8 Gen 1 / 8+ Gen 1 无支持**：v10 起 QNN 2.51 不带 V69 skel，请用 v9 或更早版本。
* **Mage / MOSS / Qwen 在 8 Elite / 8 Elite Gen 5 上不保证**：脚本只对 8 Gen 2 / 8 Gen 3 选了 v73 内核。
* **调度器只能设 4 种**：`sgm_uniform`、`karras`、`exponential`、`discrete`。App 里另外几种只能靠"继承"生效。
* **不自动启动**：进程被杀后需要重新打开 App。
* **无鉴权**：见[安全提醒](#安全提醒)。
* **安装需卸载旧版**（签名不同），模型需重新下载或提前导出备份。

---

## 许可证与第三方

* 上游代码是 **MIT**（`Copyright (c) 2026 ayanoby`），本仓库的补丁代码（`src/`、`repo/forkmod/`、`build/`、文档）也在 MIT 下发布，见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。
* 模型、模型包、NPU 运行时等第三方组件的许可与上游一致；**本项目不附带任何模型文件**，模型由 App 自己下载。详见上游的 [THIRD_PARTY.md](https://github.com/GrimmDev-Glitch/hexanima/blob/main/THIRD_PARTY.md)。
* 注意：Anima / MIAOKA 等模型是**非商用**许可（不得售卖图片），Qwen-Image 是研究许可；使用前请阅读对应模型页条款。

## 免责声明

非官方项目，与 ayanoby、GrimmDev-Glitch 无隶属关系。按原样提供，不保证可用性；出图内容和版权责任由使用者自负。
