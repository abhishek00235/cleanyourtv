package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PACK = Pattern.compile("[a-z0-9-]+");

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        if (segments.size() == 3 && "theme-packs".equals(segments.get(0))
                && PACK.matcher(segments.get(1)).matches()
                && (Config.FILE_NAME.equals(segments.get(2)) || "wallpaper.jpg".equals(segments.get(2)))) {
            return join(segments);
        }
        if (segments.size() == 4 && "theme-packs".equals(segments.get(0))
                && PACK.matcher(segments.get(1)).matches() && WALLPAPERS.equals(segments.get(2))
                && (collectionImage(segments.get(3)) || WallpaperCaptions.FILE_NAME.equals(segments.get(3)))) {
            return join(segments);
        }
        return null;
    }

    /** True for "wallpapers" alone: a delete of it clears the collection's images. */
    static boolean isCollection(List<String> segments) {
        return segments != null && (segments.size() == 1 && WALLPAPERS.equals(segments.get(0))
                || segments.size() == 3 && "theme-packs".equals(segments.get(0))
                && PACK.matcher(segments.get(1)).matches() && WALLPAPERS.equals(segments.get(2)));
    }

    private static String join(List<String> segments) {
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) path.append('/');
            path.append(segment);
        }
        return path.toString();
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
