package io.github.shivamsingh262.photoprints;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** A small, cached set from Pixabay; user photos remain in their own folder and are never overwritten. */
final class OnlinePhotos {
    private static final String TAG = "PhotoPrintsOnline";
    private static final String PREFS = "online_photos_v4";
    private static final String API_KEY_PREFS = "pixabay_settings";
    private static final long REFRESH_MS = 24L * 60L * 60L * 1000L;
    private static final long RETRY_MS = 6L * 60L * 60L * 1000L;
    private static final int PAGE_SIZE = 20;
    private static final int KEEP_IMAGES = 8;
    private static final int MAX_JSON_BYTES = 1_500_000;
    private static final int MAX_IMAGE_BYTES = 2_500_000;
    private static final String USER_AGENT = "CleanYourTV-PhotoPrints/1.4 (https://github.com/Gathin23/cleanyourtv)";
    private static final String[] SEARCHES = {"landscape", "mountain landscape", "ocean landscape",
            "forest landscape", "city landscape", "lake landscape", "nature scenery", "night sky landscape"};

    private OnlinePhotos() {}

    static File folder(Context context) {
        return new File(context.getCacheDir(), "pixabay-photos");
    }

    static String apiKey(Context context) {
        return context.getSharedPreferences(API_KEY_PREFS, Context.MODE_PRIVATE).getString("api_key", "");
    }

    static void setApiKey(Context context, String key) {
        context.getSharedPreferences(API_KEY_PREFS, Context.MODE_PRIVATE).edit()
                .putString("api_key", key == null ? "" : key.trim()).apply();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("last_attempt").remove("last_success").apply();
    }

