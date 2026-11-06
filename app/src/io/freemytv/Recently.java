package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What played lately, for the Now Playing wallpaper while nothing plays: up to four shows or films, then the last song.
 * Two sources: the TV's Watch Next list (WatchNext; its link resumes exactly where you stopped), and what the media
 * sessions showed on the home screen, kept here in the app's private files: programmes of 12 minutes or more (YouTube's
 * videos of a minute or more), between 2% and 95% watched, the newest 12 for 4 days, each with a JPEG of its art; and
 * the last song. The history never leaves the TV; a picture an app only links to (Netflix's, YouTube's) is fetched from
 * that app's image server, once. File and network work only off the main thread.
 */
final class Recently {
    private Recently() {}

    static final int VIDEOS = 4;
    private static final int MAX = 12;
    private static final long MIN_LENGTH_MS = 12 * 60_000L, KEEP_MS = 4L * 24 * 3600_000L;
    private static final int ART_W = 1920, MAX_PICTURE_BYTES = 4 << 20;
    private static final Object LOCK = new Object();

    static final class Entry {
        String pkg, title, subtitle;
        long position, duration, at;
        boolean music;
        /** File name of the art in the store, or null. */
        String art;
        /** From the TV's Watch Next list (its link and picture), or null. */
        WatchNext.Item watchNext;

        String line() {
            return subtitle == null || subtitle.isEmpty() ? title : title + " · " + subtitle;
        }

        boolean same(Entry o) {
            return pkg.equals(o.pkg) && title.equalsIgnoreCase(o.title);
        }
    }

    /** Music is what names an album: a video's session (YouTube's) gives its channel as the artist, never an album. */
    static boolean isMusic(MediaMetadata m) {
        return m != null && !TextUtils.isEmpty(m.getText(MediaMetadata.METADATA_KEY_ALBUM));
    }

