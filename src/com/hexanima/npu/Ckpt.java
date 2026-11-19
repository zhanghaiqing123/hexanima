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
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Convert Anima checkpoints (.safetensors) that live in a folder on the phone, without copying the several-GB
 * source into the app first: the file is handed to libanimaconv.so through /proc/self/fd/<n> and only the
 * converted blocks are written. Falls back to a copy when the provider does not give a real file.
 *
 * The sd.cpp stub is built here from the checkpoint's own header (8-byte length + JSON header, then a sparse
 * hole up to the real size), so no catalog download is needed for it.
 */
final class Ckpt {
    static final String[] EXT = {".safetensors"};

    static final class Item {
        Uri uri;
        String name = "";
        long size;
        int blocks;
        long headerLen;
        String error;
        CheckBox box;
        boolean valid() { return blocks == 28 || blocks == 40; }
        long blockBytes() { return blocks == 40 ? 83365888L : 138416128L; }
        long need() { return blockBytes() * blocks + 320000000L; }
    }

    // ---------------------------------------------------------------- scan
    static void scan(final Activity a, final Uri tree) {
        Backup.progress(a, L.s("Scanning the folder\u2026", "Scanning the folder\u2026"));
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
                        try { probe(cr, it); } catch (Throwable t) { it.error = String.valueOf(t.getMessage()); }
                        found.add(it);
                    }
                } catch (Throwable t) {
                    final String m = String.valueOf(t);
                    Backup.UI.post(new Runnable() { public void run() { Backup.done(a, "Scan failed: " + m); } });
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
            String txt = new String(js, "UTF-8");
            JSONObject j = new JSONObject(txt);
            String prefix = null;
            java.util.Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k.endsWith("self_attn.q_proj.weight") && !k.contains("blocks.")) { prefix = k.substring(0, k.length() - "self_attn.q_proj.weight".length()); break; }
            }
            if (prefix == null) { it.error = "not an Anima DiT"; return; }
            org.json.JSONArray shp = j.optJSONObject(prefix + "self_attn.q_proj.weight").optJSONArray("shape");
            if (shp == null || shp.length() != 2 || shp.getInt(0) != 2048 || shp.getInt(1) != 2048) { it.error = "width is not 2048"; return; }
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

        col.addView(b.text(L.f("%d .safetensors file(s) in this folder", "%d .safetensors file(s) in this folder", found.size()), 14, Base.TXT, true));
        TextView hint = b.text(L.s("Pick the ones to convert. The source file stays where it is; only the converted blocks are written to the app.",
            "Pick the ones to convert. The source file stays where it is; only the converted blocks are written to the app."), 12, Base.DIM, false);
        hint.setPadding(0, b.dp(6), 0, b.dp(8));
        col.addView(hint);
        if (found.isEmpty()) col.addView(b.text(L.s("No .safetensors file here.", "No .safetensors file here."), 13, Base.DIM, false));

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
            String meta = it.valid()
                ? (it.blocks == 40 ? "2.9B" : "2B") + "  \u00b7  " + Backup.gb(it.size) + " GB  \u00b7  needs " + Backup.gb(it.need()) + " GB"
                : (it.error != null ? it.error : "unsupported");
            inf.addView(b.text(meta, 11.5f, Base.DIM, false));
            row.addView(inf, new LinearLayout.LayoutParams(0, -2, 1));
            col.addView(row);
            if (it.valid()) pick.add(it);
        }

        Button go = b.button(L.s("Convert selected", "Convert selected"), Base.ACC, Base.ON_ACC);
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
        Button cancel = b.button(L.s("Cancel", "Cancel"), Base.FIELD, Base.TXT);
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, b.dp(44));
        lp2.topMargin = b.dp(8);
        col.addView(cancel, lp2);
    }

    // ---------------------------------------------------------------- convert
    static void convert(final Activity a, final List<Item> items) {
        Backup.progress(a, L.s("Converting\u2026", "Converting\u2026"));
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder ok = new StringBuilder();
                final List<String> errs = new ArrayList<String>();
                for (Item it : items) {
                    String err;
                    try { err = convertOne(a, it); }
                    catch (Throwable t) { err = String.valueOf(t); }
                    if (err == null) ok.append(it.name).append('\n');
                    else errs.add(it.name + ": " + err);
                }
                final String msg;
                if (errs.isEmpty()) msg = L.f("Converted %d model(s)", "Converted %d model(s)", ok.length() == 0 ? 0 : ok.toString().split("\n").length);
                else msg = L.s("Converted " + (ok.length() == 0 ? "none" : ok.toString().trim().replace('\n', ',')) + "; failed: " + errs.get(0),
                    "Converted " + (ok.length() == 0 ? "none" : ok.toString().trim().replace('\n', ',')) + "; failed: " + errs.get(0));
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
            return L.f("Not enough space: about %s GB needed", "Not enough space: about %s GB needed", Backup.gb(need));
        }
        File bin = new File(a.getApplicationInfo().nativeLibraryDir, "libanimaconv.so");
        if (!bin.isFile()) { Models.deleteTree(dir); return "libanimaconv.so is missing"; }

        ContentResolver cr = a.getContentResolver();
        ParcelFileDescriptor pfd = cr.openFileDescriptor(it.uri, "r");
        if (pfd == null) { Models.deleteTree(dir); return "cannot open the file"; }
        File tmp = null;
        try {
            String src = "/proc/" + android.os.Process.myPid() + "/fd/" + pfd.getFd();
            String err = run(a, bin, src, dir.getPath(), it.blocks);
            if (err != null && err.contains("cannot read")) {   // the provider does not expose a real file: copy (needs the space twice)
                long needCopy = it.size + need + 50000000L;
                if (home.getUsableSpace() < needCopy) { Models.deleteTree(dir); return L.f("Not enough space for a copy too: %s GB", "Not enough space for a copy too: %s GB", Backup.gb(needCopy)); }
                tmp = new File(Backup.importTemp(home));
                tmp.getParentFile().mkdirs();
                InputStream in = cr.openInputStream(it.uri);
                OutputStream o = new FileOutputStream(tmp);
                try { copy(in, o); } finally { try { o.close(); } catch (Throwable ignored) { } try { in.close(); } catch (Throwable ignored) { } }
                Models.deleteTree(dir);
                dir.mkdirs();
                err = run(a, bin, tmp.getPath(), dir.getPath(), it.blocks);
            }
            if (err != null) { Models.deleteTree(dir); return err; }
            // sd.cpp stub from the checkpoint's own header: 8-byte length + JSON header, then a sparse hole
            File stub = new File(new File(home, "sd"), id + ".safetensors");
            writeStub(cr, it, stub);
            String name = it.name.replaceAll("(?i)\\.safetensors$", "");
            boolean turbo = name.toLowerCase(java.util.Locale.ROOT).matches(".*(turbo|4.?step|distilled|lightning|hyper|schnell).*");
            JSONObject meta = new JSONObject().put("id", id).put("name", name)
                .put("speed", it.blocks == 40 ? 12 : 7).put("imported", true).put("turbo", turbo);
            java.io.FileWriter w = new java.io.FileWriter(new File(dir, "meta.json"));
            try { w.write(meta.toString()); } finally { try { w.close(); } catch (Throwable ignored) { } }
            store.doneMark(dir).createNewFile();
            return null;
        } finally {
            try { pfd.close(); } catch (Throwable ignored) { }
            if (tmp != null) tmp.delete();
        }
    }

    /** runs the converter and parses "blocks N", "blk i/N"; returns null on success */
    static String run(Activity a, File bin, String src, String out, int blocks) throws Exception {
        Process p = new ProcessBuilder(bin.getPath(), src, out, "auto").redirectErrorStream(true).start();
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
                    try { Backup.pset(L.f("Block %s of %s", "Block %s of %s", q[0], q[1]), Backup.permille(Integer.parseInt(q[0]), Integer.parseInt(q[1]))); }
                    catch (Throwable ignored) { }
                } else if (l.startsWith("error")) {
                    err = l.substring(5).trim();
                }
            }
        } finally { try { r.close(); } catch (Throwable ignored) { } }
        p.waitFor();
        if (err != null) return err;
        if (p.exitValue() != 0) return "converter exit " + p.exitValue();
        if (seen != blocks) return "converter wrote " + seen + " blocks, expected " + blocks;
        return null;
    }

    static void writeStub(ContentResolver cr, Item it, File out) throws Exception {
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
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try { raf.setLength(it.size); } finally { try { raf.close(); } catch (Throwable ignored) { } }
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
