# NOTICE — 来源与修改说明

本仓库是 [ayanoby/hexanima](https://github.com/ayanoby/hexanima) 的 GitHub fork，
工作内容是在 [GrimmDev-Glitch/hexanima](https://github.com/GrimmDev-Glitch/hexanima) 的 V73 版本上追加接口与体验功能。
以下说明它建立在谁的工作之上、以及我们到底改了什么（MIT 许可证要求保留版权声明并注明修改）。

## 来源链

```
ayanoby/hexanima                      https://github.com/ayanoby/hexanima
   原版：Hexagon NPU 内核、采样器、设备端推理、App、模型包
       └─ GrimmDev-Glitch/hexanima    https://github.com/GrimmDev-Glitch/hexanima
             Snapdragon 8 Gen 2（Hexagon V73）移植与大量扩展功能
                 └─ 本仓库（fork）   https://github.com/zhanghaiqing123/hexanima
                        本地 8081 接口 + 酒馆适配 + 模型备份 + 上游 1.8.3 体验功能同步
```

* 原版版权：`Copyright (c) 2026 ayanoby`，MIT，全文见 [LICENSE](LICENSE)。
* **Snapdragon 8 Gen 2 / Hexagon V73 的移植支持来自 @GrimmDev-Glitch**（[GrimmDev-Glitch/hexanima](https://github.com/GrimmDev-Glitch/hexanima)）；
  本项目的 APK 基于其 `1.9.5-v73` 发布版，未修改任何 NPU 内核或生成算法。
* 本仓库新增的代码与文档：`Copyright (c) 2026 zhanghaiqing123`，同样以 MIT 发布（LICENSE 条款适用于这些新增部分）。

## 我们改了什么

### v1–v3（二进制补丁方式）

1. **新增类** `com.hexanima.npu.ApiServer`：本地 HTTP/SSE 接口（8081）。
2. **新增三个 smali 调用点**（`build/patch_hooks.py`，历史存档）：
   `Main.onCreate → ApiServer.start`、`MainRun.setProgressUi → ApiServer.onProgress`、`Main.lambda$generate$132 → ApiServer.onFinish`。
3. v3 追加 `com.hexanima.npu.Backup`、`Ckpt` 与模型页的 5 个调用点（备份卡片、离线目录、SAF 回执）。

### v4（源码重编整类）

v4 起不再用 smali 钩子，直接改 fork 源码后重编以下类（源码在 `repo/forkmod/com/hexanima/npu/`）：

* `Main`、`MainRun`、`MainTools`、`Settings`、`Gallery`、`Viewer`、`ModelsActivity`
* 新增 `Tr`（本改造文案的中/英选择）、`Timings`（时间预估学习）

以及：

* 新增类 `ApiServer`、`Backup`、`Ckpt`（源码在 `src/com/hexanima/npu/`）；
* 修改 `assets/anima.sh`：等待 NPU 服务/采样器启动最多 5 分钟，失败写 `files/anima/load_error` 并退出；
* 重新编译 `classes.dex`，`resources.arsc`、`AndroidManifest.xml`、`lib/` 与上游 APK 保持逐字节一致；
* 用项目维护者自己的密钥重新签名（不是上游的），因此本 APK 与原版**无法共存**，安装前必须卸载原版。

## 第三方

本仓库不包含任何模型文件、模型包或第三方库的源码。APK 内打包的第三方组件与上游发布版相同，清单、许可与声明请见：

* https://github.com/GrimmDev-Glitch/hexanima/blob/main/THIRD_PARTY.md
* https://github.com/ayanoby/hexanima/blob/main/THIRD_PARTY.md

特别提醒：Anima / MIAOKA 系列模型为**非商用**许可（不得售卖生成图片），Qwen-Image 为研究许可。使用前请阅读对应模型页的条款。
