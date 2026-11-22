package com.hexanima.npu;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/*
 * HexAnima local API bridge (LocalDream compatible).
 *
 *   GET  /                 -> {"status":"ok",...}      (online probe used by the Tavern script)
 *   GET  /health           -> same
 *   GET  /status           -> state dump
 *   GET  /log              -> plain text log ring buffer
 *   GET  /preview.png      -> current per-step preview (files/anima/preview.png)
 *   GET  /last.png         -> last finished picture
 *   POST /generate         -> SSE: data: {"type":"progress","step":..,"total_steps":..}
 *                                  data: {"type":"complete","image":"<base64 RGB>","width":..,"height":..,"seed":..,"generation_time_ms":..}
 *                                  data: {"type":"error","message":".."}
 *
 * The heavy lifting is done by the app itself: we fill in the same widgets the
 * user would edit and then call Main.generate(false), so the normal pipeline
 * (model load, anima.sh, NPU) runs untouched. Two smali hooks feed events back:
 * MainRun.setProgressUi -> onProgress(), Main.lambda$generate$132 -> onFinish().
 *
 * Only java.* / android.* APIs are used, and every reference to app classes is
 * resolved through reflection, so this file compiles against a plain android.jar.
 */
public final class ApiServer {

    public static final String VERSION = "1.9.5-v73+api25";
    private static final String TAG = "HexAnimaApi";
    private static final int PORT = 8081;
    private static final int LOG_MAX = 400;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final long GEN_TIMEOUT_MS = 20L * 60L * 1000L;
    private static final String EVT_DONE = "\u0000DONE";

    /* ------------------------------------------------------------------ */
    /* state                                                               */
    /* ------------------------------------------------------------------ */

    private static volatile boolean running = false;
    private static volatile Activity activity = null;
    private static volatile ServerSocket serverSocket = null;
    private static volatile Thread acceptThread = null;

    private static final Object JOB_LOCK = new Object();
    private static volatile Job currentJob = null;
    private static volatile String lastError = "";

    private static final LinkedList<String> logRing = new LinkedList<String>();
    private static File logFileInternal = null;
    private static File logFileExternal = null;

    private static final HashMap<String, Field> fieldCache = new HashMap<String, Field>();
    private static final HashMap<String, Method> methodCache = new HashMap<String, Method>();
    private static final HashMap<String, Method> staticCache = new HashMap<String, Method>();

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /** Called from Main.onCreate. Idempotent; refreshes the activity reference. */
    public static void start(Context ctx) {
        try {
            if (ctx instanceof Activity) {
                activity = (Activity) ctx;
            }
            if (running) {
                return;
            }
            synchronized (ApiServer.class) {
                if (running) {
                    return;
                }
                initLog(ctx);
                ServerSocket ss = new ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"));
                serverSocket = ss;
                running = true;
                acceptThread = new Thread(new Runnable() {
                    public void run() {
                        acceptLoop();
                    }
                }, "hexanima-api");
                acceptThread.setDaemon(true);
                acceptThread.start();
                log("listening on 127.0.0.1:" + PORT + " version=" + VERSION);
            }
        } catch (Throwable t) {
            lastError = "start: " + t;
            log("start failed: " + stack(t));
        }
    }

