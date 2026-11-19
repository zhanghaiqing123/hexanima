#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把三个 smali 钩子补进 apktool 解出来的目录里（幂等）。

用法:
    python3 patch_hooks.py /tmp/ha_p

前置:
    apktool d -r <原版 apk> -o /tmp/ha_r
    cp -a /tmp/ha_r /tmp/ha_p
    cp src/com/hexanima/npu/ApiServer*.smali 由 build.sh 完成（它会自己复制）

三个钩子:
    Main.onCreate（super 之后）        -> ApiServer.start(Context)
    MainRun.setProgressUi 开头          -> ApiServer.onProgress(I)
    Main.lambda$generate$132 开头       -> ApiServer.onFinish(Object,ZZZLBitmap)
"""
import io
import sys
import os

HOOK_START = "    invoke-static/range {p0 .. p0}, Lcom/hexanima/npu/ApiServer;->start(Landroid/content/Context;)V\n"
HOOK_PROGRESS = "    invoke-static/range {p1 .. p1}, Lcom/hexanima/npu/ApiServer;->onProgress(I)V\n"
HOOK_FINISH = ("    invoke-static {p0, p1, p2, p5, p7}, "
               "Lcom/hexanima/npu/ApiServer;->onFinish(Ljava/lang/Object;ZZZLandroid/graphics/Bitmap;)V\n")


def patch(path, anchor, hook, tail=""):
    with io.open(path, encoding="utf-8") as f:
        s = f.read()
    if hook in s:
        print("skip (already patched):", path)
        return
    if s.count(anchor) != 1:
        raise SystemExit("anchor not unique in %s (%d)" % (path, s.count(anchor)))
    s = s.replace(anchor, anchor + "\n" + hook)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(s)
    print("patched:", path)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ha_p"
    pkg = os.path.join(root, "smali", "com", "hexanima", "npu")

    patch(
        os.path.join(pkg, "Main.smali"),
        "    invoke-super/range {p0 .. p1}, Lcom/hexanima/npu/MainEdit;->onCreate(Landroid/os/Bundle;)V\n",
        HOOK_START,
    )
    patch(
        os.path.join(pkg, "MainRun.smali"),
        ".method setProgressUi(ILjava/lang/String;J)V\n    .locals 17\n",
        HOOK_PROGRESS,
    )
    patch(
        os.path.join(pkg, "Main.smali"),
        ".method synthetic lambda$generate$132$com-hexanima-npu-Main(ZZDZLandroid/content/Context;"
        "Landroid/graphics/Bitmap;Ljava/lang/String;I)V\n    .locals 4\n",
        HOOK_FINISH,
    )


if __name__ == "__main__":
    main()
