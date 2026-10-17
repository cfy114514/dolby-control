package io.github.dcl.app;
import android.content.*;
import java.util.concurrent.Executors;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Context device = context.createDeviceProtectedStorageContext();
        if (!device.getSharedPreferences("activation", 0).getBoolean("enabled", false)) return;
        PendingResult result = goAsync();
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> { try { RootClient.ensureStarted(context.getApplicationContext()); } catch (Exception ignored) {} finally { result.finish(); executor.shutdown(); } });
    }
}
