package com.hexanima.npu;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

/** 中英提示词词典，三层叠起来，后面的层覆盖前面的同名键：
 *
 *    1. assets/tr_dict.tsv   内置（2.3 万条精选，人工核对过的常用写法）
 *    2. files/tr_csv.tsv     用户导入的 danbooru CSV 转换结果（10 万条，默认只补内置没有的键；
 *                            「CSV 优先」打开后它反过来覆盖内置 —— CSV 里有些机器翻译把角色名/作品名
 *                            当成了通用词，比如 猫女→catwoman、幽灵→gespenst，所以默认不覆盖）
 *    3. files/tr_user.tsv    我的词典，永远最高优先级，用来补词或纠正任何一条
 *
 *  格式都是「中文<TAB>english_tag」，一行一条，# 开头是注释。右边可以写多个标签（逗号隔开）。
 *  匹配用最长匹配：从当前位置往前找最长的、能在表里命中的中文串，命中就换成标准标签。 */
final class TrDict {
    private static final Object LOCK = new Object();
    private static HashMap<String, String> map;
    private static int maxKey = 6;
    private static int nBuiltin, nCsv, nUser;
    private static boolean csvFirst;

    private static final int PUT = 0, KEEP = 1;   // 覆盖同名键 / 只补缺
    private static final int MAX_KEY = 24;        // 再长的都是作品名，做最长匹配没意义

    private TrDict() { }

    static File userFile(Context c) { return new File(c.getFilesDir(), "tr_user.tsv"); }
    static File csvFile(Context c) { return new File(c.getFilesDir(), "tr_csv.tsv"); }

    static boolean csvFirst(Context c) {
        return c.getSharedPreferences("anima", 0).getBoolean("tr_csv_first", false);
    }

    /** 切换「CSV 优先」：立刻按新顺序重建表 */
    static void setCsvFirst(Context c, boolean v) {
        c.getSharedPreferences("anima", 0).edit().putBoolean("tr_csv_first", v).apply();
        synchronized (LOCK) { map = null; }
        ensure(c);
    }

    /** 内置 + CSV + 我的，去重后的总条数 */
    static int size() { synchronized (LOCK) { return map == null ? 0 : map.size(); } }
    static int builtinSize() { synchronized (LOCK) { return nBuiltin; } }
    static int csvSize() { synchronized (LOCK) { return nCsv; } }
    static int userSize() { synchronized (LOCK) { return nUser; } }

    static boolean ready() { synchronized (LOCK) { return map != null; } }

    static void ensure(Context c) {
        synchronized (LOCK) {
            if (map != null) return;
            HashMap<String, String> m = new HashMap<>(160000);
            boolean first = csvFirst(c);
            int mx = 0;

            int[] r;
            try { r = read(c.getAssets().open("tr_dict.tsv"), m, PUT); }
            catch (Throwable t) { r = new int[]{0, 0, 0}; }
            nBuiltin = r[2];
            mx = Math.max(mx, r[0]);

            nCsv = 0;
            File cf = csvFile(c);
            if (cf.isFile()) {
                r = read(cf, m, first ? PUT : KEEP);
                nCsv = r[2];
                mx = Math.max(mx, r[0]);
            }

            nUser = 0;
            File uf = userFile(c);
            if (uf.isFile()) {
                r = read(uf, m, PUT);
                nUser = r[2];
                mx = Math.max(mx, r[0]);
            }

            csvFirst = first;
            map = m;
            maxKey = Math.min(Math.max(mx, 2), MAX_KEY);
        }
    }

    // asset 与 files 两条路读进来的都是一张同样的表，只是来源不同
    private static int[] read(File f, HashMap<String, String> m, int mode) {
        if (!f.isFile()) return new int[]{0, 0, 0};
        try (InputStream is = new FileInputStream(f)) { return read(is, m, mode); }
        catch (Throwable t) { return new int[]{0, 0, 0}; }
    }

