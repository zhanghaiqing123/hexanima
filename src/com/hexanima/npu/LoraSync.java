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
 * mod: prompt-side LoRA activation for the Anima models.
 *
 * <p>A library file is {@code files/anima/lora/<name>.safetensors} with a card next to it ({@code <name>.json}: name, words, tags, cat and the two keys
 * this class adds: {@code "auto"}, mount for every picture, and {@code "w"}, the strength written into the tag). The prompt calls a LoRA with
 * {@code <lora:name:weight>}; {@link AnimaLora#tags} turns those into the LORA= list of the engine and strips them from the text, so a tag costs no tokens.
 *
 * <p>{@link #ensure} runs right before a picture (Main.generate and MainRun.runOne, so the UI, batches and the local API all go through it):
 * it appends {@code <lora:name:w>} for every library LoRA that is marked automatic or whose trigger words are in the prompt.
 * It never removes a tag on its own: the engine reloads when the LoRA set changes (40-60 s), so a prompt without the trigger words must not
 * unload what the previous picture just folded in.
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

    /** mount this LoRA for every picture, whether or not the prompt mentions its trigger words */
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

    /** the strength written into the tag for this LoRA (1 unless the user set one) */
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

    /** true when the prompt has a tag for this LoRA — a <lora:name:0> counts too: a weight of 0 is how the user switches one off by hand */
    static boolean called(String prompt, String name) {
        if (prompt == null) return false;
        Matcher m = TAG.matcher(prompt);
        String want = name.toLowerCase(Locale.ROOT);
        while (m.find()) if (m.group(1).trim().toLowerCase(Locale.ROOT).equals(want)) return true;
        return false;
    }

    // ---------------------------------------------------------------- the hook
    /**
     * The prompt with the tags of every LoRA that should be mounted but is not called yet. Only additions: the text the user typed is never
     * changed, and a tag that is already there keeps its own strength.
     */
    static String ensure(File home, String prompt) {
        String p = prompt == null ? "" : prompt;
        List<File> lib = library(home);
        StringBuilder add = new StringBuilder();
        for (File f : lib) {
            String n = name(f);
            if (called(p, n)) continue;
            boolean on = auto(f) || triggered(f, p);
            if (!on) continue;
            if (add.length() > 0) add.append(", ");
            add.append("<lora:").append(n).append(':').append(String.format(Locale.US, "%.2f", weight(f))).append('>');
        }
        if (add.length() == 0) return prompt == null ? "" : prompt;
        String head = p.trim();
        if (head.isEmpty()) return add.toString();
        char last = head.charAt(head.length() - 1);
        return head + (last == ',' ? " " : ", ") + add;
    }

    /** what {@link #ensure} would add right now, as "name 1.00 · name2 0.80"; empty when nothing would be added */
    static String summary(File home, String prompt) {
        String p = prompt == null ? "" : prompt;
        StringBuilder sb = new StringBuilder();
        for (File f : library(home)) {
            String n = name(f);
            if (called(p, n)) continue;
            if (!(auto(f) || triggered(f, p))) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(n).append(' ').append(String.format(Locale.US, "%.2f", weight(f)));
        }
        return sb.toString();
    }

    /** the LoRAs the prompt calls right now, as "name 1.00 · …" (a tag with 0 or an unknown name is left out) */
    static String mounted(File home, String prompt) {
        StringBuilder sb = new StringBuilder();
        if (prompt == null) return "";
        Matcher m = TAG.matcher(prompt);
        while (m.find()) {
            String n = m.group(1).trim();
            if (AnimaLora.find(home, n) == null) continue;
            String w = m.group(2) == null || m.group(2).trim().isEmpty() ? "1.0" : m.group(2).trim();
            try {
                if (Float.parseFloat(w) == 0f) continue;
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (sb.length() > 0) sb.append(" · ");
            sb.append(n).append(' ').append(w);
        }
        return sb.toString();
    }

    /** the tag of one LoRA, with its stored strength */
    static String tagOf(File lora) {
        return "<lora:" + name(lora) + ":" + String.format(Locale.US, "%.2f", weight(lora)) + ">";
    }
}
