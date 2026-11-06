package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The home screen's own background (Wallpaper: Default, and wherever a wallpaper has no picture): a glowing wave of
 * light that drifts and slowly runs through the colours. At night a bright wave on black; by day a pale one on a light
 * background, so the day's dark text stays readable. The glow is drawn once, small (it's all blur), as an alpha mask;
 * each frame only moves, stretches and tints it: two bitmaps drawn, nothing made.
 */
final class AuraWave {
    /** How often it moves: slow enough to look still at a glance, often enough to never step. */
    static final long FRAME_MS = 50;
    private static final int MW = 480, MH = 270;
    /** Seconds for the colours to go all the way round. */
    private static final float HUE_PERIOD = 90;

    private final Bitmap mask = buildMask();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint();
    private final float[] hsv = new float[3];
    private final RectF dst = new RectF();

    /** Draws it over `screen`; `night` 0 (day) to 1, `veil` the drawer's filter or null. */
    void draw(Canvas c, RectF screen, float night, ColorFilter veil, long uptime) {
        float t = uptime / 1000f, w = screen.width(), h = screen.height();
        fill.setColorFilter(veil);
        fill.setColor(mix(0xFFF2F2F5, 0xFF000000, night));
        c.drawRect(screen, fill);
        paint.setColorFilter(veil);
        float hue = (t * 360 / HUE_PERIOD) % 360;
        // The main wave: drifting sideways and breathing up and down, larger than the screen so its edges never show.
        layer(c, w, h, hue, night, 1f,
                (float) Math.sin(t / 23) * 0.05f, (float) Math.sin(t / 31) * 0.03f, 1 + 0.06f * (float) Math.sin(t / 17), false);
        // A second, fainter one mirrored, a little further round the colour wheel: the two cross as they move.
        layer(c, w, h, (hue + 50) % 360, night, 0.45f,
                (float) Math.sin(t / 29 + 2) * 0.06f, (float) Math.sin(t / 37 + 1) * 0.04f, 1 + 0.08f * (float) Math.sin(t / 21 + 1), true);
        paint.setColorFilter(null);
        fill.setColorFilter(null);
    }

    private void layer(Canvas c, float w, float h, float hue, float night, float strength, float dx, float dy, float sy,
                       boolean mirror) {
        hsv[0] = hue;
        hsv[1] = 0.88f - 0.5f * (1 - night);   // pale by day
        hsv[2] = 1;
        int color = Color.HSVToColor(hsv);
        paint.setColor(color);
        paint.setAlpha(Math.round(255 * strength * (0.55f + 0.45f * night)));
        float ow = w * 1.16f, oh = h * 1.16f * sy;
        float left = (w - ow) / 2 + dx * w, top = (h - oh) / 2 + dy * h;
        dst.set(left, top, left + ow, top + oh);
        if (mirror) {
            c.save();
            c.scale(-1, 1, w / 2, 0);
            c.drawBitmap(mask, null, dst, paint);
            c.restore();
        } else {
            c.drawBitmap(mask, null, dst, paint);
        }
    }

    /**
     * The glow, once: a soft band along a gentle wave (high on the left, dipping on the right), a haze under it, and a
     * bright pool low on the right, as in a light leak. Alpha only, so drawing it takes the paint's colour.
     */
    private static Bitmap buildMask() {
        Bitmap b = Bitmap.createBitmap(MW, MH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path wave = new Path();
        for (int i = 0; i <= 48; i++) {
            float x = MW * i / 48f;
            float y = MH * (0.56f - 0.08f * (float) Math.sin(Math.PI * 2 * i / 48f * 0.9f + 0.4f));
            if (i == 0) wave.moveTo(x, y); else wave.lineTo(x, y);
        }
        // Haze under the band, fading downward.
        Path under = new Path(wave);
        under.lineTo(MW, MH);
        under.lineTo(0, MH);
        under.close();
        Paint haze = new Paint(Paint.ANTI_ALIAS_FLAG);
        haze.setShader(new LinearGradient(0, MH * 0.45f, 0, MH, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        haze.setMaskFilter(new BlurMaskFilter(MH * 0.06f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(under, haze);
        // The band itself: a wide soft glow, then a narrower brighter core.
        Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        band.setStyle(Paint.Style.STROKE);
        band.setColor(0x99FFFFFF);
        band.setStrokeWidth(MH * 0.10f);
        band.setMaskFilter(new BlurMaskFilter(MH * 0.08f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(wave, band);
        band.setColor(0xCCFFFFFF);
        band.setStrokeWidth(MH * 0.03f);
        band.setMaskFilter(new BlurMaskFilter(MH * 0.025f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(wave, band);
        // The bright pool, low on the right.
        Paint pool = new Paint(Paint.ANTI_ALIAS_FLAG);
        pool.setShader(new RadialGradient(MW * 0.98f, MH * 0.62f, MW * 0.42f, 0xEEFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, MW, MH, pool);
        Bitmap alpha = b.extractAlpha();
        b.recycle();
        return alpha;
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
