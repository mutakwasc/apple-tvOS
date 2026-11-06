package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

/**
 * Full-screen music player: vinyl cover on the left, song details and controls on the right.
 * Opens from the home screen when the now-playing widget is clicked and music is playing.
 */
public class MusicPlayerActivity extends Activity {

    /** The session to show, handed over by the home screen: its own watch stops once this covers it. */
    static MediaController session;

    private PlayerView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new PlayerView(this);
        // No FLAG_KEEP_SCREEN_ON: the screensaver comes at its time over the player, and leaving it shows the player.
        setContentView(view);
    }

    /** Opens the player for `mc`, pulled down from the top. */
    static void open(Activity from, android.media.session.MediaController mc) {
        session = mc;
        from.startActivity(new android.content.Intent(from, MusicPlayerActivity.class));
        from.overridePendingTransition(R.anim.player_in, R.anim.hold);
    }

    /** Back: pushed up out of the way again. */
    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.player_out);
    }

    @Override protected void onResume() {
        super.onResume();
        askAudio();
        if (view != null) view.resume();
    }

    /**
     * The visualiser reads the TV's sound output, which Android allows only with RECORD_AUDIO (no microphone is
     * opened). Asked once, with Android's own prompt; a "Deny" isn't asked again, and the spikes are animated instead.
     */
    private void askAudio() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = getSharedPreferences("player", MODE_PRIVATE);
        if (p.getBoolean("askedAudio", false)) return;
        p.edit().putBoolean("askedAudio", true).apply();
        requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (view != null && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            view.startAudio();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (view != null) view.pause();
    }

    private static final int FOCUS_REPEAT = 0, FOCUS_PREV = 1, FOCUS_PLAY = 2, FOCUS_NEXT = 3, FOCUS_SHUFFLE = 4;
    private static final int BUTTON_COUNT = 5;

    static final class PlayerView extends View {

        private final MusicPlayerActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = () -> { invalidate(); scheduleTick(); };

        private float k;
        private int w, h;

        // Vinyl
        private float vinylAngle;
        private long lastFrame;
        private final Paint vinylPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Background: the art shrunk to a few pixels and stretched back up (a cheap, smooth blur), darkened.
        private Bitmap bgSmall;
        private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF bgRect = new RectF();

        // Tonearm: swings onto the record while music plays, back off it when paused.
        private float armAngle = ARM_REST;
        // Degrees about the pivot: at -3 the head is on the outer groove (~0.96 r out), at 18 just outside the label
        // (~0.50 r), at -14 off the rim.
        private static final float ARM_REST = -14, ARM_START = -3, ARM_END = 18;
        private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint armPart = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path armPath = new Path();
        private final RectF armRect = new RectF();

        // Text
        private TextPaint titlePaint, artistPaint, albumPaint, labelPaint;
        private String drawnDash;
        private float drawnArtistWidth;
        private Paint timePaint;
        private final Paint barTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barRect = new RectF();

        // Controls
        private int focus = FOCUS_PLAY;
        private final Paint focusRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint playDisc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] ctrlCx = new float[BUTTON_COUNT];

        // Cached ellipsized text (avoid allocations in onDraw)
        private String cachedTitle, cachedArtist, cachedAlbum;
        private String drawnTitle, drawnArtist, drawnAlbum;
        private float cachedTextWidth;

        // State
        private boolean running;
        private boolean shuffleOn, repeatOn;
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { invalidate(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                readMeta(metadata);
                invalidate();
            }
            @Override public void onSessionDestroyed() { activity.finish(); }
        };
        private MediaController boundController;
        private Bitmap art;
        private String title, artist, album;

        // The art on the record's label: a square copy, turned by the shader's matrix (no bitmap made per frame).
        private static final int ART_PX = 512;
        private BitmapShader artShader;
        private final Matrix artMatrix = new Matrix();
        private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint labelRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** The art's most vivid colour, brightened: the visualiser's gradient and the label when there's no art. */
        private int accent = 0xFF4FC3F7;

        // Material Symbols Rounded (res/drawable/sym_*.xml), white; dimmed with alpha, tinted only when a mode is on.
        private final android.graphics.drawable.Drawable icPlay, icPause, icPrev, icNext, icShuffle, icRepeat;

        // Visualiser: spikes along the bottom, bass on the left to treble on the right, from the TV's sound output
        // (Android's Visualizer on the output mix, only while this screen shows and only with RECORD_AUDIO); without
        // it, or where the TV hands the sound past the mixer (silence), an animation instead.
        private static final int VIZ_N = 48;
        private static final float F_LOW = 40, F_HIGH = 16000;
        private final Path vizPath = new Path();
        // Back to front: height, how much white is blended into the art's colour, opacity at the base, edge blur.
        private static final int LAYERS = 3, WAVE_POINTS = 14;
        private static final float[] LAYER_H = {1f, 0.7f, 0.42f}, LAYER_WHITE = {0f, 0.35f, 0.7f}, LAYER_BLUR = {10, 4, 2};
        private static final int[] LAYER_ALPHA = {0x80, 0xB0, 0xF0};
        private final Paint[] layerPaint = {new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG),
                new Paint(Paint.ANTI_ALIAS_FLAG)};
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF lineRect = new RectF();
        private final float[] waveY = new float[WAVE_POINTS + 1];
        private LinearGradient vizShader;
        private float vizLevel;
        private static final float VIZ_REST = 0.08f;
        private android.media.audiofx.Visualizer audio;
        private final float[] bandTarget = new float[VIZ_N], bandLevel = new float[VIZ_N];
        /** When the output last had sound (elapsedRealtime), 0 never: older than 1.5 s, the animation. */
        private long soundAt;
        private final android.media.audiofx.Visualizer.OnDataCaptureListener capture =
                new android.media.audiofx.Visualizer.OnDataCaptureListener() {
            @Override public void onWaveFormDataCapture(android.media.audiofx.Visualizer v, byte[] wave, int rate) {}

            /** FFT bytes: [0] DC, [1] Nyquist, then re/im pairs per bin. Bands spaced evenly in pitch, not in Hz. */
            @Override public void onFftDataCapture(android.media.audiofx.Visualizer v, byte[] fft, int rateMilliHz) {
                int bins = fft.length / 2;
                float binHz = rateMilliHz / 1000f / fft.length;
                if (binHz <= 0) return;
                boolean sound = false;
                for (int i = 0; i < VIZ_N; i++) {
                    float fLo = F_LOW * (float) Math.pow(F_HIGH / F_LOW, (float) i / VIZ_N);
                    float fHi = F_LOW * (float) Math.pow(F_HIGH / F_LOW, (float) (i + 1) / VIZ_N);
                    int kLo = Math.max(1, Math.round(fLo / binHz));
                    int kHi = Math.min(bins - 1, Math.max(kLo, Math.round(fHi / binHz)));
                    float peak = 0;
                    for (int kk = kLo; kk <= kHi; kk++) {
                        float re = fft[2 * kk], im = fft[2 * kk + 1];
                        peak = Math.max(peak, re * re + im * im);
                    }
                    // Power in dB (0 to ~45 for these bytes), the quiet floor cut.
                    float db = 10 * (float) Math.log10(peak + 1);
                    float level = Math.max(0, Math.min(1, (db - 6) / 34));
                    bandTarget[i] = level;
                    sound |= level > 0.05f;
                }
                if (sound) soundAt = SystemClock.elapsedRealtime();
            }
        };

        /** Listens to the sound output, if allowed. Called when the screen shows (and once the permission is given). */
        void startAudio() {
            if (audio != null || activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            try {
                audio = new android.media.audiofx.Visualizer(0);
                int[] range = android.media.audiofx.Visualizer.getCaptureSizeRange();
                audio.setCaptureSize(Math.min(1024, range[1]));
                audio.setDataCaptureListener(capture, Math.min(20000, android.media.audiofx.Visualizer.getMaxCaptureRate()),
                        false, true);
                audio.setEnabled(true);
            } catch (RuntimeException e) {   // a TV without the effect, or the output mix taken
                AppLog.w("player: visualizer: " + e.getMessage());
                stopAudio();
            }
        }

        void stopAudio() {
            if (audio == null) return;
            try {
                audio.setEnabled(false);
            } catch (RuntimeException ignored) {
            }
            audio.release();
            audio = null;
            soundAt = 0;
        }

        // Times, made again only when the second changes.
        private long shownPosSec = -1, shownDur = -1;
        private String posStr = "", durStr = "";

        private void readMeta(MediaMetadata m) {
            if (m == null) return;
            // No DISPLAY_ICON: Spotify puts its app icon there. Updates for the same song can come without the art
            // (or with a new copy of it): the label keeps what it has, so it doesn't flick to the icon and back.
            Bitmap a = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (a == null) a = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            String t = text(m, MediaMetadata.METADATA_KEY_TITLE);
            if (t == null && m.getDescription().getTitle() != null) t = m.getDescription().getTitle().toString().trim();
            boolean sameSong = t != null && t.equals(title);
            if (a == null) {
                if (!sameSong && art != null) {
                    art = null;
                    prepareArt(null);
                }
            } else if (art == null || !(a == art || a.getWidth() == art.getWidth() && a.getHeight() == art.getHeight()
                    && a.sameAs(art))) {
                art = a;
                prepareArt(a);
            }
            title = t;
            artist = text(m, MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
        }

        private static String text(MediaMetadata m, String key) {
            CharSequence s = m.getText(key);
            return TextUtils.isEmpty(s) ? null : s.toString().trim();
        }

        private void prepareArt(Bitmap a) {
            vizShader = null;   // the gradient follows the new colour
            if (a == null) {
                artShader = null;
                artPaint.setShader(null);
                bgSmall = null;
                return;
            }
            if (a.getConfig() == Bitmap.Config.HARDWARE) a = a.copy(Bitmap.Config.ARGB_8888, false);
            int side = Math.min(a.getWidth(), a.getHeight());
            Bitmap square = Bitmap.createBitmap(a, (a.getWidth() - side) / 2, (a.getHeight() - side) / 2, side, side);
            Bitmap label = Bitmap.createScaledBitmap(square, ART_PX, ART_PX, true);
            bgSmall = Bitmap.createScaledBitmap(square, 18, 18, true);
            artShader = new BitmapShader(label, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            artPaint.setShader(artShader);
            accent = vivid(label);
            updateTints();
        }

        /** The average colour weighted toward vivid pixels, then lifted so it reads on black. */
        private static int vivid(Bitmap b) {
            Bitmap t = Bitmap.createScaledBitmap(b, 16, 16, true);
            float[] hsv = new float[3];
            double r = 0, g = 0, bl = 0, total = 0;
            for (int y = 0; y < t.getHeight(); y++) {
                for (int x = 0; x < t.getWidth(); x++) {
                    int p = t.getPixel(x, y);
                    android.graphics.Color.colorToHSV(p, hsv);
                    double wgt = 0.05 + hsv[1] * hsv[2];
                    r += ((p >> 16) & 255) * wgt;
                    g += ((p >> 8) & 255) * wgt;
                    bl += (p & 255) * wgt;
                    total += wgt;
                }
            }
            int avg = 0xFF000000 | ((int) (r / total) << 16) | ((int) (g / total) << 8) | (int) (bl / total);
            android.graphics.Color.colorToHSV(avg, hsv);
            hsv[1] = Math.max(hsv[1], 0.45f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return android.graphics.Color.HSVToColor(hsv);
        }

        private void updateTints() {
            icShuffle.setTint(shuffleOn ? accent : 0xFFFFFFFF);
            icRepeat.setTint(repeatOn ? accent : 0xFFFFFFFF);
        }

        PlayerView(MusicPlayerActivity activity) {
            super(activity);
            this.activity = activity;
            setFocusable(true);
            setFocusableInTouchMode(true);

            groovePaint.setStyle(Paint.Style.STROKE);
            groovePaint.setColor(0x18FFFFFF);
            holePaint.setColor(0xFF1A1A1A);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setColor(0x33FFFFFF);

            barTrack.setColor(0x33FFFFFF);
            barFill.setColor(0xDDFFFFFF);

            focusRing.setColor(0x38FFFFFF);
            playDisc.setColor(0x1FFFFFFF);
            labelRim.setStyle(Paint.Style.STROKE);
            labelRim.setColor(0x55000000);
            armPaint.setStyle(Paint.Style.STROKE);
            armPaint.setStrokeCap(Paint.Cap.ROUND);
            armPaint.setStrokeJoin(Paint.Join.ROUND);
            armPaint.setColor(0xFFE4E4E4);

            icPlay = icon(activity, R.drawable.sym_play_arrow);
            icPause = icon(activity, R.drawable.sym_pause);
            icPrev = icon(activity, R.drawable.sym_skip_previous);
            icNext = icon(activity, R.drawable.sym_skip_next);
            icShuffle = icon(activity, R.drawable.sym_shuffle);
            icRepeat = icon(activity, R.drawable.sym_repeat);
        }

        private static android.graphics.drawable.Drawable icon(Activity a, int id) {
            return a.getDrawable(id).mutate();
        }

        void resume() {
            running = true;
            MediaController mc = session;
            if (mc == null) {
                activity.finish();
                return;
            }
            if (mc != boundController) {
                if (boundController != null) boundController.unregisterCallback(callback);
                mc.registerCallback(callback, handler);
                boundController = mc;
            }
            readMeta(mc.getMetadata());
            startAudio();
            lastFrame = SystemClock.uptimeMillis();
            scheduleTick();
            invalidate();
        }

        void pause() {
            running = false;
            stopAudio();
            handler.removeCallbacks(tick);
            if (boundController != null) {
                boundController.unregisterCallback(callback);
                boundController = null;
            }
        }

        private void scheduleTick() {
            if (!running) return;
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 16);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            this.w = w;
            this.h = h;
            k = h / 1080f;

            labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setTypeface(Ui.medium);
            labelPaint.setTextSize(26 * k);
            labelPaint.setColor(0x99FFFFFF);

            artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            artistPaint.setTypeface(Ui.displaySemibold);
            artistPaint.setTextSize(60 * k);
            artistPaint.setColor(0xFFFFFFFF);

            albumPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            albumPaint.setTypeface(Ui.regular);
            albumPaint.setTextSize(30 * k);
            albumPaint.setColor(0xA6FFFFFF);

            titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setTypeface(Ui.regular);
            titlePaint.setTextSize(40 * k);
            titlePaint.setColor(0xD9FFFFFF);

            timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            timePaint.setTypeface(Ui.regular);
            timePaint.setTextSize(24 * k);
            timePaint.setColor(0x99FFFFFF);

            armPaint.setStrokeWidth(8 * k);
            cachedTextWidth = -1;

            groovePaint.setStrokeWidth(0.5f * k);
            rimPaint.setStrokeWidth(2 * k);
            labelRim.setStrokeWidth(3 * k);
            vizShader = null;
        }

        @Override protected void onDraw(Canvas c) {
            MediaController mc = boundController;
            if (mc == null) return;
            MediaMetadata meta = mc.getMetadata();
            PlaybackState ps = mc.getPlaybackState();

            c.drawColor(0xFF0D0D0D);
            if (bgSmall != null) {
                bgRect.set(-40 * k, -40 * k, this.w + 40 * k, h + 40 * k);
                c.drawBitmap(bgSmall, null, bgRect, bgPaint);
                c.drawColor(0xA8000000);
            }

            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            long pos = 0, dur = 0;
            if (ps != null) {
                pos = ps.getPosition();
                if (playing && ps.getPlaybackSpeed() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
            }
            if (meta != null) dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            float frac = dur > 0 ? Math.min(1, Math.max(0, (float) pos / dur)) : 0;

            // The arm tracks the song: the outer groove at the start, just outside the label at the end. Without a
            // length it rests off the record.
            float armTo = dur > 0 ? ARM_START + (ARM_END - ARM_START) * frac : ARM_REST;
            float vinylCx = this.w * 0.27f;
            float vinylCy = h * 0.42f;
            float vinylR = h * 0.28f;
            drawVinyl(c, vinylCx, vinylCy, vinylR, playing && Math.abs(armAngle - armTo) < 2);
            drawArm(c, vinylCx, vinylCy, vinylR, armTo);

            // Right: "Now playing", then Artist – Album, then the song, as in a turntable app.
            float textLeft = this.w * 0.53f;
            float textRight = this.w - 110 * k;
            float textWidth = textRight - textLeft;
            float baseLabel = h * 0.25f, baseArtist = h * 0.33f, baseTitle = h * 0.405f;

            if (textWidth != cachedTextWidth || title != cachedTitle || artist != cachedArtist || album != cachedAlbum) {
                cachedTextWidth = textWidth;
                cachedTitle = title;
                cachedArtist = artist;
                cachedAlbum = album;
                drawnTitle = title != null
                        ? TextUtils.ellipsize(title, titlePaint, textWidth, TextUtils.TruncateAt.END).toString() : null;
                drawnArtist = artist != null
                        ? TextUtils.ellipsize(artist, artistPaint, textWidth * 0.65f, TextUtils.TruncateAt.END).toString() : null;
                drawnArtistWidth = drawnArtist == null ? 0 : artistPaint.measureText(drawnArtist);
                drawnDash = drawnArtist != null && album != null ? "  –  " : null;
                float left = textWidth - drawnArtistWidth - (drawnDash == null ? 0 : albumPaint.measureText(drawnDash));
                drawnAlbum = album != null && left > 80 * k
                        ? TextUtils.ellipsize(album, albumPaint, left, TextUtils.TruncateAt.END).toString() : null;
                if (drawnAlbum == null || drawnAlbum.isEmpty()) drawnDash = null;
            }
            c.drawText(Ui.tr("Now playing"), textLeft, baseLabel, labelPaint);
            if (drawnArtist != null) c.drawText(drawnArtist, textLeft, baseArtist, artistPaint);
            if (drawnDash != null) {
                float x = textLeft + drawnArtistWidth;
                c.drawText(drawnDash, x, baseArtist, albumPaint);
                c.drawText(drawnAlbum, x + albumPaint.measureText(drawnDash), baseArtist, albumPaint);
            } else if (drawnArtist == null && drawnAlbum != null) {
                c.drawText(drawnAlbum, textLeft, baseArtist, albumPaint);
            }
            if (drawnTitle != null) c.drawText(drawnTitle, textLeft, baseTitle, titlePaint);

            // Progress: the times above the bar, a knob at the playhead.
            float barY = h * 0.52f;
            float barH = 6 * k;
            float barR = barH / 2;

            barRect.set(textLeft, barY, textRight, barY + barH);
            c.drawRoundRect(barRect, barR, barR, barTrack);
            if (dur > 0) {
                float head = textLeft + textWidth * frac;
                barRect.set(textLeft, barY, head, barY + barH);
                c.drawRoundRect(barRect, barR, barR, barFill);
                c.drawCircle(head, barY + barH / 2, 12 * k, barFill);
            }

            if (dur > 0 && timePaint != null) {
                long posSec = pos / 1000;
                if (posSec != shownPosSec) {
                    shownPosSec = posSec;
                    posStr = formatTime(pos);
                }
                if (dur != shownDur) {
                    shownDur = dur;
                    durStr = formatTime(dur);
                }
                c.drawText(posStr, textLeft, barY - 20 * k, timePaint);
                float durW = timePaint.measureText(durStr);
                c.drawText(durStr, textRight - durW, barY - 20 * k, timePaint);
            }

            // Controls, spread across the column: repeat, previous, play, next, shuffle.
            float ctrlY = h * 0.65f;
            for (int i = 0; i < BUTTON_COUNT; i++) ctrlCx[i] = textLeft + textWidth * (i + 0.5f) / BUTTON_COUNT;
            c.drawCircle(ctrlCx[FOCUS_PLAY], ctrlY, 58 * k, playDisc);
            c.drawCircle(ctrlCx[focus], ctrlY, focus == FOCUS_PLAY ? 58 * k : 46 * k, focusRing);
            drawIcon(c, icShuffle, ctrlCx[FOCUS_SHUFFLE], ctrlY, 40 * k, shuffleOn || focus == FOCUS_SHUFFLE ? 255 : 150);
            drawIcon(c, icPrev, ctrlCx[FOCUS_PREV], ctrlY, 52 * k, focus == FOCUS_PREV ? 255 : 210);
            drawIcon(c, playing ? icPause : icPlay, ctrlCx[FOCUS_PLAY], ctrlY, 72 * k, 255);
            drawIcon(c, icNext, ctrlCx[FOCUS_NEXT], ctrlY, 52 * k, focus == FOCUS_NEXT ? 255 : 210);
            drawIcon(c, icRepeat, ctrlCx[FOCUS_REPEAT], ctrlY, 40 * k, repeatOn || focus == FOCUS_REPEAT ? 255 : 150);

            drawVisualizer(c, playing);

            boolean settling = Math.abs(vizLevel - VIZ_REST) > 0.005f || Math.abs(armAngle - armTo) > 0.05f;
            if (running && (playing || settling)) scheduleTick();
        }

        private static void drawIcon(Canvas c, android.graphics.drawable.Drawable d, float cx, float cy, float size, int alpha) {
            int half = Math.round(size / 2);
            int x = Math.round(cx), y = Math.round(cy);
            d.setBounds(x - half, y - half, x + half, y + half);
            d.setAlpha(alpha);
            d.draw(c);
        }

        /**
         * Three soft waves on a glowing line, in the art's colour: the back one tallest and faintest, the front one lowest
         * and nearly white. Bass on the left, treble on the right; each band rises fast and falls slowly, like a meter.
         */
        private void drawVisualizer(Canvas c, boolean playing) {
            vizLevel += ((playing ? 1 : VIZ_REST) - vizLevel) * 0.06f;
            float base = h, maxH = h * 0.24f;
            if (vizShader == null) {   // the colours (and blurs) for this art and size, made once
                for (int j = 0; j < LAYERS; j++) {
                    int rgb = mixWhite(accent, LAYER_WHITE[j]) & 0x00FFFFFF;
                    layerPaint[j].setShader(new LinearGradient(0, base, 0, base - maxH * LAYER_H[j],
                            new int[] {rgb | LAYER_ALPHA[j] << 24, rgb | (LAYER_ALPHA[j] / 3) << 24}, null,
                            Shader.TileMode.CLAMP));
                    layerPaint[j].setMaskFilter(new android.graphics.BlurMaskFilter(LAYER_BLUR[j] * k,
                            android.graphics.BlurMaskFilter.Blur.NORMAL));
                }
                int line = mixWhite(accent, 0.8f) & 0x00FFFFFF;
                vizShader = new LinearGradient(0, 0, w, 0,
                        new int[] {line | 0x55000000, line | 0xFF000000, line | 0xFF000000, line | 0x55000000},
                        new float[] {0, 0.2f, 0.8f, 1}, Shader.TileMode.CLAMP);
                linePaint.setShader(vizShader);
                linePaint.setMaskFilter(new android.graphics.BlurMaskFilter(3 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
            boolean live = audio != null && SystemClock.elapsedRealtime() - soundAt < 1500;
            float t = SystemClock.uptimeMillis() / 1000f;
            for (int i = 0; i < VIZ_N; i++) {
                float target;
                if (!playing) {
                    target = 0;
                } else if (live) {
                    target = bandTarget[i];
                } else {   // no sound to read: bass slower and taller, treble quicker and lower
                    float pitch = (float) i / VIZ_N;
                    float n = 0.5f + 0.5f * (float) (Math.sin(t * (2.2 + pitch * 5) + i * 1.7) * Math.sin(t * 1.3 + i * 0.5));
                    target = (0.85f - 0.45f * pitch) * (0.25f + 0.75f * n);
                }
                float lv = bandLevel[i];
                bandLevel[i] = lv + (target - lv) * (target > lv ? 0.55f : 0.12f);
            }
            for (int j = 0; j < LAYERS; j++) {
                wave(base, maxH * LAYER_H[j], j);
                c.drawPath(vizPath, layerPaint[j]);
            }
            lineRect.set(0, base - 4 * k, w, base);
            c.drawRoundRect(lineRect, 2 * k, 2 * k, linePaint);
        }

        /**
         * One wave into vizPath (no allocation): a smooth curve through the bands' levels across the whole width, lower
         * toward both ends. Each layer reads the bands a step further along, so the three don't rise as one.
         */
        private void wave(float base, float height, int layer) {
            float left = 0, span = w, floor = height * 0.05f;
            for (int p = 0; p <= WAVE_POINTS; p++) {
                float x = (float) p / WAVE_POINTS;
                int b = Math.round(Math.min(1, x + layer * 0.04f) * (VIZ_N - 1));
                float sum = 0;
                int n = 0;
                for (int q = Math.max(0, b - 1); q <= Math.min(VIZ_N - 1, b + 1); q++, n++) sum += bandLevel[q];
                float taper = 0.4f + 0.6f * (float) Math.sqrt(Math.sin(Math.PI * x));
                waveY[p] = base - (floor + (height - floor) * Math.min(1, sum / n * vizLevel)) * taper;
            }
            vizPath.reset();
            vizPath.moveTo(left, base);
            float px = left, py = waveY[0];
            vizPath.lineTo(px, py);
            for (int p = 1; p <= WAVE_POINTS; p++) {
                float nx = left + span * p / WAVE_POINTS, ny = waveY[p];
                vizPath.quadTo(px, py, (px + nx) / 2, (py + ny) / 2);
                px = nx;
                py = ny;
            }
            vizPath.lineTo(px, py);
            vizPath.lineTo(left + span, base);
            vizPath.close();
        }

        /** The colour moved `f` of the way to white. */
        private static int mixWhite(int color, float f) {
            int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
            return 0xFF000000 | Math.round(r + (255 - r) * f) << 16 | Math.round(g + (255 - g) * f) << 8
                    | Math.round(b + (255 - b) * f);
        }

        /** `turning`: playing, with the arm on its groove. */
        private void drawVinyl(Canvas c, float cx, float cy, float r, boolean turning) {
            long now = SystemClock.uptimeMillis();
            // 20 rpm: slow enough to stay calm, fast enough to read as turning.
            if (turning && lastFrame > 0) {
                vinylAngle += (now - lastFrame) * 0.12f;
                if (vinylAngle > 360) vinylAngle -= 360;
            }
            lastFrame = now;

            // The platter: a soft shadow, then a light rim around the record.
            vinylPaint.setColor(0x66000000);
            c.drawCircle(cx, cy + 10 * k, r * 1.045f, vinylPaint);
            vinylPaint.setColor(0xFFEDEDED);
            c.drawCircle(cx, cy, r * 1.025f, vinylPaint);
            vinylPaint.setColor(0xFF161616);
            c.drawCircle(cx, cy, r, vinylPaint);
            c.drawCircle(cx, cy, r - 1 * k, rimPaint);
            for (int i = 1; i <= 14; i++) c.drawCircle(cx, cy, r * (0.46f + i * 0.037f), groovePaint);

            // The label: the album art, turning with the record.
            float artR = r * 0.44f;
            if (artShader != null) {
                float s = artR * 2 / ART_PX;
                artMatrix.setScale(s, s);
                artMatrix.postTranslate(cx - artR, cy - artR);
                artMatrix.postRotate(vinylAngle, cx, cy);
                artShader.setLocalMatrix(artMatrix);
                c.drawCircle(cx, cy, artR, artPaint);
            } else {
                vinylPaint.setColor(accent);
                c.drawCircle(cx, cy, artR, vinylPaint);
            }
            c.drawCircle(cx, cy, artR, labelRim);
            c.drawCircle(cx, cy, 9 * k, holePaint);
        }

        /**
         * The tonearm, pivoting at the record's top-right corner, eased toward `to` degrees (it glides in when the player
         * opens or a song changes, then follows the song). Drawn hanging straight down, then turned about the pivot.
         */
        private void drawArm(Canvas c, float cx, float cy, float r, float to) {
            armAngle += (to - armAngle) * 0.07f;
            float px = cx + r * 0.98f, py = cy - r * 0.98f, len = r * 1.25f;
            c.save();
            c.rotate(armAngle, px, py);
            // Counterweight behind the pivot.
            armPart.setColor(0xFFBDBDBD);
            armRect.set(px - r * 0.07f, py - r * 0.24f, px + r * 0.07f, py - r * 0.08f);
            c.drawRoundRect(armRect, 4 * k, 4 * k, armPart);
            // The arm, with a bend near the head toward the record.
            armPath.reset();
            armPath.moveTo(px, py);
            armPath.lineTo(px, py + len * 0.78f);
            armPath.lineTo(px - len * 0.10f, py + len);
            c.drawPath(armPath, armPaint);
            // The head (cartridge), squared to the bend.
            c.save();
            c.rotate(24, px - len * 0.10f, py + len);
            armPart.setColor(0xFFF5F5F5);
            armRect.set(px - len * 0.10f - r * 0.045f, py + len - r * 0.02f, px - len * 0.10f + r * 0.045f, py + len + r * 0.12f);
            c.drawRoundRect(armRect, 4 * k, 4 * k, armPart);
            c.restore();
            c.restore();
            // The pivot doesn't turn.
            armPart.setColor(0x66000000);
            c.drawCircle(px, py + 4 * k, r * 0.11f, armPart);
            armPart.setColor(0xFFDCDCDC);
            c.drawCircle(px, py, r * 0.10f, armPart);
            armPart.setColor(0xFF9A9A9A);
            c.drawCircle(px, py, r * 0.045f, armPart);
        }

        private static String formatTime(long ms) {
            if (ms < 0) ms = 0;
            long s = ms / 1000;
            long m = s / 60;
            s = s % 60;
            if (m >= 60) {
                long h = m / 60;
                m = m % 60;
                return h + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
            }
            return m + ":" + (s < 10 ? "0" : "") + s;
        }

        @Override public boolean onKeyDown(int code, KeyEvent event) {
            MediaController mc = boundController;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (focus > 0) { focus--; invalidate(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (focus < BUTTON_COUNT - 1) { focus++; invalidate(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (mc != null) pressButton(mc);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (mc != null) {
                        PlaybackState ps = mc.getPlaybackState();
                        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING)
                            mc.getTransportControls().pause();
                        else mc.getTransportControls().play();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (mc != null) { mc.getTransportControls().skipToNext(); invalidate(); }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (mc != null) { mc.getTransportControls().skipToPrevious(); invalidate(); }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    activity.finish();
                    return true;
            }
            return super.onKeyDown(code, event);
        }

        private void pressButton(MediaController mc) {
            MediaController.TransportControls tc = mc.getTransportControls();
            PlaybackState ps = mc.getPlaybackState();
            switch (focus) {
                case FOCUS_SHUFFLE:
                    shuffleOn = !shuffleOn;
                    try { tc.sendCustomAction("shuffle", null); } catch (Exception ignored) {}
                    break;
                case FOCUS_PREV:
                    tc.skipToPrevious();
                    break;
                case FOCUS_PLAY:
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING)
                        tc.pause();
                    else
                        tc.play();
                    break;
                case FOCUS_NEXT:
                    tc.skipToNext();
                    break;
                case FOCUS_REPEAT:
                    repeatOn = !repeatOn;
                    try { tc.sendCustomAction("repeat", null); } catch (Exception ignored) {}
                    break;
            }
            updateTints();
            invalidate();
        }
    }
}
