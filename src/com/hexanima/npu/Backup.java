package com.hexanima.npu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model backup: export installed models to a folder the user picks (SAF), restore them, and an offline copy
 * of the catalog. Nothing is run from the external folder: the NPU reads the weights from the app's own
 * storage, so this is a backup that comes back without a download.
 *
 * Folder layout (identical to upstream HexAnima 1.8.3, so the two can exchange backups):
 *   HexAnima-<id>/anima/m_<id>/blkNN.bin ... plus hexanima-model.json (relative paths, size, sha256, written last).
 */
final class Backup {
    static final String MANIFEST = "hexanima-model.json";
    static final String[] ROOTS = {"anima/", "sd/", "moss/", "qwen/", "mage/", "ltx/"};
    static final int REQ_EXPORT = 9201, REQ_EXPORT_ALL = 9202, REQ_IMPORT = 9203, REQ_CKPT = 9204;

    /** English and Chinese for everything this screen shows: Tr follows the phone language (Chinese on a Chinese phone),
     *  and the dialog has a button that switches it. The rest of the app keeps its own languages. */
    static String S(android.content.Context c, String en, String zh) { return Tr.s(c, en, zh); }
    static String F(android.content.Context c, String en, String zh, Object... args) { return String.format(Tr.s(c, en, zh), args); }

    static final Handler UI = new Handler(Looper.getMainLooper());
    static AlertDialog dlg, progDlg;
    static ProgressBar progBar;
    static TextView progMsg;
    static String pendingId;
    static int pendingReq;

    // ---------------------------------------------------------------- entry: a card on the Models screen
    /** inserted at the top of the model list by render() (see patch_hooks.py) */
    static java.lang.ref.WeakReference<android.view.View> lastCard;

