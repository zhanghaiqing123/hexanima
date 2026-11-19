package com.hexanima.npu;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Convert Anima checkpoints (.safetensors) that live in a folder on the phone, without copying the several-GB
 * source into the app first: the file is handed to libanimaconv.so through /proc/self/fd/<n> and only the
 * converted blocks are written. Falls back to a copy when the provider does not give a real file.
 *
 * The model is identified exactly like libanimaconv.so does it: the file must contain the tensor
 * "blocks.0.self_attn.q_proj.weight" with shape [2048, 2048] under some prefix ("model.diffusion_model." most of
 * the time), and the highest "blocks.N." index tells whether it is Anima 2B (28) or 2.9B (40).
 *
 * The sd.cpp stub is built here from the checkpoint's own header (8-byte length + JSON header, then a sparse
 * hole up to the real size), and that header is kept as hdr.bin inside the model folder, the same way catalog
 * models keep it, so that the model backup/export can rebuild the stub later.
 */
final class Ckpt {
    static final String[] EXT = {".safetensors"};
    /** the tensor libanimaconv.so looks for to recognise an Anima DiT */
    static final String KEY = "blocks.0.self_attn.q_proj.weight";

    static final class Item {
        Uri uri;
        String name = "";
        long size;
        int blocks;
        long headerLen;
        String prefix = "";
        String dtype = "";
        String error;
        CheckBox box;
        boolean valid() { return blocks == 28 || blocks == 40; }
        /** one q8 block of a converted 2B/2.9B model: the same size the catalog models have (28 x 83 MB + small) */
        long blockBytes() { return 83365888L; }
        long need() { return blockBytes() * Math.max(blocks, 28) + 310000000L; }
    }

    static String S(Activity a, String en, String zh) { return Backup.S(a, en, zh); }
    static String F(Activity a, String en, String zh, Object... args) { return Backup.F(a, en, zh, args); }

    // ---------------------------------------------------------------- scan
    static void scan(final Activity a, final Uri tree) {
        Backup.progress(a, S(a, "Scanning the folder\u2026", "正在扫描文件夹\u2026"));
        new Thread(new Runnable() {
            public void run() {
                final List<Item> found = new ArrayList<Item>();
                try {
                    ContentResolver cr = a.getContentResolver();
                    final java.util.Map<String, Uri> docs = new java.util.HashMap<String, Uri>();
                    Backup.listTree(cr, tree, android.provider.DocumentsContract.getTreeDocumentId(tree), "", docs);
                    List<String> names = new ArrayList<String>(docs.keySet());
                    java.util.Collections.sort(names);
                    int n = 0;
                    for (String path : names) {
                        String low = path.toLowerCase(java.util.Locale.ROOT);
                        boolean ok = false;
                        for (String e : EXT) if (low.endsWith(e)) ok = true;
                        if (!ok) continue;
                        if (++n > 200) break;
                        Item it = new Item();
                        it.uri = docs.get(path);
                        it.name = path.substring(path.lastIndexOf('/') + 1);
                        try { probe(cr, it); } catch (Throwable t) { it.error = String.valueOf(t.getMessage() != null ? t.getMessage() : t); }
                        found.add(it);
                    }
                } catch (Throwable t) {
                    final String m = String.valueOf(t);
                    Backup.UI.post(new Runnable() { public void run() { Backup.done(a, F(a, "Scan failed: %s", "扫描失败：%s", m)); } });
                    return;
                }
                Backup.UI.post(new Runnable() { public void run() { list(a, found); } });
            }
        }).start();
    }

