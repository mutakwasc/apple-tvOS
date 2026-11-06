package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

/**
 * Three levels of what the TV plays, bass, mids and treble (0 to 1), for the now-playing bars on the screensaver: from
 * Android's Visualizer on the sound output, which needs RECORD_AUDIO (the music player asks for it; no microphone is
 * opened). Without it, or where the TV hands the sound past the mixer (silence), live() is false and the bars bounce on
 * their own. Start and stop it with the screen that shows it; read it on the main thread.
 */
final class AudioLevels {
    private static final float[] EDGES = {40, 250, 2000, 12000};   // Hz: bass, mids, treble
    final float[] level = new float[3];
    private final float[] target = new float[3];
    private Visualizer audio;
    private long soundAt;

    private final Visualizer.OnDataCaptureListener capture = new Visualizer.OnDataCaptureListener() {
        @Override public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) {}

        @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rateMilliHz) {
            float binHz = rateMilliHz / 1000f / fft.length;
            if (binHz <= 0) return;
            int bins = fft.length / 2;
            boolean sound = false;
            for (int b = 0; b < 3; b++) {
                int lo = Math.max(1, Math.round(EDGES[b] / binHz)), hi = Math.min(bins - 1, Math.round(EDGES[b + 1] / binHz));
                float peak = 0;
                for (int i = lo; i <= hi; i++) {
                    float re = fft[2 * i], im = fft[2 * i + 1];
                    peak = Math.max(peak, re * re + im * im);
                }
                float db = 10 * (float) Math.log10(peak + 1);
                target[b] = Math.max(0, Math.min(1, (db - 8) / 32));
                sound |= target[b] > 0.05f;
            }
            if (sound) soundAt = SystemClock.elapsedRealtime();
        }
    };

    void start(Context c) {
        if (audio != null || c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            audio = new Visualizer(0);
            audio.setCaptureSize(Math.min(512, Visualizer.getCaptureSizeRange()[1]));
            audio.setDataCaptureListener(capture, Math.min(20000, Visualizer.getMaxCaptureRate()), false, true);
            audio.setEnabled(true);
        } catch (RuntimeException e) {   // a TV without the effect, or the output mix taken
            AppLog.w("audio levels: " + e.getMessage());
            stop();
        }
    }

    void stop() {
        if (audio == null) return;
        try {
            audio.setEnabled(false);
        } catch (RuntimeException ignored) {
        }
        audio.release();
        audio = null;
        soundAt = 0;
    }

    /** Sound came in the last second and a half: the levels follow it. */
    boolean live() {
        return audio != null && SystemClock.elapsedRealtime() - soundAt < 1500;
    }

    /** One frame: each level eases toward the sound, up fast and down slowly, like a meter. */
    void step() {
        for (int i = 0; i < 3; i++) {
            float l = level[i], t = target[i];
            level[i] = l + (t - l) * (t > l ? 0.5f : 0.15f);
        }
    }
}
