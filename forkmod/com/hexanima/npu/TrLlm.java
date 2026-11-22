package com.hexanima.npu;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.function.Consumer;

/** 本地 Hy-MT1.5 翻译引擎（腾讯混元 1.8B，1.25bit 量化，440 MB）。
 *
 *  模型和可执行文件都不在 APK 里常驻内存：hymt-serve 是我自己编的 llama.cpp 服务器，
 *  由 App 以子进程方式启动（放在 nativeLibraryDir 里，Android 10+ 只允许从这里 exec），
 *  加载一次后常驻，空闲或开始生图前被 stop() 掉，内存完全还给系统。 */
final class TrLlm {
    static final String DIR = "hymt";

    /** 一个可选的本地模型。supported=false 的暂时跑不了：它的量化内核还没开源进 llama.cpp */
    static final class V {
        final String id, label;
        final long size;
        final String sha;
        final String[] urls;
        final boolean supported;
        V(String id, String label, long size, String sha, String[] urls, boolean supported) {
            this.id = id; this.label = label; this.size = size; this.sha = sha; this.urls = urls; this.supported = supported;
        }
        String file() { return "model-" + id + ".gguf"; }
    }

    static final String HF1 = "https://hf-mirror.com/";
    static final String HF2 = "https://huggingface.co/";
    static final String P125 = "tencent/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT1.5-1.8B-1.25bit.gguf";
    static final String P2   = "AngelSlim/Hy-MT1.5-1.8B-2bit-GGUF/resolve/main/Hy-MT1.5-1.8B-2bit.gguf";

    static final V V125 = new V("125", "Hy-MT1.5 · 1.25bit · 440 MB", 461860704L,
            "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab",
            new String[]{HF1 + P125, HF2 + P125}, true);
    static final V V2 = new V("2", "Hy-MT1.5 · 2bit · 573 MB", 600535360L,
            "46e587d917fc4f6f5bda602f10c4a53da8840240355824bcc0e227559c9cba3e",
            new String[]{HF1 + P2, HF2 + P2}, false);   // 内核未开源（65 B/256 的自定义 2bit），跑不起来

    static final V[] ALL = {V125};   // 2bit 用的是腾讯自己的 65B/256 内核，llama.cpp 上游没有，先不提供

    static V cur(Context c) {
        String id = c.getSharedPreferences("anima", 0).getString("tr_hymt_v", "125");
        for (V v : ALL) if (v.id.equals(id)) return v;
        return V125;
    }
    static void setCur(Context c, V v) { c.getSharedPreferences("anima", 0).edit().putString("tr_hymt_v", v.id).apply(); }

    /** 当前选中的模型文件 */
    static File file(Context c) { return new File(dir(c), cur(c).file()); }

    /** 老版本用的是 model.gguf：改名成按型号命名的文件，避免升级后又下一遍 */
    static void migrate(Context c) {
        File old = new File(dir(c), "model.gguf");
        if (!old.isFile()) return;
        for (V v : ALL) {
            File dst = new File(dir(c), v.file());
            if (!dst.isFile() && old.length() == v.size) old.renameTo(dst);
            File oldPart = new File(dir(c), "model.gguf.part");
            File newPart = new File(dir(c), v.file() + ".part");
            if (oldPart.isFile() && !newPart.isFile() && oldPart.length() < v.size) oldPart.renameTo(newPart);   // 没下完的也接上
        }
    }

    private static Process proc;
    private static BufferedWriter in;
    private static BufferedReader out;
    private static volatile boolean ready;
    private static volatile String lastErr = "";
    private static volatile boolean loading;
    private static volatile boolean downloading;   // 防止连点两下并发写同一个 .part

    private TrLlm() { }

    static File dir(Context c) { return new File(c.getFilesDir(), DIR); }
    static File model(Context c) { return file(c); }
    static File logFile(Context c) { return new File(dir(c), "hymt.log"); }
    static boolean installed(Context c) {
        migrate(c);
        V v = cur(c);
        File f = file(c);
        return f.isFile() && f.length() == v.size;
    }

    /** 文件在但大小不对（下载中断）：返回已有字节数，0 = 没有 */
    static long partial(Context c) {
        migrate(c);
        File f = file(c);
        return f.isFile() ? f.length() : 0;
    }

    static boolean ready() { return ready; }
    static boolean loading() { return loading; }
    static String lastError() { return lastErr; }

    /** "未下载 / 加载中 / 就绪 / 错误：…"，给状态行用 */
    static String state(Context c) {
        if (!installed(c)) return "installed=0";
        if (ready) return "ready";
        if (loading) return "loading";
        return lastErr.isEmpty() ? "idle" : "error:" + lastErr;
    }