    /** read the safetensors header (first 8 bytes = length, then JSON) and count the Anima blocks */
    static void probe(ContentResolver cr, Item it) throws Exception {
        ParcelFileDescriptor pfd = cr.openFileDescriptor(it.uri, "r");
        if (pfd == null) throw new IOException("cannot open");
        it.size = pfd.getStatSize();
        if (it.size <= 0) throw new IOException("empty file");
        byte[] head = new byte[8];
        InputStream in = cr.openInputStream(it.uri);
        try {
            readFully(in, head);
            long n = u64(head);
            if (n <= 0 || n > (16 << 20)) throw new IOException("not a safetensors file (header " + n + ")");
            byte[] js = new byte[(int) n];
            readFully(in, js);
            it.headerLen = 8 + n;
            JSONObject j = new JSONObject(new String(js, "UTF-8"));
            String prefix = null;
            java.util.Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k.endsWith(KEY)) { prefix = k.substring(0, k.length() - KEY.length()); break; }
            }
            if (prefix == null) { it.error = "no blocks.0.self_attn.q_proj.weight"; return; }
            it.prefix = prefix;
            JSONObject q = j.optJSONObject(prefix + KEY);
            JSONArray shp = q == null ? null : q.optJSONArray("shape");
            if (shp == null || shp.length() != 2 || shp.getInt(0) != 2048 || shp.getInt(1) != 2048) { it.error = "width is not 2048"; return; }
            it.dtype = q.optString("dtype", "");
            int max = -1;
            keys = j.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.startsWith(prefix + "blocks.")) continue;
                String rest = k.substring(prefix.length() + 7);
                int dot = rest.indexOf('.');
                if (dot <= 0) continue;
                try {
                    int b = Integer.parseInt(rest.substring(0, dot));
                    if (b > max) max = b;
                } catch (Throwable ignored) { }
            }
            it.blocks = max + 1;
            if (it.blocks != 28 && it.blocks != 40) it.error = "unknown architecture (" + it.blocks + " blocks)";
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { pfd.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- the list dialog
    static void list(final Activity a, final List<Item> found) {
        try { if (Backup.progDlg != null) Backup.progDlg.dismiss(); } catch (Throwable ignored) { }
        Backup.progDlg = null;
        Base b = (Base) a;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(b.dp(18), b.dp(14), b.dp(18), b.dp(16));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new LinearLayout.LayoutParams(-1, -2));
        final android.app.AlertDialog d = b.builder().setView(sv).create();
        d.show();

        col.addView(b.text(F(a, "%d .safetensors file(s) in this folder", "此文件夹中有 %d 个 .safetensors 文件", found.size()), 14, Base.TXT, true));
        TextView hint = b.text(S(a, "Pick the ones to convert. The source file stays where it is; only the converted blocks are written to the app.",
            "选择要转换的模型。原文件留在原处，只有转换后的分块会写入应用。"), 12, Base.DIM, false);
        hint.setPadding(0, b.dp(6), 0, b.dp(8));
        col.addView(hint);
        if (found.isEmpty()) col.addView(b.text(S(a, "No .safetensors file here.", "这里没有 .safetensors 文件。"), 13, Base.DIM, false));

        final List<Item> pick = new ArrayList<Item>();
        for (final Item it : found) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, b.dp(4), 0, b.dp(4));
            it.box = new CheckBox(a);
            it.box.setChecked(it.valid());
            it.box.setEnabled(it.valid());
            row.addView(it.box);
            LinearLayout inf = new LinearLayout(a);
            inf.setOrientation(LinearLayout.VERTICAL);
            inf.addView(b.text(it.name, 13.5f, it.valid() ? Base.TXT : Base.BAD, true));
            String meta;
            if (it.valid()) {
                meta = (it.blocks == 40 ? "2.9B (40)" : "2B (28)")
                    + (it.dtype.length() > 0 ? "  \u00b7  " + it.dtype : "")
                    + "  \u00b7  " + Backup.gb(it.size) + " GB"
                    + "  \u00b7  " + F(a, "needs %s GB", "需要 %s GB", Backup.gb(it.need()));
            } else {
                meta = reason(a, it);
            }
            inf.addView(b.text(meta, 11.5f, Base.DIM, false));
            row.addView(inf, new LinearLayout.LayoutParams(0, -2, 1));
            col.addView(row);
            if (it.valid()) pick.add(it);
        }

        Button go = b.button(S(a, "Convert selected", "转换所选"), Base.ACC, Base.ON_ACC);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<Item> sel = new ArrayList<Item>();
                for (Item it : pick) if (it.box.isChecked()) sel.add(it);
                if (sel.isEmpty()) return;
                d.dismiss();
                convert(a, sel);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, b.dp(44));
        lp.topMargin = b.dp(12);
        col.addView(go, lp);
        Button cancel = b.button(S(a, "Cancel", "取消"), Base.FIELD, Base.TXT);
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, b.dp(44));
        lp2.topMargin = b.dp(8);
        col.addView(cancel, lp2);
    }

    /** the "not convertible" line under a file name, in the user's language */
    static String reason(Activity a, Item it) {
        String e = it.error == null ? "unsupported" : it.error;
        if (e.startsWith("no blocks.0.self_attn.q_proj.weight"))
            return S(a, "not an Anima DiT", "不是 Anima 模型（缺少 blocks.0.self_attn.q_proj.weight）");
        if (e.startsWith("width is not 2048"))
            return S(a, "width is not 2048 (another architecture)", "宽度不是 2048，属于其它架构");
        if (e.startsWith("unknown architecture"))
            return S(a, "only Anima 2B (28) and 2.9B (40) are supported", "只支持 Anima 2B（28 块）和 2.9B（40 块）：" + e.replaceAll(".*\\((\\d+) blocks\\).*", "$1") + " 块");
        if (e.startsWith("not a safetensors file"))
            return S(a, "not a safetensors file", "不是有效的 safetensors 文件");
        if (e.startsWith("empty file")) return S(a, "empty file", "空文件");
        if (e.contains("ENOENT") || e.contains("No such file")) return S(a, "file is gone", "文件已不存在");
        return e;
    }

    // ---------------------------------------------------------------- convert
    static void convert(final Activity a, final List<Item> items) {
        Backup.progress(a, S(a, "Converting\u2026", "正在转换\u2026"));
        new Thread(new Runnable() {
            public void run() {
                final List<String> ok = new ArrayList<String>();
                final List<String> errs = new ArrayList<String>();
                int k = 0;
                for (Item it : items) {
                    k++;
                    if (items.size() > 1)
                        Backup.pset(F(a, "File %1$d of %2$d: %3$s", "第 %1$d/%2$d 个：%3$s", k, items.size(), it.name), Backup.permille(k - 1, items.size()));
                    String err;
                    try { err = convertOne(a, it); }
                    catch (Throwable t) { err = String.valueOf(t.getMessage() != null ? t.getMessage() : t); }
                    if (err == null) ok.add(it.name);
                    else errs.add(it.name + ": " + err);
                }
                final String msg;
                if (errs.isEmpty()) msg = F(a, "Converted %d model(s)", "已转换 %d 个模型", ok.size());
                else if (ok.isEmpty()) msg = F(a, "Conversion failed: %s", "转换失败：%s", errs.get(0));
                else msg = F(a, "Converted %d, failed %d: %s", "成功 %d 个，失败 %d 个：%s", ok.size(), errs.size(), errs.get(0));
                Backup.UI.post(new Runnable() { public void run() { Backup.done(a, msg); } });
            }
        }).start();
    }

    /** @return null on success, else the reason */
    static String convertOne(Activity a, Item it) throws Exception {
        File home = a.getFilesDir();
        Models store = new Models(home);
        String id = uniqueId(store, home, it.name);
        File dir = new File(new File(home, "anima"), "m_" + id);
        dir.mkdirs();
        store.doneMark(dir).delete();
        long need = it.need();
        if (home.getUsableSpace() < need) {
            Models.deleteTree(dir);
            return F(a, "Not enough space: about %s GB needed", "空间不足：约需 %s GB", Backup.gb(need));
        }
        if (!store.commonInstalled()) {
            Models.deleteTree(dir);
            return S(a, "Download the base files first (text encoder and VAE): a model does not run without them",
                "请先下载基础文件（文本编码器与 VAE）：没有它们模型无法运行");
        }
        File bin = new File(a.getApplicationInfo().nativeLibraryDir, "libanimaconv.so");
        if (!bin.isFile()) { Models.deleteTree(dir); return S(a, "libanimaconv.so is missing", "缺少 libanimaconv.so，无法转换"); }

        ContentResolver cr = a.getContentResolver();
        ParcelFileDescriptor pfd = cr.openFileDescriptor(it.uri, "r");
        if (pfd == null) { Models.deleteTree(dir); return S(a, "cannot open the file", "打不开这个文件"); }
        File tmp = null;
        try {
            // the converter is a separate process: a SAF file has no path it can open, so try, in order,
            // (1) the real path of the document, (2) the inherited descriptor, (3) a copy inside the app
            String err = null;
            String real = realPath(it.uri);
            String src = null;
            if (real != null) {
                File f = new File(real);
                if (f.isFile() && f.canRead() && f.length() == it.size) src = real;
            }
            if (src != null) err = run(a, bin, src, dir.getPath(), it.blocks);
            if (src == null || (err != null && err.startsWith("cannot read"))) {
                String fdSrc = "/proc/" + android.os.Process.myPid() + "/fd/" + pfd.getFd();
                if (src == null) err = run(a, bin, fdSrc, dir.getPath(), it.blocks);
            }
            if (err != null && err.startsWith("cannot read")) {   // no path the converter can open: convert a copy
                long needCopy = it.size + need + 50000000L;
                if (home.getUsableSpace() < needCopy) {
                    Models.deleteTree(dir);
                    return F(a, "Not enough space for a copy: %s GB (the source is %s GB, the model %s GB)",
                        "空间不足：需要 %s GB 来复制一份（源文件 %s GB + 模型 %s GB）",
                        Backup.gb(needCopy), Backup.gb(it.size), Backup.gb(need));
                }
                tmp = new File(Backup.importTemp(home));
                tmp.getParentFile().mkdirs();
                Backup.pset(F(a, "The folder provider gives no path: copying %s GB into the app\u2026",
                    "该系统目录不提供文件路径：正在复制 %s GB 到应用内\u2026", Backup.gb(it.size)), 0);
                copyWithProgress(a, cr, it, tmp);
                Models.deleteTree(dir);
                dir.mkdirs();
                err = run(a, bin, tmp.getPath(), dir.getPath(), it.blocks);
            }
            if (err != null) { Models.deleteTree(dir); return converterError(a, err); }
            // the safetensors header of the checkpoint: kept as hdr.bin (like catalog models) and used to build the
            // sparse sd.cpp stub of the real size, so no catalog download is needed
            File hdr = new File(dir, "hdr.bin");
            writeHdr(cr, it, hdr);
            File stub = new File(new File(home, "sd"), id + ".safetensors");
            stub.getParentFile().mkdirs();
            writeStub(hdr, stub, it.size);
            String name = it.name.replaceAll("(?i)\\.safetensors$", "");
            boolean turbo = name.toLowerCase(java.util.Locale.ROOT).matches(".*(turbo|4.?step|distilled|lightning|hyper|schnell).*");
            JSONObject meta = new JSONObject().put("id", id).put("name", name)
                .put("speed", it.blocks == 40 ? 12 : 7).put("imported", true).put("turbo", turbo);
            java.io.FileWriter w = new java.io.FileWriter(new File(dir, "meta.json"));
            try { w.write(meta.toString()); } finally { try { w.close(); } catch (Throwable ignored) { } }
            new File(dir, ".custom").createNewFile();   // marker of a checkpoint converted on the phone
            store.doneMark(dir).createNewFile();
            return null;
        } finally {
            try { pfd.close(); } catch (Throwable ignored) { }
            if (tmp != null) tmp.delete();
        }
    }

    /** runs the converter and parses "blocks N", "blk i/N"; returns null on success */
    static String run(Activity a, File bin, String src, String out, int blocks) throws Exception {
        Process p = new ProcessBuilder(bin.getPath(), src, out, "q8").redirectErrorStream(true).start();
        String err = null;
        int seen = -1;
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("blocks ")) {
                    try { seen = Integer.parseInt(l.split(" ")[1]); } catch (Throwable ignored) { }
                } else if (l.startsWith("blk ")) {
                    String[] q = l.substring(4).split("/");
                    try { Backup.pset(F(a, "Block %s of %s", "第 %s / %s 块", q[0], q[1]), Backup.permille(Integer.parseInt(q[0]), Integer.parseInt(q[1]))); }
                    catch (Throwable ignored) { }
                } else if (l.startsWith("error")) {
                    err = l.substring(5).trim();
                }
            }
        } finally { try { r.close(); } catch (Throwable ignored) { } }
        p.waitFor();
        if (err != null) return err;
        if (p.exitValue() != 0) return F(a, "converter exit %d", "转换器退出码 %d（可能内存或空间不足）", p.exitValue());
        if (seen != blocks) return F(a, "converter wrote %d blocks, expected %d", "转换器写出 %d 块，与预期的 %d 块不一致", seen, blocks);
        return null;
    }

    /** libanimaconv.so reports in English; give the common cases a readable message */
    static String converterError(Activity a, String err) {
        if (err.contains("cannot read")) return S(a, "cannot read the file: the folder provider does not expose a real file", "无法读取文件：这个目录提供方不提供真实文件");
        if (err.contains("cannot write")) return S(a, "cannot write: not enough space", "无法写入：空间不足");
        if (err.startsWith("not an Anima DiT")) return S(a, "not an Anima DiT", "不是 Anima 模型");
        if (err.startsWith("width is not 2048")) return S(a, "width is not 2048 (another architecture)", "宽度不是 2048，属于其它架构");
        if (err.startsWith("missing adaln")) return S(a, "the file has no adaln tensors: it may be a quantized or pruned export", "文件缺少 adaln 张量：可能是量化版或被裁剪过的导出");
        if (err.startsWith("missing")) return F(a, "the file is incomplete: %s", "文件不完整：%s", err.replaceFirst("^missing ", ""));
        if (err.startsWith("dtype of")) return F(a, "unsupported tensor type: %s", "不支持的张量类型：%s", err.replaceFirst("^dtype of ", ""));
        return err;
    }

    /** the real file behind a SAF document, when the storage provider is the primary external volume */
    static String realPath(Uri uri) {
        try {
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
            String doc = android.provider.DocumentsContract.getDocumentId(uri);
            if (doc == null) return null;
            if (doc.startsWith("primary:")) doc = doc.substring("primary:".length());
            else {
                int c = doc.indexOf(':');
                if (c <= 0) return null;
                String vol = doc.substring(0, c);
                doc = doc.substring(c + 1);
                if (vol.length() != 9 || !vol.contains("-")) return null;   // a removable card: /storage/XXXX-XXXX
                return "/storage/" + vol + "/" + doc;
            }
            return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + doc;
        } catch (Throwable t) { return null; }
    }

    /** the source has no path the converter can open: copy it, showing the bytes as they go */
    static void copyWithProgress(Activity a, ContentResolver cr, Item it, File out) throws Exception {
        InputStream in = cr.openInputStream(it.uri);
        OutputStream o = new FileOutputStream(out);
        long done = 0, last = 0;
        try {
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 400) {
                    last = now;
                    Backup.pset(F(a, "Copying %1$s / %2$s GB", "正在复制 %1$s / %2$s GB",
                        Backup.gb(done), Backup.gb(it.size)), Backup.permille(done, it.size));
                }
            }
        } finally {
            try { o.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** the first headerLen bytes of the checkpoint: the 8-byte length + the JSON header, nothing else */
    static void writeHdr(ContentResolver cr, Item it, File out) throws Exception {
        out.getParentFile().mkdirs();
        InputStream in = cr.openInputStream(it.uri);
        FileOutputStream o = new FileOutputStream(out);
        try {
            byte[] buf = new byte[65536];
            long left = it.headerLen;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r <= 0) throw new IOException("short read on the header");
                o.write(buf, 0, r);
                left -= r;
            }
        } finally {
            try { o.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** hdr.bin -> sparse stub of the real size (what sd.cpp opens) */
    static void writeStub(File hdr, File out, long size) throws Exception {
        out.getParentFile().mkdirs();
        InputStream in = new FileInputStream(hdr);
        FileOutputStream o = new FileOutputStream(out);
        try { copy(in, o); } finally { try { o.close(); } catch (Throwable ignored) { } try { in.close(); } catch (Throwable ignored) { } }
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try { raf.setLength(size); } finally { try { raf.close(); } catch (Throwable ignored) { } }
    }

    static String uniqueId(Models store, File home, String name) {
        String base = name.replaceAll("(?i)\\.safetensors$", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.length() < 3) base = "model";
        if (base.length() > 20) base = base.substring(0, 20);
        String id = "my" + base;
        File dir = store.modelDir(id);
        int n = 2;
        while (dir.exists() || new File(new File(home, "sd"), id + ".safetensors").exists()) { id = "my" + base + n; n++; }
        return id;
    }

    static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] b = new byte[1 << 20];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) {
            int n = in.read(b, o, b.length - o);
            if (n <= 0) throw new IOException("short read");
            o += n;
        }
    }

    static long u64(byte[] b) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }
}