    static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    static void log(Activity a, String s) {
        try {
            File f = new File(a.getFilesDir(), "backup.log");
            if (f.length() > 100000) f.delete();
            FileOutputStream o = new FileOutputStream(f, true);
            try {
                String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date());
                o.write((ts + " " + s + "\n").getBytes("UTF-8"));
            } finally { try { o.close(); } catch (Throwable ignored) { } }
        } catch (Throwable ignored) {
        }
    }

    static void header(ModelsActivity a) {
        try {
            View card = buildCard(a);
            a.list.addView(card, 0);
            lastCard = new java.lang.ref.WeakReference<android.view.View>(card);
            log(a, "header ok children=" + a.list.getChildCount());
        } catch (Throwable t) {
            log(a, "header FAILED " + stack(t));
        }
    }

    static View buildCard(Activity a) {
        Base b = (Base) a;
        LinearLayout scratch = new LinearLayout(a);
        LinearLayout card = b.card(scratch);
        scratch.removeView(card);   // card() attaches it to the throw-away parent; the list needs it unattached
        card.addView(b.label(S(a, "MODEL BACKUP", "模型备份")));
        TextView t = b.text(S(a,
            "Export installed models to a folder (SD card, USB, PC), restore them later, or convert .safetensors files from a folder.",
            "把已安装的模型导出到文件夹（SD 卡、U 盘、电脑），之后可以导回来；也能把文件夹里的 .safetensors 转换安装。"), 13, Base.DIM, false);
        t.setPadding(0, b.dp(4), 0, 0);
        card.addView(t);
        Button open = b.button(S(a, "Open backup", "打开备份"), Base.ACC, Base.ON_ACC);
        open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { open(a); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, b.dp(44));
        lp.topMargin = b.dp(10);
        card.addView(open, lp);
        return card;
    }

    /** called from ModelsActivity.load(): a safety net in case render() never runs (for example a blocked catalog) */
    static void onLoad(ModelsActivity a) {
        try { log(a, "load() called"); } catch (Throwable ignored) { }
        try {
            a.list.postDelayed(new Runnable() {
                public void run() {
                    try {
                        if (a.isFinishing()) return;
                        View c = lastCard == null ? null : lastCard.get();
                        boolean attached = c != null && c.getParent() == a.list;
                        log(a, "check attached=" + attached + " children=" + a.list.getChildCount());
                        if (!attached) header(a);
                    } catch (Throwable t) {
                        log(a, "check FAILED " + stack(t));
                    }
                }
            }, 1500);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- the backup dialog
    static void open(final Activity a) {
        dismiss();
        Base b = (Base) a;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(b.dp(18), b.dp(14), b.dp(18), b.dp(16));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new LinearLayout.LayoutParams(-1, -2));
        dlg = b.builder().setView(sv).create();
        dlg.show();
        body(a, col);
    }

    static void dismiss() {
        try { if (dlg != null && dlg.isShowing()) dlg.dismiss(); } catch (Throwable ignored) { }
        dlg = null;
    }

    static void body(final Activity a, LinearLayout col) {
        Base b = (Base) a;
        col.removeAllViews();
        final Models st = new Models(a.getFilesDir());
        List<String> ids;
        try { ids = st.installed(); } catch (Throwable t) { ids = new ArrayList<String>(); }
        long total = 0;
        for (String id : ids) total += size(st, id);

        TextView info = b.text(F(a, "Installed: %d model(s) \u00b7 %s GB \u00b7 free space: %s GB",
            "已安装 %d 个模型 \u00b7 %s GB \u00b7 剩余空间 %s GB", ids.size(), gb(total), gb(a.getFilesDir().getUsableSpace())), 12, Base.DIM, false);
        info.setPadding(0, b.dp(2), 0, b.dp(10));
        col.addView(info);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        col.addView(row);
        if (ids.isEmpty()) row.addView(b.text(S(a, "No models installed.", "没有已安装的模型。"), 13, Base.TXT, false));
        for (final String id : ids) {
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, b.dp(4), 0, b.dp(4));
            LinearLayout names = new LinearLayout(a);
            names.setOrientation(LinearLayout.VERTICAL);
            String name;
            try { name = st.name(id); } catch (Throwable t) { name = id; }
            TextView nm = b.text(name, 14, Base.TXT, true);
            names.addView(nm);
            names.addView(b.text(id + "  \u00b7  " + gb(size(st, id)) + " GB", 11.5f, Base.DIM, false));
            r.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            Button ex = b.button(S(a, "Export", "导出"), Base.FIELD, Base.TXT);
            ex.setTextSize(13);
            ex.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pendingId = id; pendingReq = REQ_EXPORT; startTree(a, REQ_EXPORT); }
            });
            r.addView(ex, new LinearLayout.LayoutParams(b.dp(96), b.dp(38)));
            row.addView(r);
        }

        col.addView(b.label(S(a, "ACTIONS", "操作")));
        Button all = b.button(S(a, "Export all to a folder\u2026", "全部导出到文件夹\u2026"), Base.ACC, Base.ON_ACC);
        all.setEnabled(!ids.isEmpty());
        all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pendingId = null; startTree(a, REQ_EXPORT_ALL); }
        });
        col.addView(all, lpb(b, 8));
        Button quick = b.button(S(a, "Export all to Download/HexAnimaBackup", "全部导出到 Download/HexAnimaBackup"), Base.FIELD, Base.TXT);
        quick.setEnabled(!ids.isEmpty());
        quick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportDownloads(a); }
        });
        col.addView(quick, lpb(b, 8));
        Button imp = b.button(S(a, "Import a model folder\u2026", "导入模型文件夹\u2026"), Base.FIELD, Base.TXT);
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startTree(a, REQ_IMPORT); }
        });
        col.addView(imp, lpb(b, 8));
        Button ck = b.button(S(a, "Convert .safetensors from a folder\u2026", "转换文件夹里的 .safetensors\u2026"), Base.FIELD, Base.TXT);
        ck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startTree(a, REQ_CKPT); }
        });
        col.addView(ck, lpb(b, 8));

        TextView hint = b.text(S(a,
            "The weights are copied, not moved: generation keeps reading them from the app's own storage. Exporting needs the same free space again.",
            "权重是复制而不是移动：生成仍然从应用自己的存储读取。导出需要再空出同样大小的空间。"), 11.5f, Base.DIM, false);
        hint.setPadding(0, b.dp(12), 0, 0);
        col.addView(hint);

        Button close = b.button(S(a, "Close", "关闭"), Base.FIELD, Base.TXT);
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dismiss(); } });
        col.addView(close, lpb(b, 14));
        Button lang = b.button(Tr.zh(a) ? "English" : "\u4e2d\u6587", Base.CARD, Base.DIM);   // switch the language of this screen only
        lang.setTextSize(13);
        lang.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Tr.set(a, !Tr.zh(a)); dismiss(); open(a); } });
        col.addView(lang, lpb(b, 8));
    }

    static LinearLayout.LayoutParams lpb(Base b, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, b.dp(44));
        lp.topMargin = b.dp(top);
        return lp;
    }

    /** the system folder picker: read + write + persistable */
    static void startTree(Activity a, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { a.startActivityForResult(i, req); }
        catch (Throwable t) { toast(a, "No folder picker: " + t); }
    }

    // ---------------------------------------------------------------- results
    static void onResult(Activity a, int req, int res, Intent data) {
        if (req < REQ_EXPORT || req > REQ_CKPT) return;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            a.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) { }
        if (req == REQ_EXPORT) exportRun(a, tree, pendingId);
        else if (req == REQ_EXPORT_ALL) exportRun(a, tree, null);
        else if (req == REQ_IMPORT) importRun(a, tree);
        else if (req == REQ_CKPT) Ckpt.scan(a, tree);
    }

    // ---------------------------------------------------------------- export
    static void exportRun(final Activity a, final Uri tree, final String only) {
        final Models st = new Models(a.getFilesDir());
        final List<String> ids;
        try { ids = only != null ? Collections.singletonList(only) : st.installed(); }
        catch (Throwable t) { toast(a, S(a, "Cannot list models: ", "无法读取模型列表：") + t); return; }
        if (ids.isEmpty()) { toast(a, S(a, "No models to export", "没有可导出的模型")); return; }
        long total = 0;
        for (String id : ids) total += size(st, id);
        long free = sdFree();
        if (free > 0 && free < total) {
            toast(a, F(a, "Not enough space on the target: %s GB needed", "目标位置空间不足：需要 %s GB", gb(total)));
            return;
        }
        progress(a, S(a, "Exporting\u2026", "正在导出\u2026"));
        new Thread(new Runnable() {
            public void run() {
                long done = 0, totalBytes = 0;
                for (String id : ids) totalBytes += size(st, id);
                List<String> errs = new ArrayList<String>();
                for (String id : ids) {
                    try { exportOne(a, st, tree, id, done, totalBytes); }
                    catch (Throwable t) { errs.add(id + ": " + t); }
                    done += size(st, id);
                }
                final String msg = errs.isEmpty()
                    ? F(a, "Exported %d model(s)", "已导出 %d 个模型", ids.size())
                    : F(a, "Done with %d error(s): ", "有 %d 个错误：", errs.size()) + errs.get(0);
                UI.post(new Runnable() { public void run() { done(a, msg); } });
            }
        }).start();
    }

    static void exportOne(Activity a, Models st, Uri tree, String id, long doneBase, long totalBytes) throws Exception {
        ContentResolver cr = a.getContentResolver();
        Uri top = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        File home = a.getFilesDir();
        File dir = modelDir(st, home, id);
        if (dir == null || !dir.isDirectory()) throw new IOException("no files for " + id);
        List<String> rels = walk(home, dir);
        JSONArray fa = new JSONArray();
        String folder = "HexAnima-" + id.replaceAll("[^A-Za-z0-9._-]", "_");
        Uri root = DocumentsContract.createDocument(cr, top, DocumentsContract.Document.MIME_TYPE_DIR, folder);
        if (root == null) throw new IOException("could not create " + folder);
        try {
            Map<String, Uri> dirs = new HashMap<String, Uri>();
            dirs.put("", root);
            long done = doneBase;
            byte[] buf = new byte[1 << 20];
            for (String p : rels) {
                File src = new File(home, p);
                String parent = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "";
                Uri pd = dir(cr, dirs, root, parent);
                if (pd == null) throw new IOException("could not create folder " + parent);
                Uri dst = DocumentsContract.createDocument(cr, pd, "application/octet-stream", p.substring(p.lastIndexOf('/') + 1));
                if (dst == null) throw new IOException("could not create " + p);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long n = 0;
                InputStream in = new FileInputStream(src);
                OutputStream o = cr.openOutputStream(dst);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        o.write(buf, 0, r);
                        md.update(buf, 0, r);
                        n += r;
                        done += r;
                        pset(p + "  " + gb(done) + " / " + gb(totalBytes) + " GB", permille(done, totalBytes));
                    }
                } finally {
                    try { o.close(); } catch (Throwable ignored) { }
                    try { in.close(); } catch (Throwable ignored) { }
                }
                fa.put(new JSONObject().put("p", p).put("size", n).put("sha", hex(md.digest())));
            }
            String name;
            try { name = st.name(id); } catch (Throwable t) { name = id; }
            JSONObject m = new JSONObject().put("format", 1).put("id", id).put("name", name).put("files", fa);
            File stub = new File(home, "sd/" + id + ".safetensors");
            File hdr = new File(dir, "hdr.bin");
            if (stub.exists() && stub.length() > 0 && hdr.isFile() && hdr.length() > 0) {
                m.put("stub", new JSONObject()
                    .put("hdr", "anima/m_" + id + "/hdr.bin")
                    .put("out", "sd/" + id + ".safetensors")
                    .put("size", stub.length()));
            }
            Uri mf = DocumentsContract.createDocument(cr, root, "application/json", MANIFEST);
            if (mf == null) throw new IOException("could not write " + MANIFEST);
            OutputStream o = cr.openOutputStream(mf);
            try { o.write(m.toString(1).getBytes("UTF-8")); } finally { try { o.close(); } catch (Throwable ignored) { } }
        } catch (Throwable t) {
            try { DocumentsContract.deleteDocument(cr, root); } catch (Throwable ignored) { }
            if (t instanceof Exception) throw (Exception) t;
            throw new IOException(String.valueOf(t));
        }
    }

    /** one tap: Download/HexAnimaBackup/HexAnima-<id>/ through MediaStore (no folder picker) */
    static void exportDownloads(final Activity a) {
        final Models st = new Models(a.getFilesDir());
        final List<String> ids;
        try { ids = st.installed(); } catch (Throwable t) { toast(a, S(a, "Cannot list models: ", "无法读取模型列表：") + t); return; }
        progress(a, S(a, "Exporting to Download\u2026", "正在导出到 Download\u2026"));
        new Thread(new Runnable() {
            public void run() {
                long totalBytes = 0;
                for (String id : ids) totalBytes += size(st, id);
                long done = 0;
                List<String> errs = new ArrayList<String>();
                for (String id : ids) {
                    try { exportDownloadOne(a, st, id, done, totalBytes); }
                    catch (Throwable t) { errs.add(id + ": " + t); }
                    done += size(st, id);
                }
                final String msg = errs.isEmpty()
                    ? F(a, "Exported %d model(s) to Download/HexAnimaBackup", "已导出 %d 个模型到 Download/HexAnimaBackup", ids.size())
                    : S(a, "Export failed: ", "导出失败：") + errs.get(0);
                UI.post(new Runnable() { public void run() { done(a, msg); } });
            }
        }).start();
    }

    static void exportDownloadOne(Activity a, Models st, String id, long doneBase, long totalBytes) throws Exception {
        ContentResolver cr = a.getContentResolver();
        File home = a.getFilesDir();
        File dir = modelDir(st, home, id);
        if (dir == null || !dir.isDirectory()) throw new IOException("no files for " + id);
        List<String> rels = walk(home, dir);
        String folder = "HexAnima-" + id.replaceAll("[^A-Za-z0-9._-]", "_");
        JSONArray fa = new JSONArray();
        long done = doneBase;
        byte[] buf = new byte[1 << 20];
        String base = Environment.DIRECTORY_DOWNLOADS + "/HexAnimaBackup/" + folder + "/";
        for (String p : rels) {
            File src = new File(home, p);
            String parent = p.contains("/") ? p.substring(0, p.lastIndexOf('/') + 1) : "";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, p.substring(p.lastIndexOf('/') + 1));
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, base + parent);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new IOException("MediaStore refused " + p);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long n = 0;
            InputStream in = new FileInputStream(src);
            OutputStream o = cr.openOutputStream(item);
            try {
                int r;
                while ((r = in.read(buf)) > 0) {
                    o.write(buf, 0, r);
                    md.update(buf, 0, r);
                    n += r;
                    done += r;
                    pset(p + "  " + gb(done) + " / " + gb(totalBytes) + " GB", permille(done, totalBytes));
                }
            } finally {
                try { o.close(); } catch (Throwable ignored) { }
                try { in.close(); } catch (Throwable ignored) { }
            }
            ContentValues fin = new ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, fin, null, null);
            fa.put(new JSONObject().put("p", p).put("size", n).put("sha", hex(md.digest())));
        }
        String name;
        try { name = st.name(id); } catch (Throwable t) { name = id; }
        JSONObject m = new JSONObject().put("format", 1).put("id", id).put("name", name).put("files", fa);
        File stub = new File(home, "sd/" + id + ".safetensors");
        File hdr = new File(dir, "hdr.bin");
        if (stub.exists() && stub.length() > 0 && hdr.isFile() && hdr.length() > 0) {
            m.put("stub", new JSONObject().put("hdr", "anima/m_" + id + "/hdr.bin")
                .put("out", "sd/" + id + ".safetensors").put("size", stub.length()));
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, MANIFEST);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, base);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (item == null) throw new IOException("MediaStore refused the manifest");
        OutputStream o = cr.openOutputStream(item);
        try { o.write(m.toString(1).getBytes("UTF-8")); } finally { try { o.close(); } catch (Throwable ignored) { } }
        ContentValues fin = new ContentValues();
        fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(item, fin, null, null);
    }

    // ---------------------------------------------------------------- import
    static void importRun(final Activity a, final Uri tree) {
        progress(a, S(a, "Reading the folder\u2026", "正在读取文件夹\u2026"));
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try { msg = importTree(a, tree); }
                catch (Throwable t) { msg = S(a, "Import failed: ", "导入失败：") + t; }
                final String m = msg;
                UI.post(new Runnable() { public void run() { done(a, m); } });
            }
        }).start();
    }

    static String importTree(Activity a, Uri tree) throws Exception {
        ContentResolver cr = a.getContentResolver();
        Map<String, Uri> docs = new HashMap<String, Uri>();
        listTree(cr, tree, DocumentsContract.getTreeDocumentId(tree), "", docs);
        Uri mf = docs.get(MANIFEST);
        if (mf == null) throw new IOException(S(a, "This folder has no " + MANIFEST + ": pick the folder an export made",
            "这个文件夹里没有 " + MANIFEST + "：请选择由本页“导出”生成的文件夹"));
        JSONObject m;
        InputStream in = cr.openInputStream(mf);
        try { m = new JSONObject(new String(readAll(in), "UTF-8")); } finally { try { in.close(); } catch (Throwable ignored) { } }
        if (m.optInt("format") != 1) throw new IOException("unknown export format");
        String id = m.getString("id"), name = m.optString("name", id);
        JSONArray fa = m.getJSONArray("files");
        long total = 0;
        for (int i = 0; i < fa.length(); i++) total += fa.getJSONObject(i).getLong("size");
        File home = a.getFilesDir();
        if (home.getUsableSpace() < total + 300000000L) throw new IOException(F(a, "Not enough space: need %s GB", "空间不足：需要 %s GB", gb(total)));
        String homePath = home.getCanonicalPath() + "/";
        byte[] buf = new byte[1 << 20];
        long done = 0;
        for (int i = 0; i < fa.length(); i++) {
            JSONObject f = fa.getJSONObject(i);
            String p = f.getString("p");
            long size = f.getLong("size");
            String sha = f.getString("sha");
            boolean ok = false;
            for (String r : ROOTS) if (p.startsWith(r)) ok = true;
            File dst = new File(home, p);
            if (!ok || p.contains("..") || !dst.getCanonicalPath().startsWith(homePath)) throw new IOException("bad path " + p);
            Uri src = docs.get(p);
            if (src == null) throw new IOException(S(a, "The folder has no file " + p, "文件夹里缺少文件 " + p));
            if (dst.length() == size && sha.equals(Models.sha256(dst))) {
                done += size;
                pset(p, permille(done, total));
                continue;
            }
            dst.getParentFile().mkdirs();
            File part = new File(dst.getPath() + ".part");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long n = 0;
            InputStream sin = cr.openInputStream(src);
            OutputStream o = new FileOutputStream(part);
            try {
                int r;
                while ((r = sin.read(buf)) > 0) {
                    o.write(buf, 0, r);
                    md.update(buf, 0, r);
                    n += r;
                    done += r;
                    pset(p + "  " + gb(done) + " / " + gb(total) + " GB", permille(done, total));
                }
            } finally {
                try { o.close(); } catch (Throwable ignored) { }
                try { sin.close(); } catch (Throwable ignored) { }
            }
            if (n != size || !hex(md.digest()).equalsIgnoreCase(sha)) {
                part.delete();
                throw new IOException(S(a, "The file is damaged: " + p, "文件已损坏：" + p));
            }
            dst.delete();
            if (!part.renameTo(dst)) throw new IOException("rename " + p);
        }
        JSONObject sb = m.optJSONObject("stub");
        if (sb != null) Models.stub(new File(home, sb.getString("hdr")), new File(home, sb.getString("out")), sb.getLong("size"));
        Models st = new Models(home);
        File dir = modelDir(st, home, id);
        if (dir != null) { dir.mkdirs(); st.doneMark(dir).createNewFile(); }
        return S(a, "Imported \"" + name + "\"", "已导入“" + name + "”");
    }

    // ---------------------------------------------------------------- helpers
    static File modelDir(Models st, File home, String id) {
        if (id == null) return null;
        if (Ltx.is(id)) return Ltx.dir(home);
        return st.modelDir(id);
    }

    static long size(Models st, String id) {
        File home = st.home;
        File dir = modelDir(st, home, id);
        if (dir == null) return 0;
        long n = 0;
        List<String> rels = walk(home, dir);
        for (String p : rels) n += new File(home, p).length();
        return n;
    }

    static List<String> walk(File home, File dir) {
        List<String> out = new ArrayList<String>();
        walkInto(home, dir, out);
        Collections.sort(out);
        return out;
    }

    static void walkInto(File home, File f, List<String> out) {
        if (skip(f)) return;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c == null) return;
            java.util.Arrays.sort(c);
            for (File x : c) walkInto(home, x, out);
        } else {
            String hp = home.getPath();
            String p = f.getPath();
            if (p.startsWith(hp + "/")) out.add(p.substring(hp.length() + 1));
        }
    }

    static boolean skip(File f) {
        String n = f.getName();
        if (f.isDirectory()) return n.equals("dsp") || n.startsWith("cache") || n.equals("lcache") || n.equals("tmp") || n.equals("videos");
        return n.endsWith(".part") || n.endsWith(".log") || n.equals(".installed")
            || n.equals("feat.f32") || n.equals("z.f32") || n.equals("ids.i32") || n.equals("ids_t.i32");
    }

    static Uri dir(ContentResolver cr, Map<String, Uri> dirs, Uri root, String rel) throws IOException {
        Uri u = dirs.get(rel);
        if (u != null) return u;
        Uri parent = dir(cr, dirs, root, rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "");
        if (parent == null) return null;
        u = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR,
            rel.substring(rel.lastIndexOf('/') + 1));
        if (u != null) dirs.put(rel, u);
        return u;
    }

    static void listTree(ContentResolver cr, Uri tree, String docId, String rel, Map<String, Uri> out) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor q = null;
        try {
            q = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (q == null) return;
            while (q.moveToNext()) {
                String did = q.getString(0), n = q.getString(1), path = rel.isEmpty() ? n : rel + "/" + n;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2))) listTree(cr, tree, did, path, out);
                else out.put(path, DocumentsContract.buildDocumentUriUsingTree(tree, did));
            }
        } catch (Throwable ignored) {
        } finally { if (q != null) try { q.close(); } catch (Throwable ignored) { } }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }

    static int permille(long done, long total) {
        return total <= 0 ? 0 : (int) Math.min(1000, done * 1000 / total);
    }

    static String gb(long b) { return String.format("%.1f", b / 1e9); }

    /** where a checkpoint is staged when its provider gives no real file */
    static String importTemp(File home) { return new File(new File(home, "anima"), "import/ckpt_" + System.currentTimeMillis() + ".safetensors").getPath(); }

    static long sdFree() {
        try { return Environment.getExternalStorageDirectory().getUsableSpace(); } catch (Throwable t) { return -1; }
    }

    static void toast(Activity a, String s) {
        try { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- progress dialog
    static void progress(Activity a, String title) {
        try {
            if (dlg != null && dlg.isShowing()) dlg.dismiss();
        } catch (Throwable ignored) { }
        dlg = null;
        Base b = (Base) a;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(b.dp(18), b.dp(14), b.dp(18), b.dp(16));
        col.addView(b.text(title, 15, Base.TXT, true));
        progMsg = b.text("", 12, Base.DIM, false);
        progMsg.setPadding(0, b.dp(8), 0, 0);
        col.addView(progMsg);
        progBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, b.dp(6));
        lp.topMargin = b.dp(12);
        col.addView(progBar, lp);
        progDlg = b.builder().setView(col).setCancelable(false).create();
        progDlg.show();
    }

    static void pset(final String msg, final int pm) {
        UI.post(new Runnable() {
            public void run() {
                try {
                    if (progMsg != null) progMsg.setText(msg);
                    if (progBar != null) progBar.setProgress(pm);
                } catch (Throwable ignored) { }
            }
        });
    }

    static void done(Activity a, String msg) {
        try { if (progDlg != null && progDlg.isShowing()) progDlg.dismiss(); } catch (Throwable ignored) { }
        progDlg = null;
        progMsg = null;
        progBar = null;
        toast(a, msg);
        open(a);
    }

    // ---------------------------------------------------------------- catalog cache (offline model list)
    static File cacheFile(Activity a) { return new File(a.getFilesDir(), "catalog.cache"); }

    static void saveCatalogAt(Activity a, List<Models.Item> items) {
        if (a == null || items == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Models.Item it : items) {
                if (it == null) continue;
                arr.put(new JSONObject().put("id", it.id).put("name", it.name).put("desc", it.desc)
                    .put("desc_en", it.descEn).put("params", it.params).put("quant", it.quant).put("author", it.author)
                    .put("manifest", it.manifest).put("runtimeManifest", it.runtimeManifest).put("mossVariant", it.mossVariant)
                    .put("size", it.size).put("runtimeSize", it.runtimeSize).put("speed", it.speed)
                    .put("moss", it.moss).put("qwen", it.qwen).put("mage", it.mage).put("video", it.video)
                    .put("runtimeAvailable", it.runtimeAvailable));
            }
            File f = cacheFile(a);
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(new JSONObject().put("ts", System.currentTimeMillis()).put("models", arr).toString().getBytes("UTF-8")); }
            finally { try { o.close(); } catch (Throwable ignored) { } }
        } catch (Throwable ignored) {
        }
    }

    static List<Models.Item> readCatalog(Activity a) {
        try {
            File f = cacheFile(a);
            if (!f.isFile()) return null;
            JSONObject j = new JSONObject(new String(readAll(new FileInputStream(f)), "UTF-8"));
            JSONArray arr = j.getJSONArray("models");
            List<Models.Item> out = new ArrayList<Models.Item>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Models.Item it = new Models.Item();
                it.id = o.optString("id"); it.name = o.optString("name"); it.desc = o.optString("desc");
                it.descEn = o.optString("desc_en", it.desc); it.params = o.optString("params"); it.quant = o.optString("quant");
                it.author = o.optString("author"); it.manifest = o.optString("manifest");
                it.runtimeManifest = o.optString("runtimeManifest"); it.mossVariant = o.optString("mossVariant");
                it.size = o.optLong("size"); it.runtimeSize = o.optLong("runtimeSize"); it.speed = o.optInt("speed");
                it.moss = o.optBoolean("moss"); it.qwen = o.optBoolean("qwen"); it.mage = o.optBoolean("mage");
                it.video = o.optBoolean("video"); it.runtimeAvailable = o.optBoolean("runtimeAvailable", true);
                out.add(it);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) { return null; }
    }

    /** called from the failed-catalog branch of ModelsActivity: show the saved catalog instead of an error */
    static void onCatalogError(ModelsActivity a, Exception e) {
        try { a.list.removeAllViews(); } catch (Throwable ignored) { }
        List<Models.Item> cached = readCatalog(a);
        if (cached != null) {
            a.items = cached;
            try { a.render(); } catch (Throwable ignored) { }
            toast(a, S(a, "Offline: showing the saved model list", "离线：显示已保存的模型列表"));
            return;
        }
        header(a);
        String msg = e == null ? "?" : String.valueOf(e.getMessage());
        try { a.list.addView(a.text(F(a, "Catalog unavailable: %s", "模型目录不可用：%s", msg), 13f, Base.BAD, false)); }
        catch (Throwable ignored) { }
    }
}
