package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Optional, on-device app allowlist for the children's Home; disabled until a parent sets it up. */
final class KidsMode {
    private static final String PREFS = "kids_mode";

    private KidsMode() {}

    static boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    static Set<String> allowedPackages(Context context) {
        Set<String> saved = prefs(context).getStringSet("allowed_apps", Collections.emptySet());
        return new HashSet<>(saved);
    }

    static boolean allows(Context context, String packageName) {
        return !enabled(context) || allowedPackages(context).contains(packageName);
    }

    static boolean allowsRemoteAction(Context context, String action) {
        if (!enabled(context)) return true;
        return action != null && !action.startsWith("input:") && allowedPackages(context).contains(action);
    }

    static File wallpaperFile(Context context) {
        File root = Config.dir(context);
        return root == null ? null : new File(new File(new File(root, "theme-packs"), "kids"), Config.WALLPAPER_NAME);
    }

    static boolean enable(Context context, Set<String> packages) {
        if (packages == null || packages.isEmpty()) return false;
        return prefs(context).edit()
                .putStringSet("allowed_apps", new HashSet<>(packages))
                .remove("pin_salt")
                .remove("pin_hash")
                .putBoolean("enabled", true)
                .commit();
    }

    static boolean enableSaved(Context context) {
        if (allowedPackages(context).isEmpty()) return false;
        return prefs(context).edit().remove("pin_salt").remove("pin_hash")
                .putBoolean("enabled", true).commit();
    }

    static void disable(Context context) {
        prefs(context).edit().remove("pin_salt").remove("pin_hash").putBoolean("enabled", false).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
