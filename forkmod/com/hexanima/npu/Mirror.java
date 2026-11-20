package com.hexanima.npu;

/** mod: the download mirror. The catalog pins absolute huggingface.co URLs for the packs, the V73 common files are pinned to hf-mirror.com,
 *  and a few extras (ADetailer, Mage, Qwen runtime) have their own hosts; Models.open() asks this class to swap the host for the origin of
 *  the chosen "download source", so any Hugging Face-compatible mirror works end to end. An empty origin, or the official origin itself,
 *  leaves the URL untouched: the direct huggingface.co mode still works exactly as before. */
final class Mirror {
    static volatile String origin = "";

    /** base is the full ".../resolve/main/" URL of the chosen source; only scheme + host (+port) are kept */
    static void set(String base) {
        try {
            java.net.URI u = java.net.URI.create(base.trim());
            if (u.getHost() == null) return;
            origin = u.getScheme() + "://" + u.getAuthority();
        } catch (Exception ignored) { }
    }

    static String fix(String url) {
        if (url == null || origin.isEmpty() || !url.startsWith("http")) return url;
        try {
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost();
            if (h == null) return url;
            h = h.toLowerCase(java.util.Locale.ROOT);
            if (!h.equals("huggingface.co") && !h.equals("hf-mirror.com") && !h.equals("hf-mirror.net")) return url;
            String q = u.getRawQuery();
            return origin + u.getRawPath() + (q == null ? "" : "?" + q);
        } catch (Exception e) { return url; }
    }
}