    /** 返回 {最长键的长度, 生效写入数, 读到的有效行数} */
    private static int[] read(InputStream src, HashMap<String, String> m, int mode) {
        BufferedReader r = null;
        int mx = 0, written = 0, seen = 0;
        try {
            r = new BufferedReader(new InputStreamReader(src, "UTF-8"));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                int t = l.indexOf('\t');
                if (t <= 0) continue;
                String k = l.substring(0, t).trim();
                String v = l.substring(t + 1).trim();
                if (k.isEmpty() || v.isEmpty() || k.length() > MAX_KEY) continue;
                seen++;
                if (mode == KEEP && m.containsKey(k)) continue;
                m.put(k, v);
                written++;
                if (k.length() > mx) mx = k.length();
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (IOException ignored) { }
        }
        return new int[]{mx, written, seen};
    }

    static String exact(String k) { synchronized (LOCK) { return map == null ? null : map.get(k); } }

    /** 三层里任意一层变了（导入 / 清空 / 切换优先级）：丢掉已加载的表，下次用时重读 */
    static void reset() { synchronized (LOCK) { map = null; maxKey = 6; nBuiltin = nCsv = nUser = 0; } }

    // ── 匹配与替换 ────────────────────────────────────────────────────────────────

    /** 一段文字的匹配结果：替换后的文本、是否整段都被词典覆盖、命中的次数 */
    static final class Rep {
        String text;
        boolean full;     // 段里所有中文都被词条吃掉 = 这一整段可以只用标签表示
        int hits;
    }

    /** 原位替换：命中就换成标签，没命中的字符原样留着。
     *  相邻两个标签之间补「, 」，中文紧贴标签时补空格，
     *  这样 (银发红瞳:1.2) → (silver_hair, red_eyes:1.2)，不会粘成一坨。 */
    static Rep replace(String s) {
        Rep out = new Rep();
        out.text = s == null ? "" : s;
        if (s == null || s.isEmpty()) { out.full = true; return out; }
        synchronized (LOCK) {
            if (map == null) { out.full = false; return out; }
            StringBuilder b = new StringBuilder(s.length() + 24);
            boolean lastTag = false, missedCjk = false;
            int n = s.length(), i = 0;
            while (i < n) {
                int hit = 0;
                String tag = null;
                int lim = Math.min(maxKey, n - i);
                for (int L = lim; L >= 1; L--) {
                    String t = map.get(s.substring(i, i + L));
                    if (t != null) { hit = L; tag = t; break; }
                }
                if (hit > 0) {
                    if (lastTag) b.append(", ");
                    else if (b.length() > 0 && cjk(b.charAt(b.length() - 1))) b.append(' ');
                    String[] ps = tag.split(",");
                    b.append(ps[0].trim());
                    for (int k = 1; k < ps.length; k++) {
                        String q = ps[k].trim();
                        if (!q.isEmpty()) b.append(", ").append(q);
                    }
                    lastTag = true;
                    out.hits++;
                    i += hit;
                } else {
                    char ch = s.charAt(i);
                    if (cjk(ch)) missedCjk = true;
                    b.append(ch);
                    lastTag = false;
                    i++;
                }
            }
            out.text = tidy(b.toString());
            out.full = out.hits > 0 && !missedCjk;
        }
        return out;
    }

    /** 输入联想：包含 q 的词条，短的在前（越短越常用），最多 limit 条。返回 {中文, 标签}。
     *  词表 11 万条，一次全扫约 20 ms，调用方放后台线程。 */
    static ArrayList<String[]> search(String q, int limit) {
        ArrayList<String[]> out = new ArrayList<>();
        if (q == null || q.isEmpty()) return out;
        synchronized (LOCK) {
            if (map == null) return out;
            for (java.util.Map.Entry<String, String> e : map.entrySet()) {
                String k = e.getKey();
                if (k.length() >= q.length() && k.contains(q)) out.add(new String[]{k, e.getValue()});
                if (out.size() >= 3000) break;          // 极端查询先截断，排完序再取前 limit 条
            }
        }
        out.sort((a, b) -> a[0].length() != b[0].length()
                ? a[0].length() - b[0].length() : a[0].compareTo(b[0]));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 把命中的标签去重后拼起来（保持出现顺序） */
    static String join(java.util.ArrayList<String> tags) {
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

    static boolean cjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    /** 去掉多余逗号/空格（替换后可能出现 "(,)" 这种） */
    static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        t = t.replaceAll(",\\s*,", ",").replaceAll(",\\s*:", ":").replaceAll("^[ ,]+", "").replaceAll("[ ,]+$", "");
        return t;
    }
}
