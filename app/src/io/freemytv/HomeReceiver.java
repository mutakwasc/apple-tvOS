package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * System notices for the main process: the TV booted, this app was updated, or the sleep timer ended. Not exported.
 */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        } else if (SleepTimer.ACTION.equals(action)) {
            long setAt = intent.getLongExtra("setAt", 0);
            later(() -> SleepTimer.fire(context, setAt));   // ADB is network
        }
    }

    /** Off the main thread (Updates may wait for a check under way), with the notice held open until it's done. */
    private void later(Runnable work) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                work.run();
            } finally {
                result.finish();
            }
        }).start();
    }
}