    /**
     * Fetch at most one page per 12 hours, sequentially, and cache up to eight landscape thumbnails.
     * A failed request keeps the last good cache and is retried no more than once per three hours.
     */
    static boolean refreshIfDue(Context context) {
        String apiKey = apiKey(context);
        if (apiKey.isEmpty()) {
            Log.i(TAG, "Pixabay API key is not set");
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSuccess = prefs.getLong("last_success", 0);
        long lastAttempt = prefs.getLong("last_attempt", 0);
        if (!hasImages(folder(context))) lastSuccess = 0;
        if (now - lastSuccess < REFRESH_MS || now - lastAttempt < RETRY_MS) return false;
        if (!prefs.edit().putLong("last_attempt", now).commit()) return false;

        File parent = context.getCacheDir();
        File staging = new File(parent, "online-photos-next");
        File previous = new File(parent, "online-photos-old");
        deleteTree(staging);
        if (!staging.mkdirs()) return false;

        boolean moved = false;
        try {
            String query = SEARCHES[new Random(now / REFRESH_MS).nextInt(SEARCHES.length)];
            JSONObject response = search(query, apiKey);
            JSONArray results = response.optJSONArray("hits");
            if (results == null || results.length() == 0) {
                Log.w(TAG, "Pixabay returned no landscape images");
                return false;
            }

            List<JSONObject> candidates = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item != null) candidates.add(item);
            }
            Collections.shuffle(candidates, new Random(now));
            int saved = 0;
            for (JSONObject item : candidates) {
                if (Thread.currentThread().isInterrupted() || saved >= KEEP_IMAGES) break;
                if (!saveImage(item, staging, saved)) continue;
                saved++;
            }
            if (saved == 0) {
                Log.w(TAG, "Pixabay results had no usable image thumbnails");
                return false;
            }

            deleteTree(previous);
            File current = folder(context);
            if (current.exists()) {
                if (!current.renameTo(previous)) return false;
                moved = true;
            }
            if (!staging.renameTo(current)) {
                if (moved) previous.renameTo(current);
                return false;
            }
            deleteTree(previous);
            Log.i(TAG, "Cached " + saved + " Pixabay images");
            return prefs.edit().putLong("last_success", now).commit();
        } catch (Exception ignored) {
            Log.w(TAG, "Pixabay refresh failed: " + ignored.getClass().getSimpleName());
            if (moved && !folder(context).exists()) previous.renameTo(folder(context));
            return false;
        } finally {
            deleteTree(staging);
        }
    }

    private static JSONObject search(String query, String apiKey) throws IOException {
        Uri uri = Uri.parse("https://pixabay.com/api/").buildUpon()
                .appendQueryParameter("key", apiKey)
                .appendQueryParameter("q", query)
                .appendQueryParameter("image_type", "photo")
                .appendQueryParameter("orientation", "horizontal")
                .appendQueryParameter("min_width", "1280")
                .appendQueryParameter("safesearch", "true")
                .appendQueryParameter("order", "latest")
                .appendQueryParameter("per_page", Integer.toString(PAGE_SIZE))
                .appendQueryParameter("page", "1")
                .build();
        byte[] bytes = get(uri.toString(), MAX_JSON_BYTES, "application/json");
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("Openverse returned invalid JSON", e);
        }
    }

    private static boolean saveImage(JSONObject item, File directory, int sequence) {
        // Pixabay requires downloading assets for use; never keep or reuse its expiring URLs as the display source.
        String imageUrl = item.optString("largeImageURL", "");
        if (imageUrl.isEmpty()) imageUrl = item.optString("webformatURL", "");
        if (!imageUrl.startsWith("https://")) return false;
        String id = item.optString("id", "").replaceAll("[^A-Za-z0-9_-]", "");
        if (id.isEmpty()) id = Integer.toString(sequence);
        File image = new File(directory, String.format(java.util.Locale.ROOT, "pixabay-%02d-%s.jpg", sequence, id));
        try {
            byte[] bytes = get(imageUrl, MAX_IMAGE_BYTES, "image/");
            try (FileOutputStream out = new FileOutputStream(image)) {
                out.write(bytes);
                out.getFD().sync();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), bounds);
            if (bounds.outWidth < 200 || bounds.outHeight < 120 || bounds.outWidth > 12000 || bounds.outHeight > 12000) {
                image.delete();
                return false;
            }

            String creator = plain(item.optString("user", "Pixabay contributor"));
            String credit = "Pixabay · " + creator;
            write(new File(directory, image.getName() + ".credit"), credit);

            JSONObject source = new JSONObject();
            source.put("creator", item.optString("user", ""));
            source.put("source_url", item.optString("pageURL", ""));
            source.put("provider", "Pixabay");
            write(new File(directory, image.getName() + ".json"), source.toString());
            return true;
        } catch (Exception e) {
            try {
                Log.w(TAG, "Thumbnail failed for " + new URL(imageUrl).getHost() + ": "
                        + e.getClass().getSimpleName());
            } catch (Exception ignored) {
                Log.w(TAG, "Thumbnail could not be saved: " + e.getClass().getSimpleName());
            }
            image.delete();
            new File(directory, image.getName() + ".credit").delete();
            new File(directory, image.getName() + ".json").delete();
            return false;
        }
    }

    private static byte[] get(String address, int maxBytes, String expectedType) throws IOException {
        HttpURLConnection connection = null;
        URL url = new URL(address);
        try {
            Log.i(TAG, "Requesting " + url.getHost());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(expectedType.equals("image/") ? 20_000 : 45_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", expectedType.equals("image/") ? "image/*" : expectedType);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " from " + url.getHost());
                throw new IOException("HTTP " + code);
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new IOException("HTTPS required");
            String type = connection.getContentType();
            if (expectedType.equals("image/") && (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))) {
                throw new IOException("Not an image response");
            }
            int length = connection.getContentLength();
            if (length > maxBytes) throw new IOException("Response is too large");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, length))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Download interrupted");
                    if (output.size() + count > maxBytes) throw new IOException("Response is too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            Log.w(TAG, "Request failed for " + url.getHost() + ": " + e.getClass().getSimpleName());
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void write(File file, String value) throws IOException {
        try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String plain(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return text.length() > 120 ? text.substring(0, 117) + "…" : text;
    }

    private static String join(String title, String creator, String license) {
        StringBuilder value = new StringBuilder();
        if (!title.isEmpty()) value.append(title);
        if (!creator.isEmpty() && !creator.equalsIgnoreCase(title)) append(value, creator);
        if (!license.isEmpty()) append(value, license);
        return value.toString();
    }

    private static void append(StringBuilder value, String next) {
        if (value.length() > 0) value.append(" · ");
        value.append(next);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists()) file.delete();
    }

    private static boolean hasImages(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) if (file.isFile() && Photos.isImage(file.getName())) return true;
        return false;
    }
}
