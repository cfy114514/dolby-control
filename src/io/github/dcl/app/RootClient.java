package io.github.dcl.app;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A fixed JSON protocol. Hooked applications never execute su or arbitrary commands. */
public final class RootClient {
    public static final String SOCKET = "dcl.audio.v1";
    private static final String BASE = "/data/adb/dolby_control_app";
    private RootClient() {}
    public static JSONObject call(String action, JSONObject args) throws Exception {
        if (!action.matches("status|settings|enhancement|edit|manual|reassert|shutdown"))
            throw new IOException("不支持的操作");
        byte[] request = (new JSONObject().put("action", action).put("args", args == null ? new JSONObject() : args).toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (request.length > 32768) throw new IOException("操作内容过长");
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(20000);
            if (socket.getPeerCredentials().getUid() != 0) throw new SecurityException("后台身份不匹配");
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            for (int b; (b = input.read()) != -1 && b != '\n';) {
                if (response.size() >= 131072) throw new IOException("返回内容过长");
                response.write(b);
            }
            if (response.size() == 0) throw new IOException("音效后台未返回结果");
            return new JSONObject(response.toString("UTF-8"));
        }
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    public static synchronized void ensureStarted(Context context) throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        if (!apk.startsWith("/data/app/") || !apk.endsWith(".apk") || apk.indexOf('\n') >= 0)
            throw new IOException("安装路径无法识别");
        try {
            JSONObject existing = call("status", null);
            if (existing.optBoolean("ready") && BuildIdentity.TOKEN.equals(existing.optString("build")) && apk.equals(existing.optString("apk"))) return;
            call("shutdown", null);
            SystemClock.sleep(150);
        } catch (Exception ignored) {}
        String launch = "export CLASSPATH=" + quote(apk) + "; exec /system/bin/app_process /system/bin --nice-name=io.github.dcl.RootDaemon io.github.dcl.RootDaemon";
        String command = "umask 077; mkdir -p " + quote(BASE) + " && /data/adb/ksu/bin/busybox nohup /system/bin/sh -c " + quote(launch) + " </dev/null >>" + quote(BASE + "/daemon.log") + " 2>&1 &";
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroy(); throw new IOException("Root 授权尚未完成，请授权后重试"); }
        if (process.exitValue() != 0) throw new IOException("请在 KernelSU 中允许音效使用 Root");
        for (int i = 0; i < 60; i++) {
            try {
                JSONObject state = call("status", null);
                if (state.optBoolean("ready") && BuildIdentity.TOKEN.equals(state.optString("build"))) {
                    context.createDeviceProtectedStorageContext().getSharedPreferences("activation", 0).edit().putBoolean("enabled", true).apply();
                    return;
                }
            } catch (Exception ignored) {}
            SystemClock.sleep(100);
        }
        throw new IOException("音效后台未能启动，请确认 Root 授权");
    }
}
