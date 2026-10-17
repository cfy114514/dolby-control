package io.github.dcl.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Context device = context.createDeviceProtectedStorageContext();
        if (!device.getSharedPreferences("activation", 0).getBoolean("enabled", false)) return;
        PendingResult result = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                // Finish inside the broadcast time limit even when a service or
                // another startup attempt is unresponsive. The app can retry later.
                RootClient.ensureStarted(context.getApplicationContext(), 8000);
            } catch (Exception error) {
                Log.w("DolbyControl", "Background startup deferred: " + error.getMessage());
            } finally {
                result.finish();
                executor.shutdown();
            }
        });
    }
}
