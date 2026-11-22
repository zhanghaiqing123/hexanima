package com.hexanima.npu;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** ML Kit 的中英翻译（com.google.mlkit:translate）。模型只有「按需下载」这一种安装方式：
 *  第一次要用时从 Google 的服务器拉 35 MB，之后永久离线（存在 App 私有目录）。
 *
 *  这个类里的所有调用都用 try/catch 包住：ML Kit 的类或初始化有问题时，词典模式仍然能用，
 *  界面上会显示「翻译引擎不可用」而不是崩掉。 */
final class TrMlKit {
    static final int NOSDK = -1, MISSING = 0, READY = 1, UNKNOWN = 2;

    private static Boolean sdk;
    private static com.google.mlkit.nl.translate.Translator client;
    private static volatile int state = UNKNOWN;

    private TrMlKit() { }

    /** ML Kit 的初始化交给这里（manifest 里没有注册它的 provider：初始化失败时只让翻译降级，不让整个 App 起不来） */
    static String initDetail = "not run";
    private static void init(Context c) {
        try { com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(c.getApplicationContext()); initDetail = "ok"; }
        catch (Throwable t) { initDetail = chain(t); }
    }

    /** 异常的完整原因链，用来在界面上显示（不看 logcat 也能知道到底哪一步失败） */
    static String chain(Throwable t) {
        if (t == null) return "null";
        StringBuilder b = new StringBuilder();
        Throwable x = t;
        for (int i = 0; x != null && i < 6; i++) {
            if (b.length() > 0) b.append(" <- ");
            b.append(x.getClass().getSimpleName());
            if (x.getMessage() != null) b.append(": ").append(x.getMessage());
            if (x.getCause() == x) break;
            x = x.getCause();
        }
        String s = b.toString();
        return s.length() > 700 ? s.substring(0, 700) + "…" : s;
    }

    static boolean hasSdk(Context c) {
        if (sdk == null) {
            try { Class.forName("com.google.mlkit.nl.translate.Translation"); init(c); sdk = Boolean.TRUE; }
            catch (Throwable t) { sdk = Boolean.FALSE; }
        }
        return sdk;
    }

    static int state() { return sdk == null ? UNKNOWN : (sdk ? state : NOSDK); }

    /** 模型在不在（顺便把结果缓存到 state） */
    static void refresh(Context c, final Consumer<Integer> cb) {
        if (!hasSdk(c)) { state = NOSDK; cb.accept(NOSDK); return; }
        try {
            com.google.mlkit.common.model.RemoteModelManager.getInstance()
                    .getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel.class)
                    .addOnSuccessListener(models -> {
                        int st = MISSING;
                        for (Object o : models) {
                            try {
                                if (o instanceof com.google.mlkit.nl.translate.TranslateRemoteModel
                                        && "zh".equals(((com.google.mlkit.nl.translate.TranslateRemoteModel) o).getLanguage())) st = READY;
                            } catch (Throwable ignored) { }
                        }
                        state = st;
                        cb.accept(st);
                    })
                    .addOnFailureListener(e -> { initDetail = chain(e); state = UNKNOWN; cb.accept(UNKNOWN); });
        } catch (Throwable t) { initDetail = chain(t); state = NOSDK; cb.accept(NOSDK); }
    }

    private static com.google.mlkit.nl.translate.Translator client() {
        if (client == null) {
            com.google.mlkit.nl.translate.TranslatorOptions o = new com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                    .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.CHINESE)
                    .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
                    .build();
            client = com.google.mlkit.nl.translate.Translation.getClient(o);
        }
        return client;
    }

    /** 下载中英模型（需要能访问 Google 的网络：一次即可，之后离线） */
    static void download(Context c, final Consumer<String> cb) {
        if (!hasSdk(c)) { cb.accept("nosdk"); return; }
        try {
            com.google.mlkit.nl.translate.Translator t = client();
            com.google.mlkit.common.model.DownloadConditions cond = new com.google.mlkit.common.model.DownloadConditions.Builder().build();
            t.downloadModelIfNeeded(cond)
                    .addOnSuccessListener(v -> { state = READY; cb.accept("ok"); })
                    .addOnFailureListener(e -> cb.accept("fail:" + chain(e)));
        } catch (Throwable t) { cb.accept("fail:" + chain(t)); }
    }

    static void delete(Context c, final Consumer<String> cb) {
        if (!hasSdk(c)) { cb.accept("nosdk"); return; }
        try {
            com.google.mlkit.common.model.RemoteModelManager.getInstance()
                    .deleteDownloadedModel(new com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(com.google.mlkit.nl.translate.TranslateLanguage.CHINESE).build())
                    .addOnSuccessListener(v -> { state = MISSING; if (client != null) { try { client.close(); } catch (Throwable ignored) { } client = null; } cb.accept("ok"); })
                    .addOnFailureListener(e -> cb.accept("fail:" + chain(e)));
        } catch (Throwable t) { cb.accept("fail:" + chain(t)); }
    }

    static boolean ready() { return sdk != null && sdk && state == READY; }

    /** 翻一段（回调在主线程；失败时回调原文，界面上不会丢内容） */
    static void translateOne(Context c, final String s, final Consumer<String> cb) {
        if (!hasSdk(c) || s == null || s.trim().isEmpty()) { cb.accept(s); return; }
        try {
            client().translate(s)
                    .addOnSuccessListener(en -> cb.accept(en))
                    .addOnFailureListener(e -> cb.accept(s));
        } catch (Throwable t) { cb.accept(s); }
    }

    /** 一串分段按顺序翻（回调在主线程） */
    static void translateAll(Context c, final List<String> parts, final Consumer<List<String>> cb) {
        final List<String> out = new ArrayList<>(parts.size());
        each(c, parts, 0, out, cb);
    }

    private static void each(Context c, final List<String> parts, final int i, final List<String> out, final Consumer<List<String>> cb) {
        if (i >= parts.size()) { cb.accept(out); return; }
        translateOne(c, parts.get(i), en -> { out.add(en); each(c, parts, i + 1, out, cb); });
    }
}
