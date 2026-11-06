package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web wallpapers from Bing, Windows Spotlight and Wikimedia Commons. Each source fetches a few landscape photographs
 * per run, crops them to 16:9 through Photos.importOnline, and stores them in Photos.onlineDir so they show as the
 * home screen's background and on the screensaver when My Photos is on and the source is "web". Personal photos and
 * Immich's are never touched.
 */
final class Gallery {
    private Gallery() {}

    private static final int KEEP = 48;
    private static final int PER_RUN = 6;
    private static final int SEEN_MAX = 9000;
    private static final long EVERY_MS = 6 * 3_600_000L;
    private static final long RETRY_MS = 30 * 60_000L;
    private static final long GAP_MS = 1_500;
    private static final int W = 1920, H = 1080;
    private static final String UA = "FreeMyTV/1.0";
    private static final Random RND = new Random();

    private static final String SPOTLIGHT =
            "https://fd.api.iris.microsoft.com/v4/api/selection?placement=88000820&bcnt=4&fmt=json&country=US&locale=en-US";
    private static final String[] BING_MARKETS = {"en-US", "en-GB", "de-DE", "fr-FR", "ja-JP", "en-AU", "pt-BR", "en-CA", "en-IN", "es-ES"};

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    /** Starts a fetch in the background when one is due; `ready` runs on the main thread when new photos arrived. */
    static void syncIfDue(Context c, Runnable ready) {
        // Wanted by the Web wallpaper and by the Ambient screensaver.
        if (!("web".equals(Prefs.wallpaper()) || "ambient".equals(Prefs.saverStyle())) || running) return;
        Context app = c.getApplicationContext();
        File stamp = new File(app.getFilesDir(), "gallery-sync");
        if (Math.abs(System.currentTimeMillis() - stamp.lastModified()) < EVERY_MS) return;
        running = true;
        WORKER.execute(() -> {
            int n = 0;
            try {
                n = sync(app);
                AppLog.w("gallery: " + n + " photos, " + Photos.files(app).size() + " in all");
            } catch (Exception e) {
                AppLog.w("gallery: " + e.getMessage());
            } finally {
                touch(stamp, n > 0 ? System.currentTimeMillis() : System.currentTimeMillis() - EVERY_MS + RETRY_MS);
                running = false;
            }
            if (n > 0 && ready != null) MAIN.post(ready);
        });
    }

