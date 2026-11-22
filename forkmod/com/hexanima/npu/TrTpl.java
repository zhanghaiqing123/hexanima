package com.hexanima.npu;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 云端翻译的提示词模板。
 *
 *  两种模式：
 *   - 简单（mode=0）：system + user 两个框，$1 插在 user 里（system 里写也行）；
 *   - 高级（mode=1）：直接给 messages 数组的 JSON，App 不注入任何身份，$1 在任意 content 里。
 *  模板数量不固定，用户自己增删改名字；都存在一个 JSON 数组里。 */
final class TrTpl {
    static final int SIMPLE = 0, ADVANCED = 1;

    String id;
    String name;
    int mode;
    String system = "";
    String user = "";
    String adv = "";

    TrTpl(String id, String name, int mode) { this.id = id; this.name = name; this.mode = mode; }

    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("mode", mode);
            o.put("system", system); o.put("user", user); o.put("adv", adv);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    static TrTpl fromJson(JSONObject o) {
        TrTpl t = new TrTpl(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "模板"), o.optInt("mode", SIMPLE));
        t.system = o.optString("system", "");
        t.user = o.optString("user", "");
        t.adv = o.optString("adv", "");
        return t;
    }

    String tag(Context c) { return mode == SIMPLE ? Tr.s(c, "[simple]", "[极简]") : Tr.s(c, "[advanced]", "[高级]"); }

    // ── 存储 ───────────────────────────────────────────────────────────────────────
    private static SharedPreferences p(Context c) { return c.getSharedPreferences("anima", 0); }

    static List<TrTpl> all(Context c) {
        List<TrTpl> out = new ArrayList<>();
        String s = p(c).getString("tr_tpls", "");
        if (!s.isEmpty()) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
            } catch (Exception ignored) { }
        }
        if (out.isEmpty()) { out.addAll(defaults()); save(c, out); }
        return out;
    }

    static void save(Context c, List<TrTpl> list) {
        JSONArray a = new JSONArray();
        for (TrTpl t : list) a.put(t.toJson());
        p(c).edit().putString("tr_tpls", a.toString()).apply();
    }

    static TrTpl current(Context c) {
        String id = p(c).getString("tr_tpl_sel", "");
        List<TrTpl> l = all(c);
        for (TrTpl t : l) if (t.id.equals(id)) return t;
        return l.get(0);   // 默认第一套
    }

    static void select(Context c, TrTpl t) { p(c).edit().putString("tr_tpl_sel", t.id).apply(); }

    static TrTpl add(Context c, TrTpl t) {
        List<TrTpl> l = all(c);
        l.add(t);
        save(c, l);
        return t;
    }

    static void update(Context c, TrTpl t) {
        List<TrTpl> l = all(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(t.id)) { l.set(i, t); break; }
        save(c, l);
    }

    static void remove(Context c, TrTpl t) {
        List<TrTpl> l = all(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(t.id)) { l.remove(i); break; }
        if (l.isEmpty()) l.addAll(defaults());
        save(c, l);
        if (t.id.equals(p(c).getString("tr_tpl_sel", ""))) p(c).edit().remove("tr_tpl_sel").apply();
    }

    static String newId() { return UUID.randomUUID().toString().substring(0, 8); }

    static List<TrTpl> defaults() {
        List<TrTpl> l = new ArrayList<>();

        TrTpl a = new TrTpl("tpl-direct", "直译（省 token）", SIMPLE);
        a.system = "";
        a.user = "Please translate the following Chinese into English.\n"
                + "Keep every tag, LoRA tag, bracket and weight exactly as it is. Output only the translation:\n\n$1";
        l.add(a);

        TrTpl b = new TrTpl("tpl-painter", "画师风（中→英）", SIMPLE);
        b.system = "你是 Anime 生图提示词翻译引擎，只负责精准、原生、无损地翻译 AI 绘图关键词。\n"
                + "规则：\n"
                + "1. 只翻译自然语言描述，不翻译、不修改、不替换任何 AI 标签符号；画师名、角色名、作品名、LoRA 标签、模型名、所有括号 () [] {}、所有权重标记（:: 1.5 0.65 等）、视角与镜头标签（cowboy shot、from behind、pov）、画质参数（depth of field、dappled sunlight、film grain）一律原样保留。\n"
                + "2. 禁止净化、屏蔽、删减任何画面描述，全部直译、完整保留。\n"
                + "3. 风格：原生英文画师口吻，简洁、精准、有氛围感，不要书面语、不要中式英语。\n"
                + "4. 不扩写、不精简、不脑补、不优化。\n"
                + "5. 只输出纯译文：不解释、不加注释、不换行乱排、不拆分标签。";
        b.user = "以下是需要翻译的内容：\n$1";
        l.add(b);

        TrTpl c = new TrTpl("tpl-adv", "高级：messages JSON（示例）", ADVANCED);
        c.adv = "[\n"
                + "  {\"role\":\"system\",\"content\":\"You are a translation engine for anime image prompts. Translate the user's Chinese into English painter-style prompt text. Keep tags, brackets and weights unchanged. Output only the translation.\"},\n"
                + "  {\"role\":\"user\",\"content\":\"$1\"}\n"
                + "]";
        l.add(c);

        return l;
    }
}