    private static void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = serverSocket.accept();
                s.setSoTimeout(120000);
                s.setTcpNoDelay(true);
                Thread t = new Thread(new Conn(s), "hexanima-api-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (running) {
                    log("accept: " + t);
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* hooks called from the patched smali                                 */
    /* ------------------------------------------------------------------ */

    /** Hook: MainRun.setProgressUi(int percent, String label, long ts) */
    public static void onProgress(int percent) {
        Job job = currentJob;
        if (job == null) {
            return;
        }
        try {
            if (percent < 0) {
                percent = 0;
            }
            if (percent > 100) {
                percent = 100;
            }
            if (percent <= job.lastPercent) {
                return;
            }
            job.lastPercent = percent;
            int total = job.totalSteps > 0 ? job.totalSteps : 20;
            int step = (int) Math.round(percent * total / 100.0);
            if (step > total) {
                step = total;
            }
            String json = "{\"type\":\"progress\",\"step\":" + step
                    + ",\"total_steps\":" + total
                    + ",\"percent\":" + percent + "}";
            job.events.offer(json);
        } catch (Throwable t) {
            log("onProgress: " + t);
        }
    }

    /** Hook: Main.lambda$generate$132(boolean cancelled, boolean ok, double secs, boolean hot, ...) */
    public static void onFinish(Object mainObj, boolean cancelled, boolean ok, boolean hot, Bitmap bitmap) {
        Job job = currentJob;
        if (job == null) {
            return;
        }
        try {
            job.cancelled = cancelled;
            job.ok = ok;
            job.hot = hot;
            job.bitmap = bitmap;
            job.genMs = System.currentTimeMillis() - job.startMs;
            try {
                File f = (File) getField(mainObj, "lastFile");
                if (f != null) {
                    job.lastFilePath = f.getAbsolutePath();
                }
            } catch (Throwable t) {
                log("read lastFile: " + t);
            }
            log("finish cancelled=" + cancelled + " ok=" + ok + " hot=" + hot
                    + " file=" + job.lastFilePath + " ms=" + job.genMs);
            job.events.offer(EVT_DONE);
        } catch (Throwable t) {
            log("onFinish: " + t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* connection handling                                                 */
    /* ------------------------------------------------------------------ */

    private static final class Conn implements Runnable {
        private final Socket socket;

        Conn(Socket s) {
            this.socket = s;
        }

        public void run() {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new BufferedInputStream(socket.getInputStream(), 8192);
                out = new BufferedOutputStream(socket.getOutputStream(), 65536);

                String requestLine = readLine(in);
                if (requestLine == null || requestLine.length() == 0) {
                    return;
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) {
                    sendJson(out, 400, "{\"error\":\"bad request line\"}");
                    return;
                }
                String method = parts[0].toUpperCase(Locale.US);
                String path = parts[1];
                int q = path.indexOf('?');
                if (q >= 0) {
                    path = path.substring(0, q);
                }

                HashMap<String, String> headers = new HashMap<String, String>();
                String line;
                int guard = 0;
                while ((line = readLine(in)) != null && line.length() > 0 && guard++ < 100) {
                    int c = line.indexOf(':');
                    if (c > 0) {
                        headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                                line.substring(c + 1).trim());
                    }
                }

                int len = 0;
                try {
                    String cl = headers.get("content-length");
                    if (cl != null) {
                        len = Integer.parseInt(cl.trim());
                    }
                } catch (Throwable ignored) {
                }
                if (len < 0 || len > MAX_BODY) {
                    sendJson(out, 413, "{\"error\":\"body too large\"}");
                    return;
                }
                byte[] body = new byte[0];
                if (len > 0) {
                    body = readN(in, len);
                }

                if ("OPTIONS".equals(method)) {
                    writeHead(out, 204, null, 0, true);
                    return;
                }
                if (("GET".equals(method) || "HEAD".equals(method))
                        && ("/".equals(path) || "/health".equals(path))) {
                    sendJson(out, 200, statusJson());
                    return;
                }
                if ("GET".equals(method) && "/status".equals(path)) {
                    sendJson(out, 200, statusJson());
                    return;
                }
                if ("GET".equals(method) && "/log".equals(path)) {
                    sendText(out, 200, logText());
                    return;
                }
                if ("GET".equals(method) && "/diag".equals(path)) {
                    sendText(out, 200, diagText());
                    return;
                }
                if ("GET".equals(method) && "/card".equals(path)) {
                    // run the model-backup card insertion on the UI thread and report what happened
                    sendText(out, 200, cardTest());
                    return;
                }
                if ("GET".equals(method) && "/preview.png".equals(path)) {
                    serveFile(out, previewFile());
                    return;
                }
                if ("GET".equals(method) && "/last.png".equals(path)) {
                    serveFile(out, lastFile());
                    return;
                }
                if ("GET".equals(method) && ("/test".equals(path) || "/test.html".equals(path))) {
                    byte[] page = testPage().getBytes("UTF-8");
                    writeHead(out, 200, "text/html; charset=utf-8", page.length, true);
                    out.write(page);
                    out.flush();
                    return;
                }
                if ("GET".equals(method) && "/props".equals(path)) {
                    sendJson(out, 200, propsJson());
                    return;
                }
                if ("POST".equals(method) && "/generate".equals(path)) {
                    handleGenerate(out, body);
                    return;
                }
                sendJson(out, 404, "{\"error\":\"not found\",\"path\":\"" + jsonEscape(path) + "\"}");
            } catch (Throwable t) {
                log("conn: " + stack(t));
            } finally {
                try {
                    if (out != null) {
                        out.flush();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* /generate                                                           */
    /* ------------------------------------------------------------------ */

    private static void handleGenerate(OutputStream out, byte[] body) {
        Job job = new Job();
        File initSrc = null;
        File initBak = null;
        try {
            JSONObject req = new JSONObject(body.length == 0 ? "{}" : new String(body, "UTF-8"));

            job.prompt = req.optString("prompt", "");
            if (job.prompt.trim().length() == 0) {
                writeSseHead(out);
                writeSse(out, errorJson("empty prompt"));
                return;
            }

            final Activity act = activity;
            if (act == null || act.isFinishing()) {
                writeSseHead(out);
                writeSse(out, errorJson("HexAnima is not running: open the app once and leave it in background"));
                return;
            }

            // Anything the request leaves out or sends empty is taken from the app's
            // live settings (the same values the user sees and can change in the UI).
            AppParams app = readAppParams(act);
            if (app.error.length() > 0) {
                log("app defaults: " + app.error);
            }
            applyParams(job, req, app);

            if (req.has("seed") && !req.isNull("seed")) {
                job.seed = req.optLong("seed", 0L);
            } else {
                job.seed = randomSeed();
            }
            job.startMs = System.currentTimeMillis();

            log("generate prompt=\"" + shortText(job.prompt, 120) + "\" neg=\"" + shortText(job.negative, 80)
                    + "\" steps=" + job.totalSteps + " cfg=" + job.cfg
                    + " size=" + job.width + "x" + job.height
                    + " sampler=" + job.sampler + "/" + job.scheduler
                    + " seed=" + job.seed
                    + " inherit=[" + job.inherited + "]");

            writeSseHead(out);
            writeSse(out, "{\"type\":\"progress\",\"step\":0,\"total_steps\":" + job.totalSteps + ",\"percent\":0}");
            out.flush();

            final Job fjob = job;
            synchronized (JOB_LOCK) {
                currentJob = fjob;
                try {
                    initSrc = new File(act.getFilesDir(), "init_src.png");
                    if (initSrc.exists()) {
                        initBak = new File(act.getFilesDir(), "init_src.api_bak.png");
                        if (initBak.exists()) {
                            initBak.delete();
                        }
                        if (initSrc.renameTo(initBak)) {
                            fjob.stashedInit = initBak;
                        } else {
                            initBak = null;
                        }
                    }
                    runGeneration(act, fjob, out);
                } finally {
                    currentJob = null;
                    if (fjob.stashedInit != null) {
                        try {
                            fjob.stashedInit.renameTo(new File(act.getFilesDir(), "init_src.png"));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            lastError = "generate: " + t;
            log("generate failed: " + stack(t));
            try {
                writeSseHead(out);
                writeSse(out, errorJson("server error: " + t));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void runGeneration(Activity act, Job job, OutputStream out) throws Exception {
        try {
            log("request start");
            final Activity fact = act;
            final Job fjob = job;
            final Throwable[] error = new Throwable[1];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startOnUi(fact, fjob);
                    } catch (Throwable t) {
                        error[0] = t;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            if (!latch.await(30, TimeUnit.SECONDS)) {
                writeSse(out, errorJson("the app did not respond (UI thread timeout)"));
                return;
            }
            if (error[0] != null) {
                log("generate() path failed: " + error[0]);
                if (!job.canFallback || !startWithRunOne(act, job)) {
                    writeSse(out, errorJson("failed to start generation: " + error[0]));
                    return;
                }
            }
        } catch (Throwable t) {
            log("startOnUi: " + stack(t));
            if (!job.canFallback || !startWithRunOne(act, job)) {
                writeSse(out, errorJson("failed to start generation: " + t));
                return;
            }
        }

        long deadline = System.currentTimeMillis() + GEN_TIMEOUT_MS;
        int idleTicks = 0;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                lastError = "timeout";
                writeSse(out, errorJson("timeout waiting for the picture"));
                return;
            }
            String ev = job.events.poll(Math.min(remain, 2000L), TimeUnit.MILLISECONDS);
            if (ev == null) {
                if (!isBusy(act)) {
                    if (!job.started) {
                        writeSse(out, errorJson("generation did not start (check the app screen)"));
                        return;
                    }
                    idleTicks++;
                    if (idleTicks >= 4) {
                        // busy went false but the finish hook did not fire: fall back to lastFile
                        log("finish hook missing, falling back to lastFile");
                        job.cancelled = false;
                        job.hot = false;
                        job.genMs = System.currentTimeMillis() - job.startMs;
                        File f = lastFile();
                        if (f != null && f.lastModified() >= job.startMs - 5000L) {
                            job.ok = true;
                            job.lastFilePath = f.getAbsolutePath();
                        } else {
                            job.ok = false;
                        }
                        break;
                    }
                } else {
                    idleTicks = 0;
                }
                continue;
            }
            idleTicks = 0;
            if (EVT_DONE.equals(ev)) {
                break;
            }
            writeSse(out, ev);
            out.flush();
        }

        finishResponse(act, job, out);
    }

    private static void finishResponse(Activity act, Job job, OutputStream out) {
        try {
            if (job.cancelled) {
                writeSse(out, errorJson("generation was cancelled"));
                return;
            }
            if (!job.ok) {
                writeSse(out, errorJson("generation failed: " + failureHint(job)));
                return;
            }
            Bitmap bmp = null;
            if (job.lastFilePath != null) {
                try {
                    bmp = BitmapFactory.decodeFile(job.lastFilePath);
                } catch (Throwable t) {
                    log("decode " + job.lastFilePath + ": " + t);
                }
            }
            if (bmp == null && job.bitmap != null) {
                bmp = job.bitmap;
            }
            if (bmp == null) {
                writeSse(out, errorJson("finished but the picture file could not be read"));
                return;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            byte[] rgb;
            try {
                rgb = toRgb(bmp);
            } finally {
                if (bmp != job.bitmap && !bmp.isRecycled()) {
                    bmp.recycle();
                }
            }
            String b64 = Base64.encodeToString(rgb, Base64.NO_WRAP);
            rgb = null;
            long ms = job.genMs > 0 ? job.genMs : (System.currentTimeMillis() - job.startMs);
            StringBuilder sb = new StringBuilder(b64.length() + 160);
            sb.append("{\"type\":\"complete\",\"image\":\"").append(b64)
                    .append("\",\"width\":").append(w)
                    .append(",\"height\":").append(h)
                    .append(",\"seed\":").append(job.seed)
                    .append(",\"generation_time_ms\":").append(ms)
                    .append("}");
            b64 = null;
            String json = sb.toString();
            sb = null;
            writeSse(out, json);
            out.flush();
            log("complete " + w + "x" + h + " ms=" + ms + " seed=" + job.seed + " bytes=" + json.length());
        } catch (Throwable t) {
            lastError = "finish: " + t;
            log("finish: " + stack(t));
            try {
                writeSse(out, errorJson("failed to return the picture: " + t));
            } catch (Throwable ignored) {
            }
        }
    }

    private static String failureHint(Job job) {
        if (job.hot) {
            return "the phone got too hot";
        }
        return "see the app log (files/anima/animad.log) or /log";
    }

    /**
     * Headless fallback: drives MainRun.runOne() directly on a worker thread, without the
     * activity pipeline (no foreground service, no notification, no dialogs). Needed because
     * Android 12+ blocks startForegroundService() from a background app, which is exactly the
     * situation when SillyTavern (the browser) is in front and HexAnima is behind it.
     */
    private static boolean startWithRunOne(final Activity act, final Job job) {
        try {
            log("fallback: driving runOne() headless");
            setField(act, "curIdx", Integer.valueOf(0));
            setField(act, "curN", Integer.valueOf(1));
            setField(act, "countOv", Integer.valueOf(0));
            setField(act, "cancelled", Boolean.FALSE);
            setField(act, "varInit", null);
            setField(act, "stepsOv", Integer.valueOf(0));
            setField(act, "cfgOv", null);
            setField(act, "cutOv", Integer.valueOf(0));
            setField(act, "picStart", Long.valueOf(System.currentTimeMillis()));
            setField(act, "remainS", Integer.valueOf(-1));
            setField(act, "hotStop", Boolean.FALSE);

            final Method runOne = findMethod(act.getClass(), "runOne",
                    new Class[]{String.class, String.class, int.class, int.class, long.class});
            setField(act, "busy", Boolean.TRUE);
            try {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            Object bar = getField(act, "bar");
                            if (bar instanceof View) {
                                ((View) bar).setVisibility(View.GONE);
                            }
                            Object st = getField(act, "status");
                            if (st instanceof android.widget.TextView) {
                                ((android.widget.TextView) st).setText("");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
            job.started = true;
            job.startMs = System.currentTimeMillis();

            Thread worker = new Thread(new Runnable() {
                public void run() {
                    Object file = null;
                    try {
                        file = runOne.invoke(act, job.prompt, String.valueOf(job.seed),
                                Integer.valueOf(job.width), Integer.valueOf(job.height),
                                Long.valueOf(job.startMs));
                        job.ok = file instanceof File;
                        if (job.ok) {
                            job.lastFilePath = ((File) file).getAbsolutePath();
                        }
                    } catch (Throwable t) {
                        job.ok = false;
                        log("runOne failed: " + stack(t));
                    } finally {
                        job.genMs = System.currentTimeMillis() - job.startMs;
                        job.cancelled = false;
                        try {
                            setField(act, "busy", Boolean.FALSE);
                            setField(act, "cancelled", Boolean.FALSE);
                        } catch (Throwable ignored) {
                        }
                        log("runOne done ok=" + job.ok + " file=" + job.lastFilePath + " ms=" + job.genMs);
                        job.events.offer(EVT_DONE);
                    }
                }
            }, "hexanima-gen");
            worker.setDaemon(true);
            worker.start();
            return true;
        } catch (Throwable t) {
            log("startWithRunOne: " + stack(t));
            return false;
        }
    }

    /** Runs on the UI thread: fills the widgets and presses Generate. */
    private static void startOnUi(Activity act, Job job) throws Exception {
        if (isBusy(act)) {
            throw new IllegalStateException("HexAnima is busy (already generating)");
        }
        String model = (String) getField(act, "model");
        if (model == null || model.length() == 0) {
            throw new IllegalStateException("no model: open HexAnima and download/select a model first");
        }

        SharedPreferences sp = act.getSharedPreferences("anima", 0);
        String modelId = sp.getString("model", "");
        SharedPreferences.Editor ed = sp.edit();
        boolean changed = false;
        if (job.pSteps) {
            ed.putInt("steps@" + modelId, job.totalSteps);
            changed = true;
        }
        if (job.pCfg) {
            ed.putString("cfg@" + modelId, job.cfg);
            changed = true;
        }
        if (job.pNeg) {
            ed.putString("neg@" + modelId, job.negative);
            changed = true;
        }
        if (job.pSampling) {
            ed.putString("sampler@" + modelId, job.sampler);
            ed.putString("sched@" + modelId, job.scheduler);
            changed = true;
        }
        if (changed) {
            ed.commit();
        }

        EditText prompt = (EditText) getField(act, "prompt");
        EditText neg = (EditText) getField(act, "negEdit");
        EditText steps = (EditText) getField(act, "stepsEdit");
        EditText cfg = (EditText) getField(act, "cfgEdit");
        EditText we = (EditText) getField(act, "wEdit");
        EditText he = (EditText) getField(act, "hEdit");
        EditText seed = (EditText) getField(act, "seed");

        // Only touch the fields the request actually set: leaving the others alone keeps
        // the app's own settings (and its text watchers, which write the prefs) untouched.
        prompt.setText(job.prompt);
        if (job.pNeg && neg != null) {
            neg.setText(job.negative);
        }
        if (job.pSteps && steps != null) {
            steps.setText(String.valueOf(job.totalSteps));
        }
        if (job.pCfg && cfg != null) {
            cfg.setText(job.cfg);
        }
        if (job.pSize) {
            if (we != null) {
                we.setText(String.valueOf(job.width));
            }
            if (he != null) {
                he.setText(String.valueOf(job.height));
            }
        }
        if (seed != null) {
            seed.setText(String.valueOf(job.seed));
        }

        setField(act, "count", Integer.valueOf(1));
        setField(act, "gridKind", Integer.valueOf(0));
        setField(act, "countOv", Integer.valueOf(0));
        setField(act, "varInit", null);

        job.canFallback = true;
        job.started = true;
        Method gen = findMethod(act.getClass(), "generate", new Class[]{boolean.class});
        gen.invoke(act, Boolean.FALSE);

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        if (!isBusy(act) && !job.done) {
            job.started = false;
            throw new IllegalStateException("generate() returned without starting (size/prompt rejected?)");
        }
        log("started " + job.width + "x" + job.height + " steps=" + job.totalSteps);
    }

    /* ------------------------------------------------------------------ */
    /* payload mapping                                                     */
    /* ------------------------------------------------------------------ */

    /** Snapshot of the values HexAnima would use if Generate were pressed in its own UI. */
    private static final class AppParams {
        int steps = 0;
        String cfg = "";
        String sampler = "";
        String scheduler = "";
        String negative = "";
        int width = 0;
        int height = 0;
        String error = "";
    }

    /**
     * Reads the app's current settings. Steps/cfg/sampler/scheduler/negative come from the
     * same Settings.* accessors MainRun uses when it generates, so the inherited value is
     * literally what the app would do on its own; the size comes from the width/height
     * fields, which generate() reads directly.
     */
    private static AppParams readAppParams(Activity act) {
        AppParams p = new AppParams();
        try {
            Class<?> s = Class.forName("com.hexanima.npu.Settings");
            p.steps = intOr(callStatic(s, "steps", act), 0);
            p.cfg = strOr(callStatic(s, "cfg", act), "");
            p.sampler = strOr(callStatic(s, "sampler", act), "");
            p.scheduler = strOr(callStatic(s, "scheduler", act), "");
            p.negative = strOr(callStatic(s, "negative", act), "");
        } catch (Throwable t) {
            p.error = "settings: " + t;
        }
        int[] wh = liveSize(act);
        p.width = wh[0];
        p.height = wh[1];
        if (p.width <= 0 || p.height <= 0) {
            if (p.error.length() == 0) {
                p.error = "size: " + p.width + "x" + p.height;
            }
        }
        return p;
    }

    /**
     * Resolves every parameter: a value in the request wins, anything missing or empty
     * falls back to the app's own setting. `null` is the only way to ask for an empty
     * negative prompt on purpose.
     */
    private static void applyParams(Job job, JSONObject req, AppParams app) {
        StringBuilder inh = new StringBuilder();

        if (req.has("negative_prompt") && req.isNull("negative_prompt")) {
            job.negative = "";
            job.pNeg = true;
        } else if (hasText(req, "negative_prompt")) {
            job.negative = req.optString("negative_prompt", "");
            job.pNeg = true;
        } else {
            job.negative = app.negative;
            addTo(inh, "negative");
        }

        int steps = req.has("steps") && !req.isNull("steps") ? req.optInt("steps", 0) : 0;
        if (steps > 0) {
            job.totalSteps = clamp(steps, 1, 60);
            job.pSteps = true;
        } else {
            job.totalSteps = app.steps > 0 ? clamp(app.steps, 1, 60) : 20;
            addTo(inh, "steps");
        }

        String cfg = req.has("cfg") && !req.isNull("cfg") ? normNum(req.optString("cfg", "").trim()) : "";
        if (cfg.length() > 0 && isFloat(cfg)) {
            job.cfg = cfg;
            job.pCfg = true;
        } else {
            job.cfg = app.cfg.length() > 0 && isFloat(app.cfg) ? normNum(app.cfg) : "4";
            addTo(inh, "cfg");
        }

        String schedReq = req.has("scheduler") && !req.isNull("scheduler") ? req.optString("scheduler", "").trim() : "";
        String sampReq = req.has("sampler") && !req.isNull("sampler") ? req.optString("sampler", "").trim() : "";
        if (schedReq.length() > 0) {
            String[] sc = mapScheduler(schedReq);
            job.sampler = sc[0];
            job.scheduler = sc[1];
            job.pSampling = true;
        } else if (sampReq.length() > 0) {
            job.sampler = mapScheduler(sampReq)[0];
            job.scheduler = app.scheduler.length() > 0 ? app.scheduler : "sgm_uniform";
            job.pSampling = true;
        } else {
            job.sampler = app.sampler.length() > 0 ? app.sampler : "dpm++2m";
            job.scheduler = app.scheduler.length() > 0 ? app.scheduler : "sgm_uniform";
            addTo(inh, "sampler");
        }

        int[] wh = resolveSize(req);
        if (wh != null) {
            job.width = wh[0];
            job.height = wh[1];
            job.pSize = true;
        } else {
            job.width = app.width > 0 ? app.width : 512;
            job.height = app.height > 0 ? app.height : 512;
            addTo(inh, "size");
        }

        job.inherited = inh.toString();
    }

    private static void addTo(StringBuilder sb, String name) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(name);
    }

    private static boolean hasText(JSONObject req, String key) {
        if (!req.has(key) || req.isNull(key)) {
            return false;
        }
        String v = req.optString(key, "");
        return v != null && v.trim().length() > 0;
    }

    /** "1.0" -> "1", "4.50" -> "4.5"; anything else is returned as is. */
    private static String normNum(String s) {
        String t = s == null ? "" : s.trim();
        if (t.endsWith(".0") && t.length() > 2) {
            return t.substring(0, t.length() - 2);
        }
        return t;
    }

    private static boolean isFloat(String s) {
        try {
            Float.parseFloat(s.trim());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Width/height as the app itself would use them: the two size boxes, then its fields. */
    private static int[] liveSize(final Activity act) {
        final int[] wh = new int[]{0, 0};
        final boolean[] done = new boolean[1];
        try {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    synchronized (wh) {
                        try {
                            wh[0] = editInt(act, "wEdit");
                            wh[1] = editInt(act, "hEdit");
                        } catch (Throwable ignored) {
                        }
                        done[0] = true;
                        wh.notifyAll();
                    }
                }
            });
            synchronized (wh) {
                long until = System.currentTimeMillis() + 2000L;
                while (!done[0]) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0L) {
                        break;
                    }
                    wh.wait(left);
                }
            }
        } catch (Throwable ignored) {
        }
        if (wh[0] > 0 && wh[1] > 0) {
            return new int[]{wh[0], wh[1]};
        }
        try {
            int rw = intOr(getField(act, "resW"), 0);
            int rh = intOr(getField(act, "resH"), 0);
            if (rw > 0 && rh > 0) {
                return new int[]{rw, rh};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0};
    }

    private static int editInt(Object act, String name) throws Exception {
        Object o = getField(act, name);
        if (!(o instanceof EditText)) {
            return 0;
        }
        try {
            return Integer.parseInt(((EditText) o).getText().toString().trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long randomSeed() {
        long v = System.nanoTime() ^ (new Random().nextLong() << 1);
        v = Math.abs(v) % 2147483647L;
        return v == 0 ? 12345L : v;
    }

    private static String[] mapScheduler(String s) {
        String sampler = "dpm++2m";
        String sched = "sgm_uniform";
        String t = s == null ? "" : s.trim().toLowerCase(Locale.US);
        if (t.endsWith("_karras")) {
            sched = "karras";
            t = t.substring(0, t.length() - 7);
        } else if (t.endsWith("_exponential")) {
            sched = "exponential";
            t = t.substring(0, t.length() - 12);
        } else if (t.endsWith("_normal") || t.endsWith("_discrete")) {
            sched = "discrete";
            t = t.substring(0, t.indexOf('_', 1));
        }
        if (t.contains("lcm")) {
            sampler = "lcm";
        } else if (t.contains("dpm++2m_sde") || t.contains("dpmpp2msde")) {
            sampler = "dpm++2m_sde";
        } else if (t.contains("dpm++2m") || t.contains("dpmpp2m")) {
            sampler = "dpm++2m";
        } else if (t.contains("dpm2")) {
            sampler = "dpm2";
        } else if (t.contains("euler_a") || t.contains("eulera") || t.contains("ancestral")) {
            sampler = "euler_a";
        } else if (t.contains("euler")) {
            sampler = "euler";
        } else if (t.contains("ddim")) {
            sampler = "ddim_trailing";
        } else if (t.contains("lms")) {
            sampler = "lms";
        } else if (t.contains("heun")) {
            sampler = "heun";
        } else if (t.contains("res_multistep") || t.contains("resmultistep")) {
            sampler = "res_multistep";
        } else if (t.contains("ipndm")) {
            sampler = "ipndm";
        } else if (t.contains("tcd")) {
            sampler = "tcd";
        } else if (t.contains("er_sde") || t.contains("ersde")) {
            sampler = "er_sde";
        }
        return new String[]{sampler, sched};
    }

    private static int[] resolveSize(JSONObject req) {
        int w = 0;
        int h = 0;
        if (req.has("width") && req.has("height")) {
            w = req.optInt("width", 0);
            h = req.optInt("height", 0);
        }
        if (w > 0 && h > 0) {
            return fixPatches(snap(w), snap(h));
        }
        int base = req.optInt("size", 0);
        String ar = req.optString("aspect_ratio", "");
        if (ar.length() > 0) {
            if (base <= 0) {
                base = 768;
            }
            int[] r = parseRatio(ar);
            int[] best = bestRatioSize(r[0], r[1], base);
            if (best != null) {
                return best;
            }
            double k = Math.sqrt((double) r[0] / (double) r[1]);
            w = (int) Math.round(base * k);
            h = (int) Math.round(base / k);
            return fixPatches(snap(w), snap(h));
        }
        if (base <= 0) {
            return null;
        }
        return fixPatches(snap(base), snap(base));
    }

    /** "2:3" -> {2,3}; falls back to {1,1}. */
    private static int[] parseRatio(String ar) {
        try {
            String[] p = ar.replace('\uFF1A', ':').split(":");
            if (p.length == 2) {
                int a = (int) Math.round(Double.parseDouble(p[0].trim()));
                int b = (int) Math.round(Double.parseDouble(p[1].trim()));
                if (a > 0 && b > 0) {
                    return new int[]{a, b};
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{1, 1};
    }

    /**
     * Finds w:h at the requested ratio whose patch count (w/16)*(h/16) is divisible by 32,
     * i.e. what the app accepts, closest in pixel count to base*base.
     */
    private static int[] bestRatioSize(int rw, int rh, int base) {
        int g = gcd(rw, rh);
        int a = rw / g;
        int b = rh / g;
        long target = (long) base * (long) base;
        int bw = 0;
        int bh = 0;
        long bestDelta = Long.MAX_VALUE;
        for (int k = 8; k <= 2048; k += 8) {
            int w = a * k;
            int h = b * k;
            if (w < 256 || h < 256) {
                continue;
            }
            if (w > 2048 || h > 2048) {
                break;
            }
            if ((w % 16) != 0 || (h % 16) != 0) {
                continue;
            }
            if (((w / 16) * (h / 16)) % 32 != 0) {
                continue;
            }
            long delta = Math.abs((long) w * (long) h - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                bw = w;
                bh = h;
            }
        }
        return bw == 0 ? null : new int[]{bw, bh};
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a == 0 ? 1 : a;
    }

    /** Nudges h upwards until the app's rule (w/16)*(h/16) % 32 == 0 holds. */
    private static int[] fixPatches(int w, int h) {
        int pw = w / 16;
        int ph = h / 16;
        int guard = 0;
        while (((pw * ph) % 32) != 0 && guard++ < 40) {
            ph++;
        }
        h = ph * 16;
        if (h > 2048) {
            h = 2048;
        }
        return new int[]{w, h};
    }

    private static int snap(int v) {
        if (v < 256) {
            v = 256;
        }
        if (v > 2048) {
            v = 2048;
        }
        return (v / 16) * 16;
    }

    /* ------------------------------------------------------------------ */
    /* reflection helpers                                                  */
    /* ------------------------------------------------------------------ */

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object getField(Object o, String name) throws Exception {
        if (o == null) {
            return null;
        }
        String key = o.getClass().getName() + "#" + name;
        Field f = fieldCache.get(key);
        if (f == null) {
            f = findField(o.getClass(), name);
            fieldCache.put(key, f);
        }
        return f.get(o);
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        String key = o.getClass().getName() + "#" + name;
        Field f = fieldCache.get(key);
        if (f == null) {
            f = findField(o.getClass(), name);
            fieldCache.put(key, f);
        }
        f.set(o, value);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] args) throws NoSuchMethodException {
        Class<?> c = cls;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** Calls a package-private static app method such as Settings.steps(Context). */
    private static Object callStatic(Class<?> cls, String name, Context arg) throws Exception {
        String key = cls.getName() + "#" + name;
        Method m = staticCache.get(key);
        if (m == null) {
            m = cls.getDeclaredMethod(name, new Class[]{Context.class});
            m.setAccessible(true);
            staticCache.put(key, m);
        }
        return m.invoke(null, arg);
    }

    private static int intOr(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static String strOr(Object o, String def) {
        return o instanceof String ? (String) o : def;
    }

    private static boolean isBusy(Activity act) {
        try {
            Object v = getField(act, "busy");
            return v instanceof Boolean && ((Boolean) v).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* files / json                                                        */
    /* ------------------------------------------------------------------ */

    private static File previewFile() {
        Activity act = activity;
        if (act == null) {
            return null;
        }
        return new File(new File(act.getFilesDir(), "anima"), "preview.png");
    }

    private static File lastFile() {
        Activity act = activity;
        if (act == null) {
            return null;
        }
        try {
            File f = (File) getField(act, "lastFile");
            if (f != null && f.exists()) {
                return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void serveFile(OutputStream out, File f) throws Exception {
        if (f == null || !f.exists() || f.length() == 0) {
            sendJson(out, 404, "{\"error\":\"no file\"}");
            return;
        }
        byte[] data = readWholeFile(f);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + (f.getName().endsWith(".png") ? "image/png" : "application/octet-stream") + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    private static byte[] readWholeFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static String statusJson() {
        Activity act = activity;
        String model = "";
        boolean busy = false;
        boolean loaded = false;
        try {
            if (act != null) {
                Object m = getField(act, "model");
                if (m != null) {
                    model = String.valueOf(m);
                }
                busy = isBusy(act);
                Object l = getField(act, "loaded");
                loaded = l instanceof Boolean && ((Boolean) l).booleanValue();
            }
        } catch (Throwable ignored) {
        }
        return "{\"status\":\"ok\",\"app\":\"HexAnima\",\"version\":\"" + VERSION + "\""
                + ",\"port\":" + PORT
                + ",\"activity\":" + (act != null)
                + ",\"screen\":\"" + jsonEscape(act == null ? "" : act.getClass().getSimpleName()) + "\""
                + ",\"model\":\"" + jsonEscape(model) + "\""
                + ",\"busy\":" + busy
                + ",\"model_loaded\":" + loaded
                + ",\"generating\":" + (currentJob != null)
                + ",\"last_error\":\"" + jsonEscape(lastError) + "\""
                + "}";
    }

    private static String propsJson() {
        Activity act = activity;
        String mid = "";
        int steps = 0;
        String cfg = "";
        String sampler = "";
        String sched = "";
        String neg = "";
        int w = 0;
        int h = 0;
        try {
            if (act != null) {
                mid = act.getSharedPreferences("anima", 0).getString("model", "");
                AppParams p = readAppParams(act);
                steps = p.steps;
                cfg = p.cfg;
                sampler = p.sampler;
                sched = p.scheduler;
                neg = p.negative;
                w = p.width;
                h = p.height;
            }
        } catch (Throwable ignored) {
        }
        return "{\"model_id\":\"" + jsonEscape(mid) + "\",\"steps\":" + steps
                + ",\"cfg\":\"" + jsonEscape(cfg) + "\""
                + ",\"sampler\":\"" + jsonEscape(sampler) + "\""
                + ",\"scheduler\":\"" + jsonEscape(sched) + "\""
                + ",\"negative\":\"" + jsonEscape(neg) + "\""
                + ",\"width\":" + w
                + ",\"height\":" + h
                + "}";
    }

    private static byte[] toRgb(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] out = new byte[w * h * 3];
        int j = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            out[j++] = (byte) (c >> 16);
            out[j++] = (byte) (c >> 8);
            out[j++] = (byte) c;
        }
        return out;
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String errorJson(String msg) {
        lastError = msg;
        return "{\"type\":\"error\",\"message\":\"" + jsonEscape(msg) + "\"}";
    }

    /* ------------------------------------------------------------------ */
    /* http plumbing                                                       */
    /* ------------------------------------------------------------------ */

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
        }
        if (!any) {
            return null;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static byte[] readN(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n <= 0) {
                break;
            }
            off += n;
        }
        if (off < len) {
            byte[] cut = new byte[off];
            System.arraycopy(buf, 0, cut, 0, off);
            return cut;
        }
        return buf;
    }

    private static void writeHead(OutputStream out, int code, String contentType, int length, boolean cors)
            throws Exception {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(code == 200 ? "OK" : (code == 204 ? "No Content" : (code == 404 ? "Not Found" : "Error"))).append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (code != 204) {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        sb.append("Cache-Control: no-store\r\n");
        if (cors) {
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Content-Type\r\n");
            sb.append("Access-Control-Allow-Private-Network: true\r\n");
            sb.append("Access-Control-Max-Age: 86400\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    private static void writeSseHead(OutputStream out) throws Exception {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "X-Accel-Buffering: no\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.flush();
    }

    private static void writeSse(OutputStream out, String json) throws Exception {
        out.write(("data: " + json + "\n\n").getBytes("UTF-8"));
    }

    private static void sendJson(OutputStream out, int code, String json) throws Exception {
        byte[] b = json.getBytes("UTF-8");
        writeHead(out, code, "application/json; charset=utf-8", b.length, true);
        out.write(b);
        out.flush();
    }

    private static void sendText(OutputStream out, int code, String text) throws Exception {
        byte[] b = text.getBytes("UTF-8");
        writeHead(out, code, "text/plain; charset=utf-8", b.length, true);
        out.write(b);
        out.flush();
    }

    /* ------------------------------------------------------------------ */
    /* built-in test page  (open http://127.0.0.1:8081/test on the phone)   */
    /* ------------------------------------------------------------------ */

    private static String testPage() {
        StringBuilder b = new StringBuilder(6144);
        b.append("<!doctype html><html><head><meta charset='utf-8'>");
        b.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        b.append("<title>HexAnima API 自测</title><style>");
        b.append("body{font-family:-apple-system,sans-serif;margin:16px;background:#111;color:#eee}");
        b.append("textarea,input,button{width:100%;font-size:15px;padding:8px;margin:4px 0;border-radius:8px;");
        b.append("border:1px solid #444;background:#1e1e1e;color:#eee;box-sizing:border-box}");
        b.append("button{background:#3a7;border:0;font-weight:600;color:#041;padding:12px}");
        b.append("img{max-width:100%;border-radius:8px;margin-top:8px}");
        b.append("#s{white-space:pre-wrap;font-size:12px;color:#9c9;background:#000;padding:8px;");
        b.append("border-radius:8px;max-height:220px;overflow:auto;margin-top:8px}");
        b.append(".row{display:flex;gap:8px}.row>*{flex:1}</style></head><body>");
        b.append("<h3>HexAnima 本地接口自测</h3>");
        b.append("<div id='h'>正在检测服务…</div>");
        b.append("<div id='p' style='font-size:12px;color:#9cf;margin:4px 0'></div>");
        b.append("<textarea id='prompt' rows='3'>1girl, solo, detailed face, soft light</textarea>");
        b.append("<textarea id='neg' rows='2'>worst quality, low quality, blurry, watermark</textarea>");
        b.append("<div class='row'><input id='steps' value='20'><input id='cfg' value='4'></div>");
        b.append("<div class='row'><input id='w' value='768'><input id='h' value='768'></div>");
        b.append("<div class='row'><input id='seed' placeholder='seed 留空随机'></div>");
        b.append("<label style='font-size:13px'><input type='checkbox' id='min' style='width:auto'> 极简模式：只发提示词，其它参数用 HexAnima 里的设置</label>");
        b.append("<button id='go'>生成</button>");
        b.append("<div id='st' style='margin-top:8px'></div>");
        b.append("<div id='pv'></div><div id='s'></div>");
        b.append("<script>");
        b.append("var el=function(i){return document.getElementById(i)};");
        b.append("var t0=0;");
        b.append("function log(x){var s=el('s');s.textContent=(new Date().toLocaleTimeString())+' '+x+'\\n'+s.textContent}");
        b.append("fetch('/health').then(function(r){return r.json()}).then(function(j){");
        b.append("el('h').textContent='服务在线 · '+j.version+' · 模型:'+(j.model||'未选择')+' · busy:'+j.busy})");
        b.append(".catch(function(e){el('h').textContent='服务不可用: '+e});");
        b.append("fetch('/props').then(function(r){return r.json()}).then(function(j){");
        b.append("el('p').textContent='软件当前设置: '+j.steps+' 步 · cfg '+j.cfg+' · '+j.sampler+'/'+j.scheduler+' · '+j.width+'x'+j.height})");
        b.append(".catch(function(e){});");
        b.append("el('go').onclick=function(){");
        b.append("var body={prompt:el('prompt').value,show_diffusion_process:false};");
        b.append("if(!el('min').checked){body.negative_prompt=el('neg').value;");
        b.append("body.steps=Number(el('steps').value);body.cfg=Number(el('cfg').value);");
        b.append("body.width=Number(el('w').value);body.height=Number(el('h').value);");
        b.append("body.scheduler='dpm++2m_karras';}");
        b.append("if(el('seed').value!==''){body.seed=Number(el('seed').value)}");
        b.append("t0=Date.now();el('go').disabled=true;el('pv').innerHTML='';");
        b.append("fetch('/generate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})");
        b.append(".then(function(r){var rd=r.body.getReader();var dec=new TextDecoder();var buf='';");
        b.append("function step(){rd.read().then(function(res){if(res.done){el('go').disabled=false;return}");
        b.append("buf+=dec.decode(res.value,{stream:true});var ls=buf.split('\\n');buf=ls.pop();");
        b.append("ls.forEach(function(l){if(l.indexOf('data: ')!==0)return;var e;try{e=JSON.parse(l.slice(6))}catch(x){return}");
        b.append("if(e.type==='progress'){var pc=e.percent||0;el('st').textContent='进度 '+pc+'%  ('+e.step+'/'+e.total_steps+')'}");
        b.append("else if(e.type==='complete'){el('st').textContent='完成 '+((Date.now()-t0)/1000).toFixed(1)+'s  seed='+e.seed+'  '+e.width+'x'+e.height;");
        b.append("var bin=atob(e.image),n=bin.length,arr=new Uint8Array(n);for(var i=0;i<n;i++){arr[i]=bin.charCodeAt(i)}");
        b.append("var cv=document.createElement('canvas');cv.width=e.width;cv.height=e.height;");
        b.append("var cx=cv.getContext('2d'),id=cx.createImageData(e.width,e.height);");
        b.append("for(var k=0;k<e.width*e.height;k++){id.data[k*4]=arr[k*3];id.data[k*4+1]=arr[k*3+1];id.data[k*4+2]=arr[k*3+2];id.data[k*4+3]=255}");
        b.append("cx.putImageData(id,0,0);el('pv').innerHTML='';el('pv').appendChild(cv);log('收到图片 '+e.image.length+' 字符 base64')}");
        b.append("else if(e.type==='error'){el('st').textContent='错误: '+e.message;log('error '+e.message)}");
        b.append("});step()});}step()}).catch(function(e){el('st').textContent='请求失败: '+e;el('go').disabled=false;log('fetch '+e)});};");
        b.append("</script></body></html>");
        return b.toString();
    }

    /* ------------------------------------------------------------------ */
    /* logging                                                             */
    /* ------------------------------------------------------------------ */

    private static void initLog(Context ctx) {
        try {
            logFileInternal = new File(ctx.getFilesDir(), "api.log");
        } catch (Throwable ignored) {
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                logFileExternal = new File(ext, "api.log");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readTextFile(File f, int max) {
        try {
            if (f == null || !f.isFile()) {
                return "(no " + (f == null ? "file" : f.getName()) + ")";
            }
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), max)];
                int o = 0;
                while (o < b.length) {
                    int n = in.read(b, o, b.length - o);
                    if (n <= 0) break;
                    o += n;
                }
                return new String(b, 0, o, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "(read failed: " + t + ")";
        }
    }

    private static String diagText() {
        StringBuilder b = new StringBuilder();
        b.append("version=").append(VERSION).append('\n');
        Activity a = activity;
        if (a == null) {
            b.append("activity=null\n");
            return b.toString();
        }
        b.append("activity=").append(a.getClass().getName()).append('\n');
        try {
            File dir = a.getFilesDir();
            b.append("files=").append(dir.getPath()).append('\n');
            b.append("catalog.cache=").append(new File(dir, "catalog.cache").length()).append('\n');
            File anima = new File(dir, "anima");
            File[] ds = anima.listFiles();
            int models = 0;
            StringBuilder names = new StringBuilder();
            if (ds != null) {
                for (File d : ds) {
                    if (d.isDirectory() && d.getName().startsWith("m_")) {
                        models++;
                        names.append(d.getName()).append(' ');
                    }
                }
            }
            b.append("anima_models=").append(models).append(' ').append(names).append('\n');
            File sd = new File(dir, "sd");
            File[] ss = sd.listFiles();
            b.append("sd_stubs=").append(ss == null ? 0 : ss.length).append('\n');
        } catch (Throwable t) {
            b.append("probe failed: ").append(t).append('\n');
        }
        b.append("--- backup.log ---\n").append(readTextFile(new File(a.getFilesDir(), "backup.log"), 40000));
        return b.toString();
    }

    private static String cardTest() {
        final Activity a = activity;
        if (a == null) {
            return "no activity\n";
        }
        final StringBuilder r = new StringBuilder();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        a.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    r.append("activity=").append(a.getClass().getName()).append('\n');
                    java.lang.reflect.Method m = Class.forName("com.hexanima.npu.Backup")
                            .getDeclaredMethod("header", Class.forName("com.hexanima.npu.ModelsActivity"));
                    m.setAccessible(true);
                    m.invoke(null, a);
                    r.append("header invoked ok\n");
                } catch (Throwable t) {
                    r.append("header threw: ").append(stack(t)).append('\n');
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        return r.toString();
    }

    private static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (logRing) {
            for (String s : logRing) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    public static void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = ts + " " + msg;
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        synchronized (logRing) {
            logRing.add(line);
            while (logRing.size() > LOG_MAX) {
                logRing.removeFirst();
            }
        }
        appendFile(logFileInternal, line);
        if (logFileExternal != null) {
            appendFile(logFileExternal, line);
        }
    }

    private static void appendFile(File f, String line) {
        if (f == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            if (f.length() > 512 * 1024) {
                f.delete();
            }
            fos = new FileOutputStream(f, true);
            fos.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String shortText(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /* ------------------------------------------------------------------ */
    /* job                                                                 */
    /* ------------------------------------------------------------------ */

    private static final class Job {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();
        String prompt = "";
        String negative = "";
        String cfg = "4";
        String sampler = "dpm++2m";
        String scheduler = "sgm_uniform";
        int width = 512;
        int height = 512;
        int totalSteps = 20;
        long seed = 0L;
        long startMs = 0L;
        boolean pNeg = false;
        boolean pSteps = false;
        boolean pCfg = false;
        boolean pSampling = false;
        boolean pSize = false;
        String inherited = "";
        volatile int lastPercent = -1;
        volatile boolean started = false;
        volatile boolean canFallback = false;
        volatile boolean done = false;
        volatile boolean ok = false;
        volatile boolean cancelled = false;
        volatile boolean hot = false;
        volatile String lastFilePath = null;
        volatile File stashedInit = null;
        volatile Bitmap bitmap = null;
        volatile long genMs = 0L;
    }
}
