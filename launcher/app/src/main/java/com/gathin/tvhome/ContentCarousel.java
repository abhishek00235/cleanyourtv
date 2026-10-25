package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-published Watch Next and preview programs shown over the wallpaper while a tile has focus. */
final class ContentCarousel implements AutoCloseable {
    private static final int MAX_ITEMS = 12;
    private static final String[] WATCH_NEXT_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE
    };
    private static final String[] PREVIEW_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE
    };

    private final Context context;
    private final View container;
    private final TextView heading;
    private final RecyclerView row;
    private final Adapter adapter;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final ExecutorService artReader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable returnToTile;
    private String requestedPackage;
    private long generation;
    private boolean closed;

    ContentCarousel(Context context, View container, TextView heading, RecyclerView row, Runnable returnToTile) {
        this.context = context.getApplicationContext();
        this.container = container;
        this.heading = heading;
        this.row = row;
        this.returnToTile = returnToTile;
        row.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        row.setItemAnimator(null);
        row.setFocusable(false);
        adapter = new Adapter();
        row.setAdapter(adapter);
        container.setVisibility(View.GONE);
    }

    void showForPackage(String packageName, String appLabel) {
        if (closed || packageName == null || packageName.isEmpty()
                || context.getPackageName().equals(packageName)) {
            hide();
            return;
        }
        if (packageName.equals(requestedPackage)) return;
        requestedPackage = packageName;
        long request = ++generation;
        container.setVisibility(View.GONE);
        reader.execute(() -> {
            List<Program> programs = readPrograms(context.getContentResolver(), packageName);
            main.post(() -> {
                if (closed || request != generation || !packageName.equals(requestedPackage)) return;
                adapter.setPrograms(programs);
                if (programs.isEmpty()) {
                    container.setVisibility(View.GONE);
                    return;
                }
                boolean hasContinue = false;
                for (Program program : programs) {
                    if (!program.suggested
                            && program.watchType != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) {
                        hasContinue = true;
                        break;
                    }
                }
                heading.setText(context.getString(hasContinue ? R.string.carousel_heading_continue
                        : R.string.carousel_heading_suggestions, appLabel));
                container.setVisibility(View.VISIBLE);
            });
        });
    }

    boolean hasPrograms() { return !adapter.programs.isEmpty() && container.getVisibility() == View.VISIBLE; }

    boolean focusFirst() {
        if (!hasPrograms()) return false;
        row.post(() -> {
            RecyclerView.ViewHolder holder = row.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else row.smoothScrollToPosition(0);
        });
        return true;
    }

    boolean containsFocus(View focused) {
        if (focused == null) return false;
        View current = focused;
        while (current != null) {
            if (current == container) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    void hide() {
        ++generation;
        requestedPackage = null;
        if (container != null) container.setVisibility(View.GONE);
    }

    @Override public void close() {
        closed = true;
        hide();
        reader.shutdownNow();
        artReader.shutdownNow();
    }

    private static List<Program> readPrograms(ContentResolver resolver, String packageName) {
        List<Program> items = new ArrayList<>();
        readTable(resolver, TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, packageName, false, items);
        if (items.size() < MAX_ITEMS) {
            readTable(resolver, TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS,
                    TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, packageName, true, items);
        }
        return items;
    }

    private static void readTable(ContentResolver resolver, Uri uri, String[] columns,
                                  String packageColumn, String packageName, boolean suggested,
                                  List<Program> output) {
        String selection = packageColumn + "=? AND " + TvContract.WatchNextPrograms.COLUMN_BROWSABLE + "!=0";
        try (Cursor cursor = resolver.query(uri, columns, selection, new String[]{packageName}, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext() && output.size() < MAX_ITEMS) {
                String title = value(cursor, TvContract.WatchNextPrograms.COLUMN_TITLE);
                if (title.isEmpty()) continue;
                String episode = value(cursor, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                String description = value(cursor, TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION);
                if (description.isEmpty()) description = value(cursor, TvContract.WatchNextPrograms.COLUMN_LONG_DESCRIPTION);
                String art = value(cursor, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                if (art.isEmpty()) art = value(cursor, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                String intent = value(cursor, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                int kind = number(cursor, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1);
                long position = numberLong(cursor, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                long duration = numberLong(cursor, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                output.add(new Program(packageName, title, episode, description, art, intent,
                        kind, position, duration, suggested));
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Some TV builds restrict the shared provider further than the manifest permission.
        }
    }

    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value.trim();
    }

    private static int number(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static long numberLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getLong(index);
    }

    private static final class Program {
        final String packageName, title, episode, description, artUri, intentUri;
        final int watchType;
        final long position, duration;
        final boolean suggested;
        Program(String packageName, String title, String episode, String description, String artUri,
                String intentUri, int watchType, long position, long duration, boolean suggested) {
            this.packageName = packageName;
            this.title = title;
            this.episode = episode;
            this.description = description;
            this.artUri = artUri;
            this.intentUri = intentUri;
            this.watchType = watchType;
            this.position = position;
            this.duration = duration;
            this.suggested = suggested;
        }

        String badge(Context context) {
            if (suggested) return context.getString(R.string.carousel_badge_suggested);
            if (watchType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                return context.getString(R.string.carousel_badge_continue);
            if (watchType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST)
                return context.getString(R.string.carousel_badge_saved);
            return context.getString(R.string.carousel_badge_next);
        }

        String meta(Context context) {
            if (!episode.isEmpty()) return episode;
            if (duration > 0 && position > 0 && position < duration) {
                int percent = (int) Math.min(99, Math.max(1, position * 100 / duration));
                return context.getString(R.string.carousel_resume_progress, percent);
            }
            return description;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        final List<Program> programs = new ArrayList<>();
        void setPrograms(List<Program> next) {
            programs.clear();
            programs.addAll(next);
            notifyDataSetChanged();
            row.scrollToPosition(0);
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            float d = parent.getResources().getDisplayMetrics().density;
            FrameLayout card = new FrameLayout(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(224, d), dp(136, d));
            lp.rightMargin = dp(14, d);
            card.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF374151, 0xFF10151E});
            bg.setCornerRadius(dp(12, d));
            card.setBackground(bg);
            card.setClipToOutline(true);
            card.setFocusable(true);
            card.setClickable(true);
            card.setElevation(dp(3, d));

            ImageView art = new ImageView(parent.getContext());
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(parent.getContext());
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xE6000000, 0x00000000});
            shade.setBackground(gradient);
            card.addView(shade, new FrameLayout.LayoutParams(-1, dp(96, d), Gravity.BOTTOM));

            TextView badge = new TextView(parent.getContext());
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(9);
            badge.setLetterSpacing(0.10f);
            badge.setPadding(dp(9, d), dp(5, d), dp(9, d), dp(5, d));
            badge.setBackground(roundRect(0xB3000000, dp(9, d)));
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            badgeLp.setMargins(dp(10, d), dp(10, d), 0, 0);
            card.addView(badge, badgeLp);

            LinearLayout text = new LinearLayout(parent.getContext());
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(12, d), 0, dp(12, d), dp(10, d));
            text.setGravity(Gravity.BOTTOM | Gravity.LEFT);
            FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(-1, dp(66, d), Gravity.BOTTOM);
            card.addView(text, textLp);
            TextView title = new TextView(parent.getContext());
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            text.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView meta = new TextView(parent.getContext());
            meta.setTextColor(0xD9FFFFFF);
            meta.setTextSize(11);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
            metaLp.topMargin = dp(3, d);
            text.addView(meta, metaLp);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_BACK) {
                    returnToTile.run();
                    return true;
                }
                return false;
            });
            return new Holder(card, art, badge, title, meta);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            Program program = programs.get(position);
            holder.image.setTag(program.artUri);
            holder.image.setImageDrawable(null);
            holder.badge.setText(program.badge(context));
            holder.title.setText(program.title);
            holder.meta.setText(program.meta(context));
            holder.itemView.setContentDescription(program.title + (program.episode.isEmpty() ? "" : ", " + program.episode)
                    + ", " + program.badge(context));
            holder.itemView.setOnClickListener(v -> open(program));
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                v.animate().cancel();
                if (focused) v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start();
                else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            });
            if (program.artUri.startsWith("content:") || program.artUri.startsWith("android.resource:")) {
                String uri = program.artUri;
                artReader.execute(() -> {
                    Bitmap bitmap = loadArt(context.getContentResolver(), uri);
                    main.post(() -> {
                        if (bitmap != null && uri.equals(holder.image.getTag())) holder.image.setImageBitmap(bitmap);
                        else if (bitmap != null) bitmap.recycle();
                    });
                });
            }
        }

        @Override public int getItemCount() { return programs.size(); }

        private void open(Program program) {
            if (program.intentUri.isEmpty()) return;
            try {
                Intent intent = Intent.parseUri(program.intentUri,
                        Intent.URI_INTENT_SCHEME | Intent.URI_ANDROID_APP_SCHEME);
                if (intent.getComponent() != null && !program.packageName.equals(intent.getComponent().getPackageName())) return;
                if (intent.getPackage() != null && !program.packageName.equals(intent.getPackage())) return;
                intent.setPackage(program.packageName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {
                // A stale provider URI must not interrupt focus or Home.
            }
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView badge, title, meta;
        Holder(View item, ImageView image, TextView badge, TextView title, TextView meta) {
            super(item);
            this.image = image;
            this.badge = badge;
            this.title = title;
            this.meta = meta;
        }
    }

    private static Bitmap loadArt(ContentResolver resolver, String uriText) {
        Uri uri = Uri.parse(uriText);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (Exception ignored) {
            return null;
        }
        int sample = 1;
        // The view is 224x136dp. Keep artwork near that size on a 1080p TV to avoid large album bitmaps.
        while (sample < 16 && (bounds.outWidth / (sample * 2) >= 448
                || bounds.outHeight / (sample * 2) >= 272)) sample *= 2;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(float value, float density) { return Math.round(value * density); }
}