    /** 启动常驻进程并等它打印 ready（阻塞，调用方要在后台线程） */
    static synchronized boolean start(Context c) {
        if (ready) return true;
        migrate(c);
        if (!cur(c).supported) { lastErr = "runtime does not support this quantization yet"; return false; }
        if (!installed(c)) { lastErr = "model missing"; return false; }
        if (proc != null) stop();
        loading = true;
        lastErr = "";
        try {
            File bin = new File(c.getApplicationInfo().nativeLibraryDir, "libhymt.so");
            if (!bin.isFile()) { lastErr = "libhymt.so missing"; loading = false; return false; }
            dir(c).mkdirs();
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), model(c).getAbsolutePath(), "4");
            pb.directory(dir(c));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile(c)));
            proc = pb.start();
            in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            out = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            final long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 60000) {
                String l = out.readLine();
                if (l == null) { lastErr = "process exited"; break; }
                if (l.equals("ready")) {
                    ready = true;
                    loading = false;
                    String sys = c.getSharedPreferences("anima", 0).getString("tr_sys", "");
                    if (!sys.isEmpty()) setSystem(sys);     // 用户自定义的头部提示词
                    return true;
                }
            }
            if (lastErr.isEmpty()) lastErr = "timeout";
        } catch (Throwable t) {
            lastErr = String.valueOf(t);
        }
        loading = false;
        stop();
        return false;
    }

    /** 卸载：进程退出后内存立即释放（llama.cpp 用 mmap，free 后页面归还系统） */
    static synchronized void stop() {
        ready = false;
        loading = false;
        try { if (in != null) { in.write("Q\n"); in.flush(); } } catch (Throwable ignored) { }
        try { if (in != null) in.close(); } catch (Throwable ignored) { }
        try { if (out != null) out.close(); } catch (Throwable ignored) { }
        in = null;
        out = null;
        Process p = proc;
        proc = null;
        if (p != null) {
            try { p.waitFor(); } catch (InterruptedException ignored) { }
            try { if (p.isAlive()) p.destroy(); } catch (Throwable ignored) { }
        }
    }

    static synchronized boolean setSystem(String s) {
        if (!ready) return false;
        try {
            String b = Base64.encodeToString(s.getBytes("UTF-8"), Base64.NO_WRAP);
            in.write("S\t" + b + "\n");
            in.flush();
            return out.readLine() != null;
        } catch (Throwable t) { lastErr = String.valueOf(t); ready = false; return false; }
    }

    /** 翻一段中文（阻塞，调用方要在后台线程）；失败返回 null，界面回退到 ML Kit */
    static synchronized String translate(String zh) {
        if (!ready) return null;
        try {
            String b = Base64.encodeToString(zh.getBytes("UTF-8"), Base64.NO_WRAP);
            in.write("T\t" + b + "\n");
            in.flush();
            String l = out.readLine();
            if (l == null) { lastErr = "no reply"; ready = false; return null; }
            if (l.startsWith("R\t")) return new String(Base64.decode(l.substring(2), Base64.DEFAULT), "UTF-8");
            lastErr = l;
            return null;
        } catch (Throwable t) { lastErr = String.valueOf(t); ready = false; return null; }
    }

    /** 下载模型（阻塞）：先 .part 续传，校验大小 + sha256，再改名 */
    static boolean isDownloading() { return downloading; }

    static void download(Context c, Consumer<Long> pct, Consumer<String> done) {
        if (downloading) { done.accept("busy"); return; }
        downloading = true;
        try { download0(c, pct, done); } finally { downloading = false; }
    }

    private static void download0(Context c, Consumer<Long> pct, Consumer<String> done) {
        migrate(c);
        V v = cur(c);
        if (!v.supported) { done.accept("unsupported"); return; }
        dir(c).mkdirs();
        File dst = file(c);
        File part = new File(dst.getPath() + ".part");
        if (dst.isFile() && dst.length() == v.size) { done.accept("ok"); return; }
        String err = null;
        for (String url : v.urls) {
            try {
                fetch(url, part, pct, v.size);
                if (part.length() != v.size) throw new java.io.IOException("size " + part.length());
                String sha = sha256(part);
                if (!v.sha.equalsIgnoreCase(sha)) throw new java.io.IOException("sha256 " + sha);
                if (dst.exists()) dst.delete();
                if (!part.renameTo(dst)) throw new java.io.IOException("rename failed");
                done.accept("ok");
                return;
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
        }
        done.accept(err == null ? "failed" : err);
    }

    private static void fetch(String url, File part, Consumer<Long> pct, long size) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (have == size) return;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        if (code == 200 && have > 0) { have = 0; if (part.exists()) part.delete(); }
        if (code != 200 && code != 206) throw new java.io.IOException("HTTP " + code);
        try (InputStream is = c.getInputStream(); FileOutputStream os = new FileOutputStream(part, have > 0)) {
            byte[] b = new byte[1 << 16];
            long n = have;
            for (int r; (r = is.read(b)) > 0; ) {
                os.write(b, 0, r);
                n += r;
                if (pct != null) pct.accept(n);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new java.io.FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            for (int r; (r = is.read(b)) > 0; ) md.update(b, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