    /** Notes what the sessions hold now (most recent first, as MediaSessionManager lists them). */
    static void record(Context c, List<MediaController> sessions) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            Store s = read(c);
            boolean changed = false, musicSeen = false;
            for (MediaController mc : sessions) {
                MediaMetadata m;
                PlaybackState ps;
                try {
                    m = mc.getMetadata();
                    ps = mc.getPlaybackState();
                } catch (RuntimeException e) {   // the app's session went away meanwhile
                    continue;
                }
                if (m == null || ps == null) continue;
                MediaDescription d = m.getDescription();
                if (d == null || TextUtils.isEmpty(d.getTitle())) continue;
                Entry e = new Entry();
                e.pkg = mc.getPackageName();
                e.title = d.getTitle().toString().trim();
                e.subtitle = TextUtils.isEmpty(d.getSubtitle()) ? null : d.getSubtitle().toString().trim();
                e.duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                e.position = position(ps);
                e.at = now;
                e.music = isMusic(m);
                if (e.music) {
                    if (musicSeen) continue;   // only the newest song
                    musicSeen = true;
                    if (s.music != null && s.music.same(e)) e.art = s.music.art;
                    if (e.art == null || !new File(dir(c), e.art).exists()) e.art = saveArt(c, m, e);
                    if (s.music != null && s.music.art != null && !s.music.art.equals(e.art)) {
                        new File(dir(c), s.music.art).delete();
                    }
                    s.music = e;
                    changed = true;
                    continue;
                }
                Entry old = null;
                for (Entry v : s.videos) if (v.same(e)) old = v;
                // YouTube's videos are mostly shorter than a programme and it writes no Watch Next: a minute will do.
                long minLength = e.pkg.startsWith("com.google.android.youtube") ? 60_000L : MIN_LENGTH_MS;
                boolean keep = e.duration >= minLength && e.position >= e.duration * 0.02
                        && e.position <= e.duration * 0.95;
                if (!keep) {
                    if (old != null && e.duration > 0 && e.position > e.duration * 0.95) {   // watched to the end
                        s.videos.remove(old);
                        changed = true;
                    }
                    continue;
                }
                if (old != null) {
                    e.art = old.art;
                    s.videos.remove(old);
                }
                if (e.art == null || !new File(dir(c), e.art).exists()) e.art = saveArt(c, m, e);
                s.videos.add(e);
                changed = true;
            }
            if (prune(c, s, now) || changed) write(c, s);
        }
    }

    /**
     * Up to four shows or films, newest first (the TV's Watch Next and ours together, each title once), then the last
     * song. `tv` is WatchNext.load's list (empty without its permission).
     */
    static List<Entry> list(Context c, List<WatchNext.Item> tv) {
        Store s;
        synchronized (LOCK) {
            s = read(c);
            if (prune(c, s, System.currentTimeMillis())) write(c, s);
        }
        List<Entry> videos = new ArrayList<>();
        for (WatchNext.Item it : tv) {
            if (it.title == null || it.title.isEmpty()) continue;
            Entry e = new Entry();
            e.pkg = it.pkg;
            e.title = it.title;
            e.subtitle = it.episode;
            e.position = it.position;
            e.duration = it.duration;
            e.at = it.engaged;
            e.watchNext = it;
            videos.add(e);
        }
        for (Entry v : s.videos) {
            boolean dup = false;
            for (Entry e : videos) dup |= e.same(v);
            if (!dup) videos.add(v);
        }
        Collections.sort(videos, (a, b) -> Long.compare(b.at, a.at));
        List<Entry> out = new ArrayList<>(videos.subList(0, Math.min(VIDEOS, videos.size())));
        if (s.music != null) out.add(s.music);
        return out;
    }

    /** The entry's picture for the wallpaper, or null (off the main thread). */
    static Bitmap picture(Context c, Entry e) {
        try {
            if (e.watchNext != null && e.watchNext.pictureUri != null) {
                byte[] data;
                try (InputStream in = c.getContentResolver().openInputStream(e.watchNext.pictureUri)) {
                    if (in == null) return null;
                    data = readAll(in);
                }
                return decode(data);
            }
            // The app's own picture link (Netflix names its image server): downloaded once, then kept for 4 days.
            String link = e.watchNext == null ? null : e.watchNext.picture();
            if (link != null && link.startsWith("https://")) {
                File f = new File(linkDir(c), Integer.toHexString(link.hashCode()) + ".jpg");
                if (f.exists()) return BitmapFactory.decodeFile(f.getPath());
                Bitmap b = decode(download(link));
                if (b == null) return null;
                saveJpeg(b, f);
                return b;
            }
            if (e.art != null) {
                File f = new File(dir(c), e.art);
                if (f.exists()) return BitmapFactory.decodeFile(f.getPath());
            }
        } catch (IOException | RuntimeException ex) {
            AppLog.w("recently: picture: " + ex.getMessage());
        }
        return null;
    }

    private static long position(PlaybackState ps) {
        long p = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getPlaybackSpeed() > 0 && ps.getLastPositionUpdateTime() > 0) {
            p += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    /**
     * The picture a session gives, wherever the app put it (what Google TV's media panel shows): the art, then for a
     * video the display icon (music apps put their app icon there), then a link: the app's own content, or https.
     */
    static Bitmap sessionArt(Context c, MediaMetadata m, boolean music) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null && !music) b = picture(m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON));
        if (b == null && !music && m.getDescription() != null) b = picture(m.getDescription().getIconBitmap());
        if (b != null || c == null) return b;
        List<String> links = new ArrayList<>();
        for (String key : new String[] {MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String link = m.getString(key);
            if (link != null) links.add(link);
        }
        if (m.getDescription() != null && m.getDescription().getIconUri() != null) {
            links.add(m.getDescription().getIconUri().toString());
        }
        for (String link : links) {
            try {
                if (link.startsWith("https://")) return picture(decode(download(link)));
                if (link.startsWith("content://") || link.startsWith("android.resource://")) {
                    try (InputStream in = c.getContentResolver().openInputStream(android.net.Uri.parse(link))) {
                        if (in != null) return picture(decode(readAll(in)));
                    }
                }
            } catch (IOException | RuntimeException ex) {   // a provider that doesn't let us read it, a dead link
                AppLog.w("recently: art link: " + ex.getMessage());
            }
        }
        return null;
    }

    /** A real picture, not an app's icon: wide, or large. */
    private static Bitmap picture(Bitmap b) {
        return b != null && (b.getWidth() > b.getHeight() * 1.1f || b.getWidth() >= 300) ? b : null;
    }

    private static String saveArt(Context c, MediaMetadata m, Entry e) {
        try {
            Bitmap b = sessionArt(c, m, e.music);
            if (b == null && !e.music && e.pkg.startsWith("com.google.android.youtube")) b = youtubeThumbnail(e);
            if (b == null) return null;
            String name = Integer.toHexString((e.pkg + "|" + e.title.toLowerCase(java.util.Locale.ROOT)).hashCode())
                    + (e.music ? "-m" : "") + ".jpg";
            saveJpeg(b, new File(dir(c), name));
            return name;
        } catch (IOException | RuntimeException ex) {
            AppLog.w("recently: art: " + ex.getMessage());
            return null;
        }
    }

    private static void saveJpeg(Bitmap b, File f) throws IOException {
        if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
        if (b.getWidth() > ART_W) b = Bitmap.createScaledBitmap(b, ART_W, Math.round((float) b.getHeight() * ART_W / b.getWidth()), true);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        }
    }

    /**
     * A picture an app named for what you watched: https only, never a redirect (it could lead anywhere), an image
     * of at most 4 MB. Nothing about the TV or the person goes along.
     */
    private static byte[] download(String link) throws IOException {
        java.net.URL url = new java.net.URL(link);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("not https");
        javax.net.ssl.HttpsURLConnection con = (javax.net.ssl.HttpsURLConnection) url.openConnection();
        try {
            con.setInstanceFollowRedirects(false);
            con.setUseCaches(false);
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            String type = con.getContentType();
            if (type == null || !type.startsWith("image/")) throw new IOException("not an image");
            if (con.getContentLengthLong() > MAX_PICTURE_BYTES) throw new IOException("picture too large");
            try (InputStream in = con.getInputStream()) {
                return readAll(in);
            }
        } finally {
            con.disconnect();
        }
    }

    // MARK: - YouTube: its session gives no picture, and it writes no Watch Next. Its search finds the video by its
    // title and channel (the first result: a common title can match another video), and its image server the
    // thumbnail. Tried once per title while the app runs.

    private static final java.util.regex.Pattern VIDEO_ID =
            java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final Set<String> TRIED = new HashSet<>();

    private static Bitmap youtubeThumbnail(Entry e) {
        String query = e.subtitle == null ? e.title : e.title + " " + e.subtitle;
        synchronized (TRIED) {
            if (!TRIED.add(query)) return null;
        }
        try {
            String html = fetchPage("https://www.youtube.com/results?search_query="
                    + java.net.URLEncoder.encode(query, "UTF-8"));
            java.util.regex.Matcher id = VIDEO_ID.matcher(html);
            if (!id.find()) return null;
            // The HD one exists only for HD uploads; the standard one always.
            for (String size : new String[] {"maxresdefault", "hqdefault"}) {
                try {
                    Bitmap b = decode(download("https://i.ytimg.com/vi/" + id.group(1) + "/" + size + ".jpg"));
                    if (b != null) return b;
                } catch (IOException missing) {
                    // the next size
                }
            }
        } catch (IOException | RuntimeException ex) {
            AppLog.w("recently: youtube: " + ex.getMessage());
        }
        return null;
    }

    /** YouTube's search page, as a browser gets it: https, no redirects (a consent page leads elsewhere), 3 MB at most. */
    private static String fetchPage(String link) throws IOException {
        javax.net.ssl.HttpsURLConnection con = (javax.net.ssl.HttpsURLConnection) new java.net.URL(link).openConnection();
        try {
            con.setInstanceFollowRedirects(false);
            con.setUseCaches(false);
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)");
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            String type = con.getContentType();
            if (type == null || !type.startsWith("text/html")) throw new IOException("not a page");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 3 << 20) break;   // the first results come early in the page
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    /** Downloaded Watch Next pictures, apart from the store's own JPEGs; kept 4 days. */
    private static File linkDir(Context c) {
        File d = new File(dir(c), "links");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // MARK: - The store: watched.json and its JPEGs, in the app's private files

    private static final class Store {
        final List<Entry> videos = new ArrayList<>();
        Entry music;
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watched");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Drops what's older than 4 days and past the newest 12, and JPEGs nothing names. True if anything went. */
    private static boolean prune(Context c, Store s, long now) {
        boolean changed = false;
        for (int i = s.videos.size() - 1; i >= 0; i--) {
            if (now - s.videos.get(i).at > KEEP_MS) {
                s.videos.remove(i);
                changed = true;
            }
        }
        Collections.sort(s.videos, (a, b) -> Long.compare(b.at, a.at));
        while (s.videos.size() > MAX) {
            s.videos.remove(s.videos.size() - 1);
            changed = true;
        }
        if (s.music != null && now - s.music.at > KEEP_MS) {
            s.music = null;
            changed = true;
        }
        Set<String> named = new HashSet<>();
        for (Entry v : s.videos) if (v.art != null) named.add(v.art);
        if (s.music != null && s.music.art != null) named.add(s.music.art);
        File[] files = dir(c).listFiles();
        if (files != null) {
            for (File f : files) if (f.getName().endsWith(".jpg") && !named.contains(f.getName())) f.delete();
        }
        File[] links = linkDir(c).listFiles();
        if (links != null) {
            for (File f : links) if (now - f.lastModified() > KEEP_MS) f.delete();
        }
        return changed;
    }

    private static Store read(Context c) {
        Store s = new Store();
        File f = new File(dir(c), "watched.json");
        if (!f.exists()) return s;
        try (InputStream in = new java.io.FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            JSONArray v = o.optJSONArray("videos");
            for (int i = 0; v != null && i < v.length(); i++) {
                Entry e = entry(v.getJSONObject(i));
                if (e != null) s.videos.add(e);
            }
            JSONObject m = o.optJSONObject("music");
            if (m != null) {
                s.music = entry(m);
                if (s.music != null) s.music.music = true;
            }
        } catch (IOException | JSONException e) {
            AppLog.w("recently: read: " + e.getMessage());
        }
        return s;
    }

    private static Entry entry(JSONObject o) {
        String pkg = o.optString("pkg", ""), title = o.optString("title", "");
        if (pkg.isEmpty() || title.isEmpty()) return null;
        Entry e = new Entry();
        e.pkg = pkg;
        e.title = title;
        e.subtitle = o.isNull("subtitle") ? null : o.optString("subtitle", null);
        e.position = o.optLong("position");
        e.duration = o.optLong("duration");
        e.at = o.optLong("at");
        e.art = o.isNull("art") ? null : o.optString("art", null);
        return e;
    }

    private static JSONObject json(Entry e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("pkg", e.pkg);
        o.put("title", e.title);
        if (e.subtitle != null) o.put("subtitle", e.subtitle);
        o.put("position", e.position);
        o.put("duration", e.duration);
        o.put("at", e.at);
        if (e.art != null) o.put("art", e.art);
        return o;
    }

    private static void write(Context c, Store s) {
        try {
            JSONObject o = new JSONObject();
            JSONArray v = new JSONArray();
            for (Entry e : s.videos) v.put(json(e));
            o.put("videos", v);
            if (s.music != null) o.put("music", json(s.music));
            File tmp = new File(dir(c), "watched.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir(c), "watched.json"))) AppLog.w("recently: couldn't save");
        } catch (IOException | JSONException e) {
            AppLog.w("recently: write: " + e.getMessage());
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_PICTURE_BYTES) throw new IOException("picture too large");
        }
        return out.toByteArray();
    }

    /** Decoded at most about twice the wallpaper's width. */
    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= Backdrop.W) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
