package com.hexanima.npu;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * mod: the Anima LoRA library, i.e. which files the engine loads and which {@code <lora:…>} tags the prompt carries.
 *
 * <p>A library file is {@code files/anima/lora/<name>.safetensors} with a card next to it ({@code <name>.json}: name, words, tags, cat and the three
 * keys this class adds: {@code "on"}, the switch that decides whether the engine loads the file; {@code "auto"}, always put its tag in the prompt;
 * {@code "w"}, the strength of that file).
 *
 * <p>The two things are deliberately separate, because anima.sh reloads the model whenever the LORA list changes (40-60 s):
 * <ul>
 *   <li>{@link #env} — what the engine loads: the ACTIVATED files only. A prompt edit never changes this, so typing, clearing or rewriting a
 *       prompt cannot trigger a reload; flipping a switch in the library does, once.</li>
 *   <li>{@link #sync} — the {@code <lora:name:weight>} tags of the prompt: a marker of which activated LoRAs this prompt uses. A file with trigger
 *       words gets its tag when one of them is in the prompt and loses it when they are gone; {@code auto} keeps it there always. The tags are
 *       stripped from the text before it is encoded ({@link AnimaLora#tags}), so they cost no tokens and do not change the loaded set.</li>
 * </ul>
 */
final class LoraSync {
    /** <lora:name> and <lora:name:weight>, the same shape AnimaLora.tags() parses */
    static final Pattern TAG = Pattern.compile("<lora:([^:>]+)(?::\\s*([^>]*))?>", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- the card of a library file
    static File dir(File home) { return AnimaLora.dir(home); }

    static File cardFile(File lora) {
        String n = lora.getName().replaceAll("(?i)\\.safetensors$", "");
        return new File(lora.getParentFile(), n + ".json");
    }

    static JSONObject card(File lora) {
        try {
            File f = cardFile(lora);
            if (!f.isFile()) return new JSONObject();
            return new JSONObject(Models.read(f));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static void saveCard(File lora, JSONObject j) {
        try (FileWriter w = new FileWriter(cardFile(lora))) {
            w.write(j.toString());
        } catch (Throwable ignored) {
        }
    }

    /** the switch: the engine loads this file (and only changing this reloads the model) */
    static boolean on(File lora) {
        try {
            return card(lora).optBoolean("on", false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void setOn(File lora, boolean v) {
        try {
            JSONObject j = card(lora);
            j.put("on", v);
            saveCard(lora, j);
        } catch (Throwable ignored) {
        }
    }

    /** always put this file's tag in the prompt, whether or not the prompt mentions its trigger words (needs the switch too) */
    static boolean auto(File lora) {
        try {
            return card(lora).optBoolean("auto", false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void setAuto(File lora, boolean on) {
        try {
            JSONObject j = card(lora);
            j.put("auto", on);
            saveCard(lora, j);
        } catch (Throwable ignored) {
        }
    }

    /** the strength of this file (1 unless the user set one) */
    static float weight(File lora) {
        try {
            double w = card(lora).optDouble("w", 1.0);
            if (Double.isNaN(w) || w < 0) w = 1.0;
            return (float) w;
        } catch (Throwable t) {
            return 1f;
        }
    }

    static void setWeight(File lora, float w) {
        try {
            JSONObject j = card(lora);
            j.put("w", Math.round(w * 100f) / 100.0);
            saveCard(lora, j);
        } catch (Throwable ignored) {
        }
    }

    static String name(File lora) { return lora.getName().replaceAll("(?i)\\.safetensors$", ""); }

    static String fmt(float w) { return String.format(Locale.US, "%.2f", w); }

    /** the tag of one file, with its stored strength */
    static String tagOf(File lora) { return "<lora:" + name(lora) + ":" + fmt(weight(lora)) + ">"; }

    /** every LoRA in the library, sorted by name, temporary files skipped */
    static List<File> library(File home) {
        List<File> out = new ArrayList<>();
        File[] fs = dir(home).listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".safetensors") || f.getName().startsWith("new.")) continue;
                out.add(f);
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    static File find(File home, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return AnimaLora.find(home, name);
    }

    // ---------------------------------------------------------------- what the engine loads
    /** "path*weight" for every activated file, in library order — the LORA= list of the engine (stable: the prompt is not part of it) */
    static List<String> env(File home) {
        List<String> out = new ArrayList<>();
        for (File f : library(home)) if (on(f)) out.add(f.getPath() + "*" + fmt(weight(f)));
        return out;
    }

    // ---------------------------------------------------------------- trigger words
    /** lower case, a backslash before a parenthesis dropped ("si \\(arknights: endfield\\)" and "si (arknights: endfield)" are the same word), spaces collapsed */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.replace("\\(", "(").replace("\\)", ")").toLowerCase(Locale.ROOT);
        return t.replaceAll("\\s+", " ").trim();
    }

    /** the characters a trigger word may not be glued to: "si" must not match inside "sigma"; CJK and punctuation count as a boundary */
    static boolean wordChar(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'; }

    /** the trigger word or phrase appears in the prompt as a whole word */
    static boolean hit(String prompt, String trigger) {
        String p = norm(prompt), t = norm(trigger);
        if (t.isEmpty() || p.isEmpty()) return false;
        for (int from = 0; ; ) {
            int i = p.indexOf(t, from);
            if (i < 0) return false;
            int e = i + t.length();
            boolean left = i == 0 || !wordChar(p.charAt(i - 1));
            boolean right = e >= p.length() || !wordChar(p.charAt(e));
            if (left && right) return true;
            from = i + 1;
        }
    }

    /** any of the comma separated trigger words of this file is in the prompt */
    static boolean triggered(File lora, String prompt) {
        String w = Civitai.words(lora);
        if (w.isEmpty()) return false;
        for (String t : w.split(",")) if (hit(prompt, t)) return true;
        return false;
    }

    /** true when the prompt has a tag for this LoRA — a <lora:name:0> counts too, it is still a tag the user sees */
    static boolean called(String prompt, String name) {
        if (prompt == null) return false;
        Matcher m = TAG.matcher(prompt);
        String want = name.toLowerCase(Locale.ROOT);
        while (m.find()) if (m.group(1).trim().toLowerCase(Locale.ROOT).equals(want)) return true;
        return false;
    }

    /** the pattern of one LoRA's tag: <lora:name> or <lora:name:anything> */
    static Pattern tagPattern(String name) {
        return Pattern.compile("<lora:" + Pattern.quote(name) + "(?::[^>]*)?>", Pattern.CASE_INSENSITIVE);
    }

    // ---------------------------------------------------------------- the hook
    /**
     * The prompt with its {@code <lora:…>} tags brought in line with the library. Never touches what the engine loads.
     * <ul>
     *   <li>not activated → its tag is taken out (an unactivated LoRA must not appear);</li>
     *   <li>activated with trigger words → tag when one of them is in the prompt, no tag when they are gone;</li>
     *   <li>activated with {@code auto} → tag always;</li>
     *   <li>activated without trigger words → whatever the prompt has is kept, nothing is added unless {@code auto};</li>
     *   <li>the pose adapter is left alone (the POSE section owns its tag).</li>
     * </ul>
     * A tag that is there keeps its place; a missing one is appended; a wrong strength is corrected to the card's.
     */
    static String sync(File home, String prompt) {
        String p = prompt == null ? "" : prompt;
        StringBuilder add = new StringBuilder();
        String out = p;
        boolean changed = false;
        for (File f : library(home)) {
            String n = name(f);
            if (n.equalsIgnoreCase(Pose.LORA)) continue;   // the pose adapter's tag belongs to the POSE section
            boolean has = called(out, n), act = on(f), auto = auto(f), hasW = !Civitai.words(f).isEmpty();
            boolean want;
            if (!act) want = false;
            else if (hasW) want = auto || triggered(f, out);
            else want = auto || has;
            if (!want) {
                if (has) { out = tagPattern(n).matcher(out).replaceAll(""); changed = true; }
                continue;
            }
            if (!has) {
                if (add.length() > 0) add.append(", ");
                add.append(tagOf(f));
                continue;
            }
            // present: only the strength may need correcting
            String want2 = tagOf(f);
            Matcher m = tagPattern(n).matcher(out);
            if (m.find() && !m.group().equalsIgnoreCase(want2)) {
                out = out.substring(0, m.start()) + want2 + out.substring(m.end());
                changed = true;
            }
        }
        if (add.length() > 0) {
            String head = out.trim();
            out = head.isEmpty() ? add.toString() : head + (head.endsWith(",") ? " " : ", ") + add;
            changed = true;
        }
        if (!changed) return p;
        out = out.replaceAll("[ \\t]*,[ \\t]*(,[ \\t]*)+", ", ").trim();
        while (out.endsWith(",")) out = out.substring(0, out.length() - 1).trim();
        return out;
    }

    /** "name 1.00 · …" of the activated files whose tag the prompt does not have yet (what sync will append) */
    static String pending(File home, String prompt) {
        String p = prompt == null ? "" : prompt;
        StringBuilder sb = new StringBuilder();
        for (File f : library(home)) {
            if (!on(f) || called(p, name(f))) continue;
            boolean want = auto(f) || triggered(f, p);
            if (!want) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(name(f)).append(' ').append(fmt(weight(f)));
        }
        return sb.toString();
    }

    /** "name 1.00 · …" of the activated files the prompt has a tag for */
    static String inPrompt(File home, String prompt) {
        String p = prompt == null ? "" : prompt;
        StringBuilder sb = new StringBuilder();
        for (File f : library(home)) {
            if (!on(f) || !called(p, name(f))) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(name(f)).append(' ').append(fmt(weight(f)));
        }
        return sb.toString();
    }

    /** "name 1.00 · …" of the activated files — what the engine loads right now */
    static String loaded(File home) {
        StringBuilder sb = new StringBuilder();
        for (File f : library(home)) {
            if (!on(f)) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(name(f)).append(' ').append(fmt(weight(f)));
        }
        return sb.toString();
    }
}
