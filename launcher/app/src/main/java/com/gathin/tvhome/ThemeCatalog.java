package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Small, offline theme catalog installed by tools/theme.ps1. */
final class ThemeCatalog {
    private static final String[] IDS = {"default", "aurora", "glass", "ruska", "scenes",
            "six-columns", "sweden", "dussehra", "kids", "cinematic-sunset"};
    private ThemeCatalog() {}

    static List<String> installed(Context context) {
        List<String> result = new ArrayList<>();
        File root = Config.dir(context);
        if (root == null) return result;
        for (String id : IDS) {
            File config = new File(new File(new File(root, "theme-packs"), id), Config.FILE_NAME);
            if (config.isFile() && config.length() > 0) result.add(id);
        }
        return result;
    }

    static String label(String id) {
        String[] words = id.split("-");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    static int currentIndex(Context context, List<String> themes) {
        File root = Config.dir(context);
        File active = root == null ? null : new File(root, Config.FILE_NAME);
        if (active == null || !active.isFile()) return -1;
        for (int i = 0; i < themes.size(); i++) {
            File candidate = new File(new File(new File(root, "theme-packs"), themes.get(i)), Config.FILE_NAME);
            if (sameContents(active, candidate)) return i;
        }
        return -1;
    }

    private static boolean sameContents(File left, File right) {
        if (!right.isFile() || left.length() != right.length()) return false;
        try (InputStream a = new FileInputStream(left); InputStream b = new FileInputStream(right)) {
            byte[] x = new byte[4096];
            byte[] y = new byte[4096];
            while (true) {
                int nx = a.read(x), ny = b.read(y);
                if (nx != ny) return false;
                if (nx == -1) return true;
                for (int i = 0; i < nx; i++) if (x[i] != y[i]) return false;
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    static void apply(Context context, String id) throws IOException {
        if (!installed(context).contains(id)) throw new IOException("Theme pack is not installed");
        File root = Config.dir(context);
        File pack = new File(new File(root, "theme-packs"), id);
        copy(new File(pack, Config.FILE_NAME), new File(root, Config.FILE_NAME));
        copyOptional(new File(pack, Config.WALLPAPER_NAME), new File(root, Config.WALLPAPER_NAME));

        File activeWallpapers = new File(root, "wallpapers");
        if (!activeWallpapers.exists() && !activeWallpapers.mkdirs()) throw new IOException("Cannot create wallpapers folder");
        File[] existing = activeWallpapers.listFiles();
        if (existing != null) for (File file : existing) {
            if (ThemeFiles.collectionImage(file.getName()) || WallpaperCaptions.FILE_NAME.equals(file.getName())) {
                if (!file.delete()) throw new IOException("Cannot replace wallpaper collection");
            }
        }
        File packedWallpapers = new File(pack, "wallpapers");
        File[] images = packedWallpapers.listFiles();
        if (images != null) for (File file : images) {
            if (ThemeFiles.collectionImage(file.getName()) || WallpaperCaptions.FILE_NAME.equals(file.getName())) {
                copy(file, new File(activeWallpapers, file.getName()));
            }
        }

        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                .remove(Config.DOCK_OPACITY_KEY)
                .putString("active_theme", id)
                .putLong(Config.SETTINGS_REVISION_KEY,
                        choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .commit();
    }

    private static void copyOptional(File source, File target) throws IOException {
        if (source.isFile()) copy(source, target);
        else if (target.exists() && !target.delete()) throw new IOException("Cannot clear old wallpaper");
    }

    private static void copy(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create theme folder");
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        }
    }
}
