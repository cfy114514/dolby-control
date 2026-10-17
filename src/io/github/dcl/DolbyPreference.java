package io.github.dcl;

import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Method;

/** Reads Samsung's live preference; the root service never enables Dolby itself. */
final class DolbyPreference {
    private static final String AUTHORITY = "com.sec.android.app.soundalive.compatibility.SAContentProvider";

    private DolbyPreference() {}

    static int mode() throws Exception {
        IBinder token = new Binder();
        Class<?> activityManager = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder = activityManager.getMethod("getContentProviderExternal", String.class,
                int.class, IBinder.class, String.class)
                .invoke(manager, AUTHORITY, 0, token, "io.github.dcl.RootDaemon");
        if (holder == null) throw new Exception("Dolby provider unavailable");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Class<?> attribution = Class.forName("android.content.AttributionSource");
            Object source = attribution.getMethod("myAttributionSource").invoke(null);
            Method query = Class.forName("android.content.IContentProvider").getMethod("query",
                    attribution, Uri.class, String[].class, Bundle.class,
                    Class.forName("android.os.ICancellationSignal"));
            try (Cursor cursor = (Cursor) query.invoke(provider, source, Uri.parse("content://" + AUTHORITY),
                    new String[]{"DOLBY_ATMOS_LEVEL"}, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new Exception("Dolby preference unavailable");
                int column = cursor.getColumnIndex("Result");
                if (column < 0) throw new Exception("Unexpected Dolby provider layout");
                int mode = Integer.parseInt(cursor.getString(column));
                if (mode != 0 && mode != 1 && mode != 2 && mode != 3 && mode != 5) {
                    throw new Exception("Unsupported Dolby preference");
                }
                return mode;
            }
        } finally {
            activityManager.getMethod("removeContentProviderExternalAsUser", String.class,
                    IBinder.class, int.class).invoke(manager, AUTHORITY, token, 0);
        }
    }
}
