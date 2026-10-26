package com.martinb.downloados;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Small persistent settings: GitHub owner, optional token, cached app list, install history. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("downloados", Context.MODE_PRIVATE);
    }

    static String owner(Context c) { return sp(c).getString("owner", "fisbal"); }
    static String token(Context c) { return sp(c).getString("token", ""); }

    static void setOwnerAndToken(Context c, String owner, String token) {
        sp(c).edit().putString("owner", owner).putString("token", token).remove("cache").apply();
    }

    /** The user's own Claude API key for the copilot; stays on this phone. */
    static String claudeKey(Context c) { return sp(c).getString("claudeKey", ""); }
    static void setClaudeKey(Context c, String key) { sp(c).edit().putString("claudeKey", key).apply(); }

    static String cache(Context c) { return sp(c).getString("cache", ""); }
    static long cacheTime(Context c) { return sp(c).getLong("cacheTime", 0); }

    static void setCache(Context c, String json) {
        sp(c).edit().putString("cache", json).putLong("cacheTime", System.currentTimeMillis()).apply();
    }

    /** Package name learnt for a repo (after its first download), or a best guess. */
    static String packageFor(Context c, String repoKey, String repoName) {
        String known = sp(c).getString("pkg_" + repoKey, null);
        if (known != null) return known;
        return "com.martinb." + repoName.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    static void rememberPackage(Context c, String repoKey, String pkg) {
        if (repoKey == null || repoKey.isEmpty() || pkg == null) return;
        sp(c).edit().putString("pkg_" + repoKey, pkg).apply();
    }

    static String installedTag(Context c, String repoKey) {
        return sp(c).getString("tag_" + repoKey, "");
    }

    static long installedCode(Context c, String repoKey) {
        return sp(c).getLong("code_" + repoKey, -1);
    }

    static void recordInstall(Context c, int status, String repoKey, String tag, String pkg) {
        if (status != PackageInstaller.STATUS_SUCCESS || repoKey == null || repoKey.isEmpty()) return;
        rememberPackage(c, repoKey, pkg);
        long code = -1;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            code = versionCode(pi);
        } catch (Exception ignored) { }
        sp(c).edit().putString("tag_" + repoKey, tag == null ? "" : tag).putLong("code_" + repoKey, code).apply();
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }
}
