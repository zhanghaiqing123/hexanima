package com.hexanima.npu;

import android.content.Context;

/** The strings this mod adds are written in English and Chinese: Chinese when the phone is Chinese, or when the
 *  language was switched from the model-backup dialog ("MODEL BACKUP" card). Everything else follows the app's own L.s(). */
final class Tr {
    static boolean zh(Context c) {
        try {
            android.content.SharedPreferences p = c.getSharedPreferences("anima", 0);
            if (p.contains("ui_zh")) return p.getBoolean("ui_zh", true);
        } catch (Throwable ignored) { }
        try { return java.util.Locale.getDefault().getLanguage().startsWith("zh"); } catch (Throwable t) { return false; }
    }
    static void set(Context c, boolean v) { c.getSharedPreferences("anima", 0).edit().putBoolean("ui_zh", v).apply(); }
    static String s(Context c, String en, String zh) { return zh(c) ? zh : en; }
    static String f(Context c, String en, String zh, Object... a) { return String.format(zh(c) ? zh : en, a); }
}
