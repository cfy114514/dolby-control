package io.github.dcl.app;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import io.github.dcl.BoundedLine;
import io.github.dcl.BoundedProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** A fixed JSON protocol. Hooked applications never execute su or arbitrary commands. */
public final class RootClient {
    public static final String SOCKET = "dcl.audio.v1";
    private static final String BASE = "/data/adb/dolby_control_app";
    private static final ReentrantLock START_LOCK = new ReentrantLock();

    private RootClient() {}

    public static JSONObject call(String action, JSONObject arguments) throws Exception {
        return call(action, arguments, 20000);
    }

    /** Preserve a user's original deadline while an injected application's worker is busy. */
    public static JSONObject callBefore(String action, JSONObject arguments,
                                        long deadlineElapsedMillis) throws Exception {
        long deadline = Math.min(deadlineElapsedMillis, SystemClock.elapsedRealtime() + 20000);
        remaining(deadline, 20000);
        return callUntil(action, arguments, deadline);
    }

    private static JSONObject call(String action, JSONObject arguments, int timeoutMillis) throws Exception {
        if (timeoutMillis < 1 || timeoutMillis > 20000) throw new IllegalArgumentException("Invalid RPC timeout");
        return callUntil(action, arguments, SystemClock.elapsedRealtime() + timeoutMillis);
    }

    private static JSONObject callUntil(String action, JSONObject arguments, long deadline) throws Exception {
        if (!action.matches("status|settings|enhancement|edit|manual|reassert|shutdown")) {
            throw new IOException("不支持的操作");
        }
        byte[] request = (new JSONObject().put("action", action)
                .put("deadline_elapsed_ms", deadline)
                .put("args", arguments == null ? new JSONObject() : arguments).toString() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (request.length > 32768) throw new IOException("操作内容过长");
        try (LocalSocket socket = new LocalSocket()) {
            // Getting the stream creates the descriptor without connecting it.
            // Install send/receive limits before a potentially blocked connect.
            InputStream input = socket.getInputStream();
            socket.setSoTimeout(remaining(deadline, 20000));
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            if (socket.getPeerCredentials().getUid() != 0) throw new SecurityException("后台身份不匹配");
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            String response = BoundedLine.read(input, 131072,
                    remaining(deadline, 20000), socket::setSoTimeout);
            if (response.isEmpty()) throw new IOException("音效后台未返回结果");
            return new JSONObject(response);
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static boolean matches(JSONObject state, String apk) {
        return state.optBoolean("ready") && BuildIdentity.TOKEN.equals(state.optString("build"))
                && apk.equals(state.optString("apk"));
    }

    private static int remaining(long deadline, int maximum) throws IOException {
        long milliseconds = deadline - SystemClock.elapsedRealtime();
        if (milliseconds <= 0) throw new IOException("音效操作超时，请重试");
        return (int) Math.min(milliseconds, maximum);
    }

    private static void markActivated(Context context) {
        context.createDeviceProtectedStorageContext().getSharedPreferences("activation", 0)
                .edit().putBoolean("enabled", true).apply();
    }

    public static void ensureStarted(Context context) throws Exception {
        ensureStarted(context, 25000);
    }

    /** A boot receiver uses a shorter total budget, including any competing startup. */
    static void ensureStarted(Context context, int timeoutMillis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        if (!START_LOCK.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IOException("音效后台正在启动，请稍后重试");
        }
        try {
            ensureStartedLocked(context, deadline);
        } finally {
            START_LOCK.unlock();
        }
    }

    private static void ensureStartedLocked(Context context, long deadline) throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        if (!apk.startsWith("/data/app/") || !apk.endsWith(".apk") || apk.indexOf('\n') >= 0) {
            throw new IOException("安装路径无法识别");
        }
        JSONObject existing = null;
        try { existing = call("status", null, remaining(deadline, 1500)); }
        catch (IOException ignored) {}
        if (existing != null && matches(existing, apk)) {
            markActivated(context);
            return;
        }
        if (existing != null) {
            call("shutdown", null, remaining(deadline, 1500));
            long stopDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 2500);
            boolean stopped = false;
            while (SystemClock.elapsedRealtime() < stopDeadline) {
                try {
                    JSONObject state = call("status", null, remaining(stopDeadline, 500));
                    if (matches(state, apk)) {
                        markActivated(context);
                        return;
                    }
                } catch (IOException gone) {
                    stopped = true;
                    break;
                }
                SystemClock.sleep(Math.min(100, remaining(stopDeadline, 100)));
            }
            if (!stopped) throw new IOException("旧音效后台仍在退出，请稍后重试");
        }
        String launch = "export CLASSPATH=" + quote(apk)
                + "; exec /system/bin/app_process /system/bin --nice-name=io.github.dcl.RootDaemon io.github.dcl.RootDaemon";
        String command = "umask 077; mkdir -p " + quote(BASE)
                + " && /data/adb/ksu/bin/busybox nohup /system/bin/sh -c " + quote(launch)
                + " </dev/null >>" + quote(BASE + "/daemon.log") + " 2>&1 &";
        BoundedProcess.run(Arrays.asList("su", "-c", command), null,
                remaining(deadline, 15000), 32768);
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                JSONObject state = call("status", null, remaining(deadline, 1000));
                // Build tokens alone cannot distinguish two installs of the same APK.
                if (matches(state, apk)) {
                    markActivated(context);
                    return;
                }
            } catch (IOException ignored) {}
            SystemClock.sleep(Math.min(100, remaining(deadline, 100)));
        }
        throw new IOException("音效后台未能启动，请确认 Root 授权");
    }
}
