# HexAnima 定制版 · 本地接口 + 翻译 + 模型工具

在 [HexAnima]（骁龙 NPU 本地生图 App）上加了一套**本地 HTTP 接口、中→英提示词翻译、模型导入转换、模型备份**。

> **底层来自上游。** 本仓库不是重写的推理引擎：NPU 算子、生图管线、模型加载、采样器、App 界面主体全部来自上游
> [GrimmDev-Glitch/hexanima]；V73（8 Gen 2）支持来自 [ayanoby/hexanima]。
> 这里只做功能层的增删：接口、翻译、导入转换、备份、LoRA 管理、重绘。
> 上游项目的许可证与致谢见文末。

> ## ⚠️ 机型支持
> **第五代骁龙 8 至尊版（SM8850 / 8 Elite Gen 5）只支持 Anima 系列模型。**
> MOSS / MAGE / Qwen 在这一代没有对应的 NPU 内核，会直接提示「不支持」，不是配置问题。
>
> | 平台 | 支持情况 |
> |---|---|
> | 8 Gen 2（SM8550 / V73） | Anima · MOSS · MAGE · Qwen |
> | 8 Gen 3（SM8650 / V75） | Anima · MOSS · MAGE · Qwen |
> | 8 Elite（SM8750 / V79） | Anima 稳定；其余以实际内核为准 |
> | **8 Elite Gen 5（SM8850）** | **只有 Anima 系列** |
> | 8 Gen 1 / 8+ Gen 1 | 从 v10 起不再支持（QNN 2.51 不带 V69 skel），请用更早的包 |

---

## 功能模块

| 模块 | 说明 |
|---|---|
| **8081 本地 HTTP 接口** | LocalDream 协议兼容，酒馆（SillyTavern）脚本零改动直连；支持参数继承、SSE 进度、内置自测页 |
| **中文提示词翻译** | 内置词典 + danbooru CSV + 个人词典三层，ML Kit 离线 / 本地 Hy-MT 1.8B / 云端 LLM 三种引擎；**一律先过词典**再交给模型 |
| **自定义模型导入与转换** | `.safetensors` → App 的检查点目录；转换取源文件三级兜底；导入的模型可删除 |
| **模型备份** | 与上游 1.8.3 的数据格式互通，导出/导入整包 |
| **Hires fix / 手动重绘** | 对相册或 App 里的现成图直接跑第二遍；大图可先按比例压缩再重绘 |
| **LoRA 管理** | 启用（决定加载）/ 智能（按触发词写撤标签）/ 写标签 三个开关，改提示词不重载 |
| **下载源与镜像** | 默认 hf-mirror，com↔net 自动回退，官方直连 / 自定义 |
| **参数继承** | 接口请求优先，缺省听 App 当前配置；只回写请求里真给了的字段 |

## 下载与安装

去 [Releases] 下载最新的 `HexAnima-*.apk`，直接覆盖安装（同签名）。

- 首次安装会**丢一次 App 私有目录里的模型和设置**：先备份模型，装好再导入；
- 模型的备份/导出入口在模型页的 MODEL BACKUP 卡片；
- 版本号 `versionCode` / `versionName` 沿用基础包，实际服务版本看 `GET /status` 的 `version`（`1.9.5-v73+apiNN`）。

## 8081 端口

App 启动后监听 `127.0.0.1:8081`（只绑本机回环），酒馆脚本里把 LocalDream 地址指向它即可。

> ⚠️ 如果装了 Local Dream，两边都抢 8081：同一时间只能开一个。

常用排查：`GET /status` 看是否在线，`GET /log` 看最近 400 行日志，`GET /diag` 看模型/目录是否齐全。

## 接口

### `POST /generate`（SSE，与 LocalDream 一致）

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

* 尺寸三选一：`size` / `width`+`height` / `aspect_ratio`；接口会把不合法的值修正到最近的合法尺寸（宽高 ≥256、16 的倍数、`(w/16)×(h/16)` 能被 32 整除）。
* `scheduler` / `sampler` 都认；只给 `sampler` 时调度器沿用 App 的。
* `seed` 省略则随机，返回里如实回报；**不会改写 App 里设的 seed**（`-1` 还是 `-1`）。
* 请求里没给的字段继承 App 当前配置（`inherit=[...]` 会在日志里标出来）；`null` 表示明确要空值（例如空负向词）。
* 最小请求：`{"prompt": "1girl, solo"}`。

### 响应

```
data: {"type":"progress","step":4,"total_steps":6,"percent":66}

data: {"type":"complete","image":"<base64 原始 RGB>","width":512,"height":512,"seed":123456,"generation_time_ms":13671}

data: {"type":"error","message":"..."}
```

