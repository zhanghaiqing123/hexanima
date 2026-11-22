package com.hexanima.npu;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 云端 LLM 翻译（OpenAI 兼容 /chat/completions）。
 *
 *  参数（模型、温度、附加 JSON）在设置里；"说什么"由模板决定（TrTpl），App 不注入任何身份。 */
final class TrCloud {
    interface Sink { void done(String text, String error); }

    private static final LinkedHashMap<String, String> CACHE = new LinkedHashMap<String, String>(80, .75f, true) {
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 80; }
    };

    private TrCloud() { }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("anima", 0); }

    static String base(Context c) { return p(c).getString("tr_api_base", "https://api.deepseek.com/v1").trim(); }
    static String key(Context c) { return p(c).getString("tr_api_key", ""); }
    static String model(Context c) { return p(c).getString("tr_api_model", "deepseek-chat"); }
    static float temp(Context c) { return p(c).getFloat("tr_api_temp", .3f); }
    static float topP(Context c) { return p(c).getFloat("tr_api_top_p", 1f); }
    static int maxTokens(Context c) { return p(c).getInt("tr_api_max", 1024); }
    static String extra(Context c) { return p(c).getString("tr_api_extra", ""); }
    static String provider(Context c) { return p(c).getString("tr_api_provider", "custom"); }
    static String effort(Context c) { return p(c).getString("tr_api_effort", "low"); }   // off | low | medium | high
    static void setProvider(Context c, String id) { p(c).edit().putString("tr_api_provider", id).apply(); }
    static void setEffort(Context c, String e) { p(c).edit().putString("tr_api_effort", e).apply(); synchronized (CACHE) { CACHE.clear(); } }

    static boolean configured(Context c) { return !key(c).isEmpty() && !base(c).isEmpty() && !model(c).isEmpty(); }

    static void save(Context c, String base, String key, String model, float temp, float topP, int max, String extra) {
        p(c).edit()
                .putString("tr_api_base", base.trim())
                .putString("tr_api_key", key.trim())
                .putString("tr_api_model", model.trim())
                .putFloat("tr_api_temp", temp)
                .putFloat("tr_api_top_p", topP)
                .putInt("tr_api_max", max)
                .putString("tr_api_extra", extra.trim())
                .apply();
        synchronized (CACHE) { CACHE.clear(); }
    }

    /** 常见服务商的默认值（选预设时填进去，之后随便改）。第 4 列是 provider id，决定"思考强度"怎么写 */
    static final String[][] PRESETS = {
            {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "deepseek"},
            {"通义千问（兼容模式）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "glm"},
            {"Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "kimi"},
            {"OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat", "openrouter"},
            {"硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", "siliconflow"},
            {"自定义", "", "", "custom"}
    };

    /** "思考强度"按服务商翻译成各自的字段；用户的"附加 JSON"最后覆盖 */
    /** 「不发送」= 这个参数一个都不带：有的模型没有 reasoning_effort，有的思考开关字段也不一样，
     *  与其猜一个关闭值，不如交给服务商默认 */
    static JSONObject effortFields(Context c) {
        JSONObject o = new JSONObject();
        String e = effort(c);
        if ("off".equals(e)) return o;
        try {
            switch (provider(c)) {
                case "deepseek":
                    o.put("thinking", new JSONObject().put("type", "enabled"));
                    o.put("reasoning_effort", e);
                    break;
                case "qwen":
                    o.put("enable_thinking", true);
                    break;
                case "glm":
                    o.put("thinking", new JSONObject().put("type", "enabled"));
                    break;
                default:
                    o.put("reasoning_effort", e);
                    break;
            }
        } catch (Exception ignored) { }
        return o;
    }

    // ── 模型列表 ───────────────────────────────────────────────────────────────────
    static List<String> models(Context c, String[] err) {
        List<String> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = open(base(c) + "/models", key(c));
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String body = read(conn, code);
            if (code != 200) { if (err != null) err[0] = "HTTP " + code + ": " + brief(body); return out; }
            JSONObject o = new JSONObject(body);
            JSONArray a = o.optJSONArray("data");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m != null) { String id = m.optString("id", ""); if (!id.isEmpty()) out.add(id); }
            }
            if (out.isEmpty()) { if (err != null) err[0] = "列表为空（可手填模型名）"; }
        } catch (Throwable t) {
            if (err != null) err[0] = String.valueOf(t);
        } finally { if (conn != null) conn.disconnect(); }
        return out;
    }

    // ── 翻译 ──────────────────────────────────────────────────────────────────────
    static void translate(Context c, TrTpl tpl, String text, Sink cb) {
        String cacheKey = tpl.id + "|" + model(c) + "|" + text;
        synchronized (CACHE) {
            String hit = CACHE.get(cacheKey);
            if (hit != null) { cb.done(hit, null); return; }
        }
        String[] err = new String[1];
        JSONArray msgs = messages(c, tpl, text, err);
        if (msgs == null) { cb.done(null, err[0]); return; }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model(c));
            body.put("messages", msgs);
            body.put("temperature", temp(c));
            body.put("top_p", topP(c));
            body.put("max_tokens", maxTokens(c));
            body.put("stream", false);
            JSONObject eo = effortFields(c);           // 思考强度 → 各家字段
            String ex = extra(c);
            if (!ex.isEmpty()) {                       // 用户附加 JSON 最后覆盖
                JSONObject user = new JSONObject(ex);
                java.util.Iterator<String> it = user.keys();
                while (it.hasNext()) { String k = it.next(); eo.put(k, user.get(k)); }
            }
            java.util.Iterator<String> it = eo.keys();
            while (it.hasNext()) { String k = it.next(); body.put(k, eo.get(k)); }
            conn = open(base(c) + "/chat/completions", key(c));
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            String resp = read(conn, code);
            if (code != 200) { cb.done(null, "HTTP " + code + "：" + brief(resp)); return; }
            JSONObject o = new JSONObject(resp);
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) { cb.done(null, "返回里没有 choices（" + brief(resp) + "）"); return; }
            JSONObject msg = ch.getJSONObject(0).optJSONObject("message");
            String out = msg == null ? "" : msg.optString("content", "");
            out = clean(out);
            if (out.isEmpty()) {
                String reason = msg == null ? "" : msg.optString("reasoning_content", "");
                cb.done(null, reason.isEmpty() ? "模型返回了空内容" : "只有思考内容、没有正文（可关掉思维链再试）");
                return;
            }
            synchronized (CACHE) { CACHE.put(cacheKey, out); }
            cb.done(out, null);
        } catch (Throwable t) {
            cb.done(null, brief(String.valueOf(t)));
        } finally { if (conn != null) conn.disconnect(); }
    }

    /** 按模板拼 messages；失败返回 null 并给出原因 */
    static JSONArray messages(Context c, TrTpl tpl, String text, String[] err) {
        try {
            JSONArray out = new JSONArray();
            if (tpl.mode == TrTpl.ADVANCED) {
                String adv = tpl.adv.trim();
                if (adv.isEmpty()) { err[0] = "高级模板是空的"; return null; }
                JSONArray a = new JSONArray(adv);
                boolean used = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject m = a.getJSONObject(i);
                    String role = m.optString("role", "");
                    String content = m.optString("content", "");
                    if (!role.equals("system") && !role.equals("user") && !role.equals("assistant")) {
                        err[0] = "第 " + (i + 1) + " 条的 role 只能是 system / user / assistant";
                        return null;
                    }
                    if (content.contains("$1")) { content = content.replace("$1", text); used = true; }
                    JSONObject n = new JSONObject();
                    n.put("role", role);
                    n.put("content", content);
                    out.put(n);
                }
                if (!used) {   // 没写 $1：追加到最后一条 user，或者新加一条
                    appendText(out, text);
                }
                return out;
            }
            // 简单模式：system + user
            String sys = tpl.system;
            String user = tpl.user;
            boolean used = false;
            if (sys.contains("$1")) { sys = sys.replace("$1", text); used = true; }
            if (user.contains("$1")) { user = user.replace("$1", text); used = true; }
            if (!sys.trim().isEmpty()) {
                JSONObject s = new JSONObject();
                s.put("role", "system");
                s.put("content", sys);
                out.put(s);
            }
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", used ? user : (user.isEmpty() ? text : user + "\n\n" + text));
            out.put(u);
            return out;
        } catch (Throwable t) {
            err[0] = "模板格式错误：" + t.getMessage();
            return null;
        }
    }

    private static void appendText(JSONArray msgs, String text) throws Exception {
        for (int i = msgs.length() - 1; i >= 0; i--) {
            JSONObject m = msgs.getJSONObject(i);
            if ("user".equals(m.optString("role"))) {
                m.put("content", m.optString("content", "") + "\n\n" + text);
                return;
            }
        }
        JSONObject u = new JSONObject();
        u.put("role", "user");
        u.put("content", text);
        msgs.put(u);
    }

    /** 去掉模型爱加的外壳：``` 围栏、首尾引号、前言 */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        if (t.length() > 1 && (t.startsWith("\"") && t.endsWith("\"")) ) t = t.substring(1, t.length() - 1).trim();
        t = t.replaceFirst("^(?i)(translation|英文|译文)\\s*[:：]\\s*", "");
        return t.trim();
    }

    private static HttpURLConnection open(String url, String key) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "HexAnima-Translate/1");
        return conn;
    }

    private static String read(HttpURLConnection conn, int code) throws Exception {
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] b = new char[4096];
            for (int n; (n = r.read(b)) > 0; ) { sb.append(b, 0, n); if (sb.length() > 20000) break; }
        }
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
