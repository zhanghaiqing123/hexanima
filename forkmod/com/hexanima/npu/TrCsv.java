package com.hexanima.npu;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 把外面常见的 danbooru 中文对照 CSV 转成 App 的 tsv（中文<TAB>english_tag）。
 *
 *  真实文件长这样（10 万行，注意是标签在前）：
 *      1girl,1个女孩
 *      long_hair,长发
 *  但我们自己人也可能导出成「中文,标签」，所以先采样判断哪一列是中文，不靠用户选。
 *
 *  另外要处理：开头 BOM、引号包裹、一个中文对应多个标签（最多收 3 个，再多的都是同义词堆叠）、
 *  中文超过 24 个字符（作品名/角色全名，做最长匹配没意义）。 */
final class TrCsv {
    static final int MAX_KEY = 24, MAX_TAGS = 3;

    static final class Stat {
        int lines, keys, merged, overlength, bad;
        String err;
    }

    private TrCsv() { }

    static Stat convert(InputStream in, OutputStream out) {
        Stat st = new Stat();
        List<String[]> rows = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String l;
            boolean first = true;
            while ((l = r.readLine()) != null) {
                if (first) {                       // UTF-8 BOM
                    first = false;
                    if (!l.isEmpty() && l.charAt(0) == '\uFEFF') l = l.substring(1);
                }
                l = l.trim();
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                int p = sep(l);
                if (p <= 0 || p >= l.length() - 1) { st.bad++; continue; }
                String a = clean(l.substring(0, p)), b = clean(l.substring(p + 1));
                if (a.isEmpty() || b.isEmpty()) { st.bad++; continue; }
                rows.add(new String[]{a, b});
                if (rows.size() >= 300000) break;   // 防御：再大也不可能是词表
            }
        } catch (Throwable t) {
            st.err = String.valueOf(t);
            return st;
        }
        st.lines = rows.size();
        if (rows.isEmpty()) { st.err = "empty"; return st; }

        // 哪一列是中文：采样前 500 行投个票（正常表里中文几乎全在同一列）
        int leftCjk = 0, rightCjk = 0;
        for (int i = 0; i < Math.min(rows.size(), 500); i++) {
            if (hasCjk(rows.get(i)[0])) leftCjk++;
            if (hasCjk(rows.get(i)[1])) rightCjk++;
        }
        if (leftCjk == 0 && rightCjk == 0) { st.err = "no-cjk"; return st; }
        boolean tagFirst = rightCjk >= leftCjk;

        LinkedHashMap<String, LinkedHashSet<String>> m = new LinkedHashMap<>(160000);
        for (String[] row : rows) {
            String tag = tagFirst ? row[0] : row[1];
            String zh = tagFirst ? row[1] : row[0];
            if (!hasCjk(zh)) { st.bad++; continue; }
            if (zh.length() > MAX_KEY) { st.overlength++; continue; }
            if (tag.contains("\t")) tag = tag.replace('\t', ' ');
            LinkedHashSet<String> set = m.get(zh);
            if (set == null) { set = new LinkedHashSet<>(); m.put(zh, set); }
            if (set.size() < MAX_TAGS) set.add(tag);
        }
        if (m.isEmpty()) { st.err = "nothing-usable"; return st; }
        st.keys = m.size();

        try {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
            for (Map.Entry<String, LinkedHashSet<String>> e : m.entrySet()) {
                if (e.getValue().size() > 1) st.merged++;
                w.write(e.getKey());
                w.write('\t');
                boolean first = true;
                for (String t : e.getValue()) { if (!first) w.write(", "); w.write(t); first = false; }
                w.write('\n');
            }
            w.flush();
        } catch (Throwable t) {
            st.err = String.valueOf(t);
        }
        return st;
    }

    /** 第一个不在引号里的分隔符（tab 或逗号）。CSV 的标签列不含逗号，所以第一个就是边界 */
    private static int sep(String s) {
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { q = !q; continue; }
            if (!q && (c == ',' || c == '\t')) return i;
        }
        return -1;
    }

    private static String clean(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
            s = s.substring(1, s.length() - 1).replace("\"\"", "\"").trim();
        return s;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) if (TrDict.cjk(s.charAt(i))) return true;
        return false;
    }
}