`image` 是裸 RGB（每像素 3 字节、无行填充、无 alpha），与 LocalDream 相同，前端用 canvas 还原。

### 其它端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` `/health` `/status` | 在线探测与状态（版本、模型、是否生成中） |
| GET | `/props` | App 当前生效的 steps / cfg / sampler / 负向 / 分辨率 |
| GET | `/log` | 内存日志（最近 400 行） |
| GET | `/diag` | 诊断合集（目录、模型、脚本、底座信息） |
| GET | `/preview.png` `/last.png` | 当前预览图 / 上一张成品 |
| GET | `/test` | 内置自测页 |

## 翻译

翻译卡片夹在提示词框和反向提示词框之间，输入中文 → 停手 0.5 秒自动翻成英文并替换提示词框。

* **词典三层**：内置 23,056 条 → 导入的 danbooru CSV（默认只补缺，可切换「CSV 优先」）→ **个人词典**（软件内手动增删改，永远最高优先级）。
* **三种引擎**：ML Kit 离线（35 MB）、本地 Hy-MT 1.8B（440 MB，点输入框才加载、生图前卸载）、云端 LLM（OpenAI 兼容，模板可自定义）。
* **一律先过词典**：按逗号切段，整段被词条覆盖就直接出标签；剩下的整段交给当前引擎，回来按逗号对齐回填。
* **即时三档**：关 / 即时 / 仅词典（只替换、不调用模型）。
* **输入联想**：词典打开时，打中文会列出包含它的词条；点一下把英文追加到提示词框，长按复制英文。

## 版本说明

| 阶段 | 内容 |
|---|---|
| **接口与备份**（v1–v5） | 8081 本地接口、参数继承、模型备份/导出导入、离线目录、检查点转换、上游 1.8.3 六项功能与中文化 |
| **稳定性与 LoRA**（v6–v10） | 转换取源文件三级兜底、LoRA 三开关（启用/智能/写标签）、温控交还系统 + SoC 温度、QNN 2.51（8 Elite / 8 Elite Gen 5） |
| **下载与重绘**（v11–v16） | 下载源与主机名重写（hf-mirror + com↔net 回退）、导入模型可删、Hires fix 支持现成图片、重绘前按比例压缩 |
| **翻译模块**（v25–v37） | 内置词典 + danbooru CSV + 个人词典、ML Kit / Hy-MT / 云端三引擎、统一「先过词典」流水线、即时三档、输入联想、模板双档、加载超时与自愈、云端模型列表分页 |

更细的每次改动见 [CHANGES.md](CHANGES.md)（含构建方式与踩过的坑）。

## 构建

不依赖 Android Studio：apktool 解包基线 APK → javac/D8 编我们的类 → baksmali 覆盖 → 重新打包签名。
脚本在 `build/`，需要注意的点（aarch64 上 aapt2 要换成 arm 版、ML Kit 的依赖要一起 dex 进去等）写在
`CHANGES.md` 和 `build/mlkit/README.md` 里。

## 已知限制

* 8 Elite Gen 5（SM8850）只有 Anima 系列有内核。
* 纯文生图接口：`denoise_strength`、`show_diffusion_process`、`lora_dir` 等字段目前忽略。
* ML Kit 的模型需要能访问 Google 的网络下载一次，之后离线。
* 本地 Hy-MT 只提供 1.25bit 版本（2bit 用的是腾讯自有 65 B/256 内核，llama.cpp 上游没有）。
* 云端翻译的 `$1` 收到的是「词典没吃掉的那部分」，不是整句原文。

## 免责声明与授权

* 本仓库是**个人用途的定制版**，与上游作者无隶属关系；上游不对本仓库的任何改动负责。
* 底层算子、生图管线、模型加载来自上游 [GrimmDev-Glitch/hexanima] 与 [ayanoby/hexanima]，遵循其原始许可证。
* 本地 Hy-MT 模型来自腾讯，遵循腾讯混元社区许可（**不含**欧盟 / 英国 / 韩国；商用有 MAU 门槛），使用前请自行阅读许可文本。
* 生成内容的版权与合规责任由使用者承担；请遵守所在地区的法律法规。
* 仅供学习与个人使用，请勿用于商业分发或任何违法用途。

[HexAnima]: https://github.com/GrimmDev-Glitch/hexanima
[GrimmDev-Glitch/hexanima]: https://github.com/GrimmDev-Glitch/hexanima
[ayanoby/hexanima]: https://github.com/ayanoby/hexanima
[Releases]: https://github.com/zhanghaiqing123/hexanima/releases
