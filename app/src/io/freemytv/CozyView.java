package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.VideoView;

/**
 * The Cozy Nights screensaver (Home Settings > Screensaver > Style): a fireplace (res/raw/fireplace.mp4, a short
 * seamless clip) looping on the whole screen, muted and without taking the sound, so the music goes on; and in the
 * bottom-right corner, while something plays, a quiet line of it: the level bars, the song, the artist under it and a
 * thin progress bar. No card behind it and no times: just text with a soft shadow over the flames.
 */
final class CozyView extends FrameLayout {
    private final VideoView video;
    private final Corner corner;
    private boolean running;

    CozyView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        video = new VideoView(c);
        // No audio focus: VideoView asks for it by default, and that pauses the music. Muted, it needs none.
        video.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE);
        video.setVideoURI(Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.fireplace));
        video.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            AppLog.w("cozy nights: the video can't play (" + what + "/" + extra + ")");
            return true;   // the black background and the corner stay
        });
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // Screensaver > Show music player: off, the fire alone.
        corner = Prefs.saverMusic() ? new Corner(c) : null;
        if (corner != null) addView(corner, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void start() {
        if (running) return;
        running = true;
        video.start();
        if (corner != null) corner.start();
    }

    void stop() {
        if (!running) return;
        running = false;
        video.stopPlayback();
        if (corner != null) corner.stop();
    }

    /** What's playing, drawn by hand in the corner; redrawn every frame while it shows (the bars move). */
    private static final class Corner extends View {
        private static final float WIDTH = 460, MARGIN = 72;
        private final NowPlaying.Watch playing;
        private final AudioLevels audio = new AudioLevels();
        private final Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private TextPaint titlePaint, artistPaint;
        private String title, artist, shownFor;
        private float k = 1;
        private boolean running;

        Corner(Context c) {
            super(c);
            playing = new NowPlaying.Watch(c, this::changed);
            bars.setColor(0xD9FFFFFF);
            track.setColor(0x33FFFFFF);
            fill.setColor(0xB3FFFFFF);
        }

        void start() {
            running = true;
            playing.start();
            audio.start(getContext());
            invalidate();
        }

        void stop() {
            running = false;
            audio.stop();
            playing.stop();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            titlePaint = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xD9FFFFFF));
            artistPaint = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x99FFFFFF));
            for (Paint p : new Paint[] {titlePaint, artistPaint, bars}) p.setShadowLayer(14 * k, 0, 2 * k, 0x99000000);
            shownFor = null;
            changed();
        }

        /** The song changed: its lines cut to fit (not while drawing). */
        private void changed() {
            if (titlePaint == null) return;
            String now = playing.text;
            if (now == null) {
                title = artist = shownFor = null;
            } else if (!now.equals(shownFor)) {
                shownFor = now;
                float room = WIDTH * k;
                title = TextUtils.ellipsize(playing.title != null ? playing.title : now, titlePaint, room,
                        TextUtils.TruncateAt.END).toString();
                artist = playing.artist == null ? null
                        : TextUtils.ellipsize(playing.artist, artistPaint, room, TextUtils.TruncateAt.END).toString();
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null || titlePaint == null) return;
            float right = getWidth() - MARGIN * k, bottom = getHeight() - MARGIN * k;
            float barY = bottom - 3 * k, artistBase = barY - 22 * k;
            float titleBase = artist != null ? artistBase - 36 * k : artistBase;
            // Right-aligned, the bars just before the title.
            float titleW = titlePaint.measureText(title), cap = Ui.cap(titlePaint), barsW = NowPlaying.barsWidth(cap);
            float titleX = right - titleW;
            NowPlaying.drawBars(c, titleX - 12 * k - barsW, titleBase, cap, bars, audio, rect);
            c.drawText(title, titleX, titleBase, titlePaint);
            if (artist != null) c.drawText(artist, right - artistPaint.measureText(artist), artistBase, artistPaint);
            long duration = playing.duration;
            if (duration > 0) {
                float left = right - WIDTH * k, h = 3 * k;
                rect.set(left, barY, right, barY + h);
                c.drawRoundRect(rect, h / 2, h / 2, track);
                rect.right = left + (right - left) * Math.min(1, (float) NowPlaying.position(playing) / duration);
                c.drawRoundRect(rect, h / 2, h / 2, fill);
            }
            if (running) postInvalidateOnAnimation();
        }
    }
}
