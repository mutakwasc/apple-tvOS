package io.freemytv;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits; with Watch from the screensaver on (Home Settings), OK first opens the
 * title on screen in its app.
 */
public class Screensaver extends DreamService {
    private DreamView view;
    private CozyView cozy;   // Cozy Nights instead of the photos

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        boolean fireplace = "cozy".equals(Prefs.saverStyle());
        // Interactive: the keys come here instead of waking the TV (OK opens the title on screen; Cozy Nights has none).
        setInteractive(Prefs.saverWatch() && !fireplace);
        setFullscreen(true);
        setScreenBright(true);
        if (fireplace) {
            cozy = new CozyView(this);
            setContentView(cozy);
        } else {
            view = new DreamView(this);
            setContentView(view);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isInteractive()) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // OK acts when it's released, so the app it opens doesn't get the release; any other key closes at once.
        if (ok ? event.getAction() == KeyEvent.ACTION_UP : event.getAction() == KeyEvent.ACTION_DOWN) {
            if (ok && view != null) view.watch();
            finish();
        }
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
        if (cozy != null) cozy.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        if (cozy != null) cozy.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        if (cozy != null) cozy.stop();
        super.onDetachedFromWindow();
    }
}
