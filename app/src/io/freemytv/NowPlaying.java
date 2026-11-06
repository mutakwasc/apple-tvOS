package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    /** Where `w` is now, in ms: its last reported position moved on by the time since, at its speed. */
    static long position(Watch w) {
        long p = w.position;
        if (w.speed > 0 && w.positionAt > 0) p += (long) ((SystemClock.elapsedRealtime() - w.positionAt) * w.speed);
        return Math.max(0, w.duration > 0 ? Math.min(p, w.duration) : p);
    }

    /** "02:30/04:10" into `sb`. */
    static void appendTimes(StringBuilder sb, long elapsed, long total) {
        appendTime(sb, elapsed);
        sb.append('/');
        appendTime(sb, total);
    }

    private static void appendTime(StringBuilder sb, long ms) {
        long s = ms / 1000, m = s / 60;
        s %= 60;
        if (m < 10) sb.append('0');
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        sb.append(s);
    }

    /**
     * The bars following the sound (AudioLevels: bass, mids, treble) when it can be read; else bouncing on their own,
     * never still.
     */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, AudioLevels audio, RectF r) {
        if (audio == null || !audio.live()) {
            drawBars(c, x, base, cap, p, true, r);
            return;
        }
        audio.step();
        for (int i = 0; i < 3; i++) {
            float level = 0.2f + 0.8f * audio.level[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;
        /** The playing media's art (album art or video still), or null. */
        volatile Bitmap art;
        /** The artist or subtitle, or null. */
        volatile String artist;
        /** The playing app's package name, or null. */
        volatile String pkg;
        /** Whether the session looks like music (has an album title or artist, no video size). */
        volatile boolean music;
        /** The album name, or null. */
        volatile String album;
        /** The playing session's controller: for transport controls. Read on the main thread only. */
        volatile MediaController controller;
        /** Duration in ms, or 0. */
        volatile long duration;
        /** Last reported position in ms. */
        volatile long position;
        /** Playback speed (1 = normal). */
        volatile float speed;
        /** SystemClock.elapsedRealtime() when position was last reported. */
        volatile long positionAt;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        /** Every session the apps keep, paused ones too, most recent first (a copy: read it off the main thread). */
        List<MediaController> sessions() {
            return new ArrayList<>(controllers);
        }

        /** The session of `pkg`, playing or not, or null. */
        MediaController session(String pkg) {
            for (MediaController mc : controllers) if (mc.getPackageName().equals(pkg)) return mc;
            return null;
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null, nowTitle = null;
            Bitmap nowArt = null;
            String nowArtist = null, nowPkg = null, nowAlbum = null;
            boolean nowMusic = false;
            MediaController nowController = null;
            long nowDuration = 0, nowPosition = 0, nowPositionAt = 0;
            float nowSpeed = 1;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    MediaMetadata m = mc.getMetadata();
                    CharSequence t = m == null ? null : m.getDescription().getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();
                    nowPkg = mc.getPackageName();
                    nowController = mc;
                    if (m != null) {
                        // No DISPLAY_ICON: Spotify puts its app icon there.
                        nowArt = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (nowArt == null) nowArt = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                        // A video's still in the display icon (YouTube's, a player's): the bitmaps only, no link here.
                        if (nowArt == null && !Recently.isMusic(m)) nowArt = Recently.sessionArt(null, m, false);
                        // An update for the same song without the art keeps the art it had.
                        if (nowArt == null && nowTitle != null && nowTitle.equals(title)) nowArt = art;
                        CharSequence a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
                        if (TextUtils.isEmpty(a)) a = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                        nowArtist = TextUtils.isEmpty(a) ? null : a.toString().trim();
                        CharSequence al = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
                        nowAlbum = TextUtils.isEmpty(al) ? null : al.toString().trim();
                        nowMusic = Recently.isMusic(m);
                    }
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null) {
                        nowDuration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                        nowPosition = ps.getPosition();
                        nowSpeed = ps.getPlaybackSpeed();
                        nowPositionAt = ps.getLastPositionUpdateTime();
                    }
                    break;
                }
            }
            // Play, pause and seeks keep the title: the position is kept without a redraw (the progress bar reads it).
            duration = nowDuration;
            position = nowPosition;
            speed = nowSpeed;
            positionAt = nowPositionAt;
            controller = nowController;
            // Apps send the title first with a placeholder (their icon), then the real art: a new picture alone is a change.
            if (Objects.equals(now, text) && sameArt(nowArt, art)) return;
            title = nowTitle;
            text = now;
            art = nowArt;
            artist = nowArtist;
            pkg = nowPkg;
            album = nowAlbum;
            music = nowMusic;
            changed.run();
        }

        /** Some apps hand a new Bitmap object for the same picture on every update. */
        private static boolean sameArt(Bitmap a, Bitmap b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            return a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight() && a.sameAs(b);
        }

        /** "Title · Artist": the media's own title and subtitle; null when there's none. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
