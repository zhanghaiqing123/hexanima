package com.hexanima.npu;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** 中文提示词 → 英文提示词的分拣器。所有引擎共用同一条流水线：
 *
 *    规范化（全角标点 → 逗号）
 *      → 按逗号切段
 *      → 每段里做词典最长匹配
 *          整段的中文都被词条吃掉 → 直接换成标准标签，不再进模型
 *          还有中文没被吃掉       → 这一整段留给模型（不拆碎：能替换的替换、不能的整段交模型，
 *                                   免得出现 bento + 「偷吃的」这种半成品）
 *      → 把「留给模型」的段按原顺序用逗号拼成一句，**一次**请求
 *      → 模型回来后按逗号拆开回填；段数对不上就整块放在第一个待翻位置（聚拢）
 *
 *  「仅词典」档不请求模型：没命中的段原样保留中文，用户能看见哪些词不在表里。 */
final class TrEngine {
    interface Sink { void done(String en, int dictSegs, int modelSegs); }

    static final class Plan {
        final String[] out;                 // 每段的最终文本；null = 等模型
        final String[] src;                 // 每段原文
        final List<Integer> pend = new ArrayList<>();
        int dictSegs;
        String pending;                     // 拼给模型的文本；null = 没有需要模型的段

        Plan(int n) { out = new String[n]; src = new String[n]; }

        boolean any() { return pending != null; }
        int segs() { return out.length; }

        /** 把模型结果合回原段序；modelText 传 null = 只用词典（未命中的段保留中文） */
        String merge(String modelText) {
            String[] res = null;
            String whole = null;
            if (modelText != null) {
                whole = tidy(modelText);
                if (whole.isEmpty()) whole = null;
                else {
                    String[] sp = whole.split(",");
                    if (sp.length == pend.size()) {
                        res = new String[sp.length];
                        for (int k = 0; k < sp.length; k++) res[k] = sp[k].trim();
                    }
                }
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < out.length; i++) {
                String s = out[i];
                if (s != null) { add(b, s); continue; }
                int at = pend.indexOf(i);
                if (at < 0) continue;
                if (res != null) s = res[at];                       // 段数对得上：逐段回填
                else if (whole != null) s = (at == 0 ? whole : null); // 对不上：整块放第一个待翻位置
                else s = src[i];                                    // 仅词典档：保留中文
                if (s == null) continue;
                add(b, s);
            }
            return tidy(b.toString());
        }

        private static void add(StringBuilder b, String s) {
            if (s == null || s.trim().isEmpty()) return;
            if (b.length() > 0) b.append(", ");
            b.append(s.trim());
        }
    }

    private TrEngine() { }

    static Plan plan(Context c, String normalized, boolean useDict) {
        String[] parts = normalized.split(",");
        Plan p = new Plan(parts.length);
        if (useDict) TrDict.ensure(c);
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i].trim();
            p.src[i] = seg;
            if (seg.isEmpty()) { p.out[i] = ""; continue; }
            if (!hasCjk(seg)) { p.out[i] = seg; continue; }     // 英文/数字/括号权重原样留
            if (!useDict) { p.pend.add(i); continue; }
            TrDict.Rep r = TrDict.replace(seg);
            if (r.full) { p.out[i] = r.text; p.dictSegs++; }
            else p.pend.add(i);
        }
        if (!p.pend.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (int i : p.pend) {
                if (b.length() > 0) b.append(", ");
                b.append(p.src[i]);
            }
            p.pending = b.toString();
        }
        return p;
    }

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
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (TrDict.cjk(s.charAt(i))) return true;
        return false;
    }

    /** 去掉多余逗号/空格 */
    static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        t = t.replaceAll(",\\s*,", ",").replaceAll("^[ ,]+", "").replaceAll("[ ,]+$", "");
        return t;
    }

    /** 词典换了之后没有残留要清（plan 每次现算），保留空实现给旧调用点 */
    static void clearCache() { }
}