    /** The source changed: clear the stamp so the next sync runs soon. */
    static void sourceChanged(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getApplicationContext().getFilesDir(), "gallery-sync").delete();
    }

    private static int sync(Context app) throws Exception {
        Set<String> seen = seen(app);
        int got = 0;
        // Round-robin through the three sources
        SharedPreferences p = prefs(app);
        int turn = p.getInt("gallery_turn", 0);
        p.edit().putInt("gallery_turn", turn + 1).apply();
        String[] sources = {"spotlight", "bing", "wikimedia"};
        String source = sources[Math.abs(turn) % sources.length];
        try {
            switch (source) {
                case "spotlight": got += spotlight(app, seen); break;
                case "bing": got += bing(app, seen); break;
                case "wikimedia": got += wikimedia(app, seen); break;
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("gallery " + source + ": " + e.getMessage());
        }
        saveSeen(app, seen);
        prune(app);
        return got;
    }

    // ---- Sources -------------------------------------------------------------------------------

    private static int spotlight(Context app, Set<String> seen) throws IOException, InterruptedException {
        byte[] body = get(SPOTLIGHT);
        if (body == null) return 0;
        int got = 0;
        try {
            JSONObject rsp = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONObject("batchrsp");
            JSONArray items = rsp == null ? null : rsp.optJSONArray("items");
            for (int i = 0; items != null && i < items.length() && got < PER_RUN; i++) {
                JSONObject ad = new JSONObject(items.getJSONObject(i).optString("item", "{}")).optJSONObject("ad");
                JSONObject img = ad == null ? null : ad.optJSONObject("landscapeImage");
                String url = img == null ? "" : img.optString("asset");
                if (!url.startsWith("https://")) continue;
                String id = url.replaceAll(".*/", "").replaceAll("^[0-9a-f]{8}-[0-9a-f-]+_", "");
                if (id.length() > 80) id = id.substring(0, 80);
                id = id.replaceAll("[^A-Za-z0-9_-]", "_");
                if (seen.contains("s:" + id)) continue;
                seen.add("s:" + id);
                Thread.sleep(GAP_MS);
                if (download(app, url, "spotlight", id)) got++;
            }
        } catch (org.json.JSONException e) {
            throw new IOException("spotlight json: " + e.getMessage());
        }
        return got;
    }

    private static int bing(Context app, Set<String> seen) throws IOException, InterruptedException {
        SharedPreferences p = prefs(app);
        int marketTurn = p.getInt("gallery_market", 0);
        p.edit().putInt("gallery_market", marketTurn + 1).apply();
        String mkt = BING_MARKETS[Math.abs(marketTurn) % BING_MARKETS.length];
        byte[] body = get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + mkt);
        if (body == null) return 0;
        int got = 0;
        try {
            JSONArray imgs = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("images");
            for (int i = 0; imgs != null && i < imgs.length() && got < PER_RUN; i++) {
                JSONObject o = imgs.getJSONObject(i);
                String base = o.optString("urlbase");
                String id = base.replaceAll(".*OHR\\.([^_]+)_.*", "$1");
                if (id.length() > 80) id = id.substring(0, 80);
                id = id.replaceAll("[^A-Za-z0-9_-]", "_");
                if (base.isEmpty() || seen.contains("b:" + id)) continue;
                seen.add("b:" + id);
                Thread.sleep(GAP_MS);
                if (download(app, "https://www.bing.com" + base + "_UHD.jpg&w=" + W + "&h=" + H + "&rs=1&c=4", "bing", id)) got++;
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bing json: " + e.getMessage());
        }
        return got;
    }

    private static int wikimedia(Context app, Set<String> seen) throws IOException, InterruptedException {
        String[] cats = {"landscapes", "mountains", "lakes", "forests", "coasts"};
        long from = 1_199_145_600_000L + (long) (RND.nextDouble() * (System.currentTimeMillis() - 1_199_145_600_000L));
        String start = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).format(new java.util.Date(from));
        byte[] body = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo"
                + "&iiprop=url%7Csize%7Cmime&iiurlwidth=" + W + "&generator=categorymembers&gcmtype=file&gcmlimit=40"
                + "&gcmsort=timestamp&gcmstart=" + start + "&gcmtitle=Category:Featured_pictures_of_" + cats[RND.nextInt(cats.length)]);
        if (body == null) return 0;
        int got = 0;
        try {
            JSONObject q = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONObject("query");
            JSONObject pages = q == null ? null : q.optJSONObject("pages");
            if (pages == null) return 0;
            java.util.Iterator<String> keys = pages.keys();
            while (keys.hasNext() && got < PER_RUN) {
                JSONObject page = pages.optJSONObject(keys.next());
                JSONArray infos = page == null ? null : page.optJSONArray("imageinfo");
                JSONObject info = infos == null ? null : infos.optJSONObject(0);
                if (info == null) continue;
                String title = page.optString("title");
                String id = title.replaceAll("[^A-Za-z0-9_-]", "_");
                if (id.length() > 80) id = id.substring(0, 80);
                int ow = info.optInt("width"), oh = info.optInt("height");
                if (seen.contains("c:" + id) || ow < W || ow < oh * 1.3f || !"image/jpeg".equals(info.optString("mime"))) continue;
                seen.add("c:" + id);
                Thread.sleep(GAP_MS);
                String url = info.optString("thumburl", info.optString("url"));
                if (download(app, url, "wikimedia", id)) got++;
            }
        } catch (org.json.JSONException e) {
            throw new IOException("wikimedia json: " + e.getMessage());
        }
        return got;
    }

    // ---- Download and import -------------------------------------------------------------------

    private static boolean download(Context app, String url, String source, String id) {
        File tmp = new File(app.getCacheDir(), "gallery-" + source + ".tmp");
        try {
            byte[] data = get(url);
            if (data == null) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            return Photos.importOnline(app, tmp, source, id);
        } catch (IOException | InterruptedException e) {
            AppLog.w("gallery download " + source + "/" + id + ": " + e.getMessage());
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ---- Network -------------------------------------------------------------------------------

    private static byte[] get(String url) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestProperty("User-Agent", UA);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setInstanceFollowRedirects(true);
            try {
                int code = con.getResponseCode();
                if (code == 429 || code == 503) {
                    if (attempt >= 2) throw new IOException("rate limited");
                    Thread.sleep(20_000L * (attempt + 1));
                    continue;
                }
                if (code == 404 || code == 400) return null;
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        if (Thread.interrupted()) throw new InterruptedIOException();
                        bo.write(buf, 0, n);
                    }
                    return bo.toByteArray();
                }
            } finally {
                con.disconnect();
            }
        }
    }

    // ---- Seen set and pruning ------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gallery", Context.MODE_PRIVATE);
    }

    private static Set<String> seen(Context c) {
        return new HashSet<>(prefs(c).getStringSet("gallery_seen", new HashSet<String>()));
    }

    private static void saveSeen(Context c, Set<String> seen) {
        if (seen.size() > SEEN_MAX) {
            List<String> l = new ArrayList<>(seen);
            Collections.shuffle(l, RND);
            seen = new HashSet<>(l.subList(0, SEEN_MAX));
        }
        prefs(c).edit().putStringSet("gallery_seen", seen).apply();
    }

    private static void prune(Context c) {
        for (String source : new String[] {"bing", "spotlight", "wikimedia"}) {
            List<File> files = Photos.onlineFiles(c, source);
            for (int i = KEEP; i < files.size(); i++) {
                //noinspection ResultOfMethodCallIgnored
                files.get(i).delete();
            }
        }
    }

    private static void touch(File f, long time) {
        try {
            if (!f.exists()) new FileOutputStream(f).close();
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(time);
        } catch (IOException e) {
            // stamp not written: next resume tries again
        }
    }
}
