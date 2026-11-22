package com.hexanima.npu;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/** 中文提示词 → 英文提示词。
 *
 *  按逗号/换行切段；每段里只处理「中文片段」，其它字符（括号、权重、LoRA 标记、数字、已有的英文标签）原样保留：
 *   1. 词典（可选）对中文片段做最长匹配，命中就换成 danbooru 标准标签（"银发" → silver_hair）；
 *   2. 词典吃不掉的片段交给 ML Kit（模型已下载时）；
 *   3. 都没有就保留中文，用户能看见哪些字没翻出来。
 *  最后把各段按逗号拼回去。 */
final class TrEngine {
    interface Sink { void done(String en, boolean mlUsed); }

    private static final LinkedHashMap<String, String> CACHE = new LinkedHashMap<String, String>(200, .75f, true) {
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 200; }
    };

    private TrEngine() { }

    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '，': case '、': case '；': case '：': case '。': case '．': b.append(','); break;
                case '（': b.append('('); break;
                case '）': b.append(')'); break;
                case '　': b.append(' '); break;
                case '！': b.append('!'); break;
                case '？': b.append('?'); break;
                case '“': case '”': case '‘': case '’': break;
                case '\r': b.append(' '); break;
                case '\n': b.append(','); break;
                default: b.append(c);
            }
        }
        return b.toString().replaceAll("[ \\t]{2,}", " ").trim();
    }

    static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) if (isCjk(s.charAt(i))) return true;
        return false;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    static void translate(final Context c, final String zh, final boolean useDict, final Sink cb) {
        final String norm = normalize(zh);
        if (norm.isEmpty()) { cb.done("", false); return; }
        final String key = (useDict ? "d|" : "m|") + norm;
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) { cb.done(hit, false); return; }
        }
        final String[] parts = norm.split(",");
        if (useDict) TrDict.ensure(c);
        final String[] out = new String[parts.length];
        final boolean[] mlUsed = new boolean[1];
        step(c, parts, 0, useDict, out, mlUsed, new Sink() {
            public void done(String en, boolean ml) {
                if (!en.isEmpty()) synchronized (CACHE) { CACHE.put(key, en); }
                cb.done(en, ml || mlUsed[0]);
            }
        });
    }

    private static void step(final Context c, final String[] parts, final int i, final boolean useDict,
                             final String[] out, final boolean[] mlUsed, final Sink cb) {
        if (i >= parts.length) { cb.done(rejoin(out), false); return; }
        final String seg = parts[i].trim();
        if (seg.isEmpty() || !hasCjk(seg)) {
            out[i] = seg;
            step(c, parts, i + 1, useDict, out, mlUsed, cb);
            return;
        }
        resolve(c, seg, useDict, new Consumer<String>() {
            public void accept(String en) { out[i] = en; step(c, parts, i + 1, useDict, out, mlUsed, cb); }
        });
    }

    /** 一段（可能夹着括号/权重/英文）里的中文片段逐个处理，然后原位拼回去 */
    private static void resolve(final Context c, final String seg, final boolean useDict, final Consumer<String> cb) {
        final List<int[]> runs = cjkRuns(seg);
        if (runs.isEmpty()) { cb.accept(seg); return; }
        final String[] repl = new String[runs.size()];
        runsStep(c, seg, runs, repl, 0, useDict, new Runnable() {
            public void run() { cb.accept(assemble(seg, runs, repl)); }
        });
    }

    private static void runsStep(final Context c, final String seg, final List<int[]> runs, final String[] repl,
                                 final int k, final boolean useDict, final Runnable done) {
        if (k >= runs.size()) { done.run(); return; }
        int[] r = runs.get(k);
        resolveRun(c, seg.substring(r[0], r[1]), useDict, new Consumer<String>() {
            public void accept(String en) {
                repl[k] = en;
                runsStep(c, seg, runs, repl, k + 1, useDict, done);
            }
        });
    }

    private static String assemble(String seg, List<int[]> runs, String[] repl) {
        StringBuilder b = new StringBuilder();
        int at = 0;
        for (int k = 0; k < runs.size(); k++) {
            int[] r = runs.get(k);
            b.append(seg, at, r[0]);
            String rep = repl[k];
            b.append(rep == null ? seg.substring(r[0], r[1]) : rep);
            at = r[1];
        }
        b.append(seg, at, seg.length());
        return tidy(b.toString());
    }

    /** 一个中文片段：纯中文段走「词典整段替换」，句子交给模型 */
    static void resolveRun(final Context c, final String run, final boolean useDict, final Consumer<String> cb) {
        if (!useDict) { mlOrKeep(c, run, cb); return; }
        TrDict.Seg d = TrDict.split(run);
        if (d.clean() && d.any()) { cb.accept(TrDict.join(d.tags)); return; }
        if (TrMlKit.ready()) { mlOrKeep(c, run, cb); return; }
        // 没有模型：能换的标签先换上，剩下的中文留着
        String t = TrDict.join(d.tags);
        String rest = d.rest.toString().trim();
        cb.accept((t.isEmpty() ? rest : (rest.isEmpty() ? t : t + " " + rest)));
    }

    private static void mlOrKeep(Context c, final String s, final Consumer<String> cb) {
        if (TrMlKit.ready()) {
            TrMlKit.translateOne(c, s, new Consumer<String>() {
                public void accept(String en) { cb.accept(en == null || en.trim().isEmpty() ? s : en); }
            });
        } else cb.accept(s);
    }

    private static List<int[]> cjkRuns(String s) {
        List<int[]> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            if (!isCjk(s.charAt(i))) { i++; continue; }
            int j = i;
            while (j < n && isCjk(s.charAt(j))) j++;
            out.add(new int[]{i, j});
            i = j;
        }
        return out;
    }

    /** 去掉多余逗号/空格（"(,) " 之类） */
    private static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        t = t.replaceAll(",\\s*,", ",").replaceAll("^[ ,]+", "").replaceAll("[ ,]+$", "");
        return t;
    }

    private static String rejoin(String[] out) {
        StringBuilder b = new StringBuilder();
        for (String s : out) {
            if (s == null || s.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(s.trim());
        }
        return b.toString();
    }

    /** 纯词典模式：整段都能被词表覆盖时返回标签串，否则返回 null（交给模型）。
     *  用于"本地大模型"档：标签式输入仍然走词典，只有句子才麻烦模型。 */
    static String dictOnly(Context c, String zh) {
        TrDict.ensure(c);
        if (!TrDict.ready()) return null;
        String[] parts = normalize(zh).split(",");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            String seg = p.trim();
            if (seg.isEmpty()) continue;
            if (!hasCjk(seg)) { if (out.length() > 0) out.append(", "); out.append(seg); continue; }
            TrDict.Seg d = TrDict.split(seg);
            if (!d.clean() || !d.any()) return null;
            if (out.length() > 0) out.append(", ");
            out.append(TrDict.join(d.tags));
        }
        return out.length() == 0 ? null : out.toString();
    }

    static void clearCache() { synchronized (CACHE) { CACHE.clear(); } }
}
