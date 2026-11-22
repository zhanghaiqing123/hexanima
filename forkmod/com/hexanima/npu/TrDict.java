package com.hexanima.npu;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

/** 中英提示词词典：assets/tr_dict.tsv 是内置的（danbooru 标签的中文名，约 4.5 万条），
 *  files/tr_user.tsv 是用户导入的，同一个中文键以用户表为准。格式都是「中文<TAB>english_tag」，一行一条。
 *  运行时的用法是「最长匹配」：从当前位置往前找最长的、能在表里命中的中文串，命中就换成标准标签。 */
final class TrDict {
    private static final Object LOCK = new Object();
    private static HashMap<String, String> map;
    private static int maxKey = 6;
    private static int builtin = 0;

    private TrDict() { }

    static File userFile(Context c) { return new File(c.getFilesDir(), "tr_user.tsv"); }

    /** 内置 + 用户表都已经读好的条目数（0 = 还没读到 / 没有内置表） */
    static int size() { synchronized (LOCK) { return map == null ? 0 : map.size(); } }

    static boolean ready() { synchronized (LOCK) { return map != null; } }

    static void ensure(Context c) {
        synchronized (LOCK) {
            if (map != null) return;
            HashMap<String, String> m = new HashMap<>(60000);
            int mx = load(c, m, true);
            File u = userFile(c);
            if (u.isFile()) mx = Math.max(mx, load(c, m, false));
            builtin = m.size();
            map = m;
            maxKey = Math.min(Math.max(mx, 2), 24);
        }
    }

    /** 读一张表；返回最长键的长度（读失败时返回 0） */
    private static int load(Context c, HashMap<String, String> m, boolean asset) {
        BufferedReader r = null;
        int mx = 0;
        try {
            if (asset) r = new BufferedReader(new InputStreamReader(c.getAssets().open("tr_dict.tsv"), "UTF-8"));
            else r = new BufferedReader(new InputStreamReader(new FileInputStream(userFile(c)), "UTF-8"));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                int t = l.indexOf('\t');
                if (t <= 0) continue;
                String k = l.substring(0, t).trim();
                String v = l.substring(t + 1).trim();
                if (k.isEmpty() || v.isEmpty() || k.length() > 24) continue;
                // 允许一个中文键对多个标签（用逗号隔开），也可以是别名列表
                m.put(k, v);
                if (k.length() > mx) mx = k.length();
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (IOException ignored) { }
        }
        return mx;
    }

    static String exact(String k) { synchronized (LOCK) { return map == null ? null : map.get(k); } }

    /** 用户表换了（导入 / 清空）：丢掉已加载的表，下次用时重读 */
    static void reset() { synchronized (LOCK) { map = null; maxKey = 6; } }

    /** 一段文字的词典结果：命中的标签，以及没有被任何一个词条吃掉的剩余文字 */
    static final class Seg {
        final ArrayList<String> tags = new ArrayList<>();
        final StringBuilder rest = new StringBuilder();
        boolean any() { return !tags.isEmpty(); }
        boolean clean() { return rest.toString().trim().isEmpty(); }
    }

    /** 最长匹配：命中就出标签，没命中就原样留在 rest 里（留给 ML Kit / 用户看） */
    static Seg split(String s) {
        Seg out = new Seg();
        int n = s.length();
        int i = 0;
        StringBuilder gap = new StringBuilder();
        while (i < n) {
            int hit = 0;
            String tag = null;
            int lim = Math.min(maxKey, n - i);
            for (int L = lim; L >= 1; L--) {
                String cand = s.substring(i, i + L);
                String t = exact(cand);
                if (t != null) { hit = L; tag = t; break; }
            }
            if (hit > 0) {
                if (gap.length() > 0) { out.rest.append(gap); gap.setLength(0); }
                out.tags.add(tag);
                i += hit;
            } else {
                gap.append(s.charAt(i));
                i++;
            }
        }
        if (gap.length() > 0) out.rest.append(gap);
        return out;
    }

    /** 把命中的标签去重后拼起来（保持出现顺序） */
    static String join(ArrayList<String> tags) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String t : tags) {
            for (String p : t.split(",")) {
                String q = p.trim();
                if (!q.isEmpty()) seen.add(q);
            }
        }
        StringBuilder b = new StringBuilder();
        for (String t : seen) { if (b.length() > 0) b.append(", "); b.append(t); }
        return b.toString();
    }
}
