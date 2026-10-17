package io.github.dcl;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.dcl.app.BuildIdentity;
import io.github.dcl.app.RootClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single application-owned root service; no code is injected into the audio server. */
public final class RootDaemon {
    static final String APP = "io.github.dcl.app";
    static final String SOUND_ALIVE = "com.sec.android.app.soundalive";
    static final String BASE = "/data/adb/dolby_control_app/";
    private static final long[] PLAYBACK_RETRIES = {0, 80, 250, 800};
    private static Handler handler;
    private static Object spatializer;
    private static Context context;
    private static JSONObject policy = new JSONObject();
    private static JSONObject table;
    private static String apk;
    private static int ownerUid;
    private static int soundAliveUid;
    private static volatile boolean stopping;
    private static boolean tableRestorePending;
    private static boolean tableStatusKnown;
    private static LocalServerSocket server;
    private static long lastProbe;
    // Bound open sockets as well as executing work. A fixed pool alone has an
    // unbounded queue and can retain an arbitrary number of accepted descriptors.
    private static final ThreadPoolExecutor CLIENTS = new ThreadPoolExecutor(
            3, 3, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
    private static final Runnable PLAYBACK_REASSERT = () -> {
        try { reassert(); } catch (Exception ignored) {}
    };

    private RootDaemon() {}

    static void safeDirectory(File path) throws Exception {
        if (!path.getCanonicalPath().equals(path.getAbsolutePath())) {
            throw new IOException("Unsafe service directory");
        }
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("Cannot create service directory");
        path.setReadable(false, false);
        path.setWritable(false, false);
        path.setExecutable(false, false);
        path.setReadable(true, true);
        path.setWritable(true, true);
        path.setExecutable(true, true);
    }

    private static void persist(JSONObject next) throws Exception {
        File target = new File(BASE + "policy.json");
        File temporary = new File(BASE + "policy.new");
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(temporary.toPath())) {
            throw new IOException("Unsafe policy path");
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(next.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        temporary.setReadable(false, false);
        temporary.setWritable(false, false);
        temporary.setReadable(true, true);
        temporary.setWritable(true, true);
        if (!temporary.renameTo(target)) throw new IOException("Cannot save sound settings");
        // Failed persistence must not silently change the in-memory manual rule.
        policy = next;
    }

    private static void installHelper() throws Exception {
        safeDirectory(new File(BASE + "bin"));
        File helper = new File(RuntimeEdit.HELPER);
        if (Files.isSymbolicLink(helper.toPath())) throw new IOException("Unsafe helper path");
        byte[] bytes;
        try (ZipFile zip = new ZipFile(apk);
             InputStream input = zip.getInputStream(zip.getEntry("assets/backend/table-runtime"))) {
            bytes = input.readAllBytes();
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        if (!BuildIdentity.NATIVE_SHA256.equals(digest.toString())) {
            throw new IOException("Audio adapter identity mismatch");
        }
        try (FileOutputStream output = new FileOutputStream(helper)) {
            output.write(bytes);
            output.getFD().sync();
        }
        helper.setReadable(false, false);
        helper.setWritable(false, false);
        helper.setExecutable(false, false);
        helper.setReadable(true, true);
        helper.setWritable(true, true);
        helper.setExecutable(true, true);
    }

    static String shortError(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static boolean active(JSONObject state) {
        return state.optBoolean("enabled") && state.optBoolean("available")
                && state.optInt("output") > 0 && state.optInt("profile", -1) >= 0;
    }

    private static int selected() { return policy.optInt("selection", -1); }

    private static String profileEnhancement(int profile) throws Exception {
        if (table == null || !tableStatusKnown || !RuntimeControl.editableProfile(profile)) return "unknown";
        boolean neutral = true;
        boolean original = true;
        boolean hasFlags = false;
        JSONArray records = table.getJSONArray("records");
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            int scope = record.getInt("profile");
            if (scope != profile && scope != -1) continue;
            int value = record.getInt("current");
            if (record.getBoolean("is_flag")) {
                hasFlags = true;
                neutral &= value == 0;
            }
            original &= value == record.getInt("original");
        }
        return !hasFlags ? "unknown" : neutral ? "neutral" : original ? "preset" : "custom";
    }

    private static JSONObject snapshot() throws Exception {
        JSONObject state = RuntimeControl.state(spatializer);
        int profile = state.optInt("profile", -1);
        int displayProfile = RuntimeControl.editableProfile(profile) ? profile
                : RuntimeControl.editableProfile(selected()) ? selected() : 2;
        JSONObject result = new JSONObject().put("ok", true).put("ready", true)
                .put("build", BuildIdentity.TOKEN).put("apk", apk).put("active", active(state))
                .put("profile", profile).put("selected_profile", selected())
                .put("enhancement", profileEnhancement(displayProfile))
                .put("enhancement_supported", table != null && RuntimeControl.editableProfile(profile))
                .put("enabled", state.optBoolean("enabled")).put("available", state.optBoolean("available"));
        if (table != null) result.put("table_identity", RuntimeEdit.identity(table));
        return result;
    }

    private static void rememberTable(JSONObject fresh) throws Exception {
        // Reflect the actual table even if a later disk write fails.
        table = fresh;
        tableStatusKnown = true;
        JSONArray edits = new JSONArray();
        JSONArray records = fresh.getJSONArray("records");
        boolean neutral = true;
        boolean original = true;
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            int value = record.getInt("current");
            edits.put(new JSONObject().put("profile", record.getInt("profile"))
                    .put("key", record.getString("key")).put("value", value));
            if (record.getBoolean("is_flag")) neutral &= value == 0;
            original &= value == record.getInt("original");
        }
        String enhancement = neutral ? "neutral" : original ? "preset" : "custom";
        persist(new JSONObject(policy.toString()).put("edits", edits).put("enhancement", enhancement));
        tableRestorePending = false;
    }

    private static JSONObject edits(JSONArray values) throws Exception { return edits(values, null); }

    private static JSONObject edits(JSONArray values, JSONObject preconditions) throws Exception {
        if (values.length() < 1 || values.length() > 128) throw new IOException("调整数量不正确");
        StringBuilder batch = new StringBuilder();
        for (int index = 0; index < values.length(); index++) {
            JSONObject edit = values.getJSONObject(index);
            int profile = edit.getInt("profile");
            int value = edit.getInt("value");
            String key = edit.getString("key");
            if ((profile != -1 && !RuntimeControl.editableProfile(profile))
                    || !key.matches("[a-z][a-z0-9-]{2,63}")) {
                throw new IOException("不支持的音效参数");
            }
            batch.append(profile).append('\t').append(key).append('\t').append(value).append('\n');
        }
        String input = Base64.getEncoder().encodeToString(batch.toString().getBytes(StandardCharsets.UTF_8));
        try (ProfileLock lock = new ProfileLock()) {
            return RuntimeEdit.run(new String[]{input}, preconditions);
        }
    }

    private static boolean sameAudioProcess() {
        if (table == null) return false;
        try {
            String stat = Files.readString(Path.of("/proc/" + table.getInt("pid") + "/stat"));
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return fields[19].equals(table.getString("process_starttime"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void restoreAfterAudioRestart() {
        try {
            boolean same = sameAudioProcess();
            if (same && !tableRestorePending) return;
            if (!same) tableRestorePending = true;
            if (SystemClock.elapsedRealtime() - lastProbe < 3000) return;
            lastProbe = SystemClock.elapsedRealtime();
            JSONArray desired = policy.optJSONArray("edits");
            if (desired != null && desired.length() > 0) {
                // RuntimeEdit already takes a guarded native snapshot. A separate
                // status child here added work without participating in the edit.
                JSONObject result = edits(desired);
                if (!result.optBoolean("ok")) throw new IOException(result.optString("error"));
                rememberTable(result.getJSONObject("table"));
            } else {
                rememberTable(RuntimeEdit.nativeCall(Arrays.asList("status"), null));
            }
            tableRestorePending = false;
        } catch (Exception error) {
            System.err.println("Restore pending: " + shortError(error));
        }
    }

    private static boolean dolbyEnabled() {
        // Query the live provider, not cached preference XML; never turn Dolby on.
        try { return DolbyPreference.mode() != 5; }
        catch (Exception ignored) { return false; }
    }

    private static JSONObject reassert() throws Exception {
        JSONObject before = RuntimeControl.state(spatializer);
        int goal = selected();
        if (goal > 0 && active(before) && before.optInt("profile", -1) != goal && dolbyEnabled()) {
            try (ProfileLock lock = new ProfileLock()) {
                JSONObject fresh = RuntimeControl.state(spatializer);
                if (active(fresh) && dolbyEnabled() && selected() == goal) {
                    RuntimeControl.profile(spatializer, goal);
                }
            }
        }
        return snapshot();
    }

    private static JSONObject settingsReply() throws Exception {
        JSONObject state = snapshot();
        int profile = state.getInt("profile");
        if (profile < 0) profile = RuntimeControl.editableProfile(selected()) ? selected() : 2;
        return state.put("profile", profile).put("records", table.getJSONArray("records"));
    }

    private static JSONObject dispatch(String action, JSONObject arguments, boolean owner) throws Exception {
        switch (action) {
            case "status": return snapshot();
            case "manual": {
                int profile = arguments.getInt("profile");
                if (!RuntimeControl.editableProfile(profile)) throw new IOException("不支持的档位");
                persist(new JSONObject(policy.toString()).put("selection", profile));
                return reassert();
            }
            case "reassert": return reassert();
            default:
                if (!owner) throw new SecurityException("Operation is private to the settings app");
        }
        if (action.equals("settings")) {
            tableStatusKnown = false;
            table = RuntimeEdit.nativeCall(Arrays.asList("status"), null);
            tableStatusKnown = true;
            return settingsReply();
        }
        if (action.equals("enhancement") || action.equals("edit")) {
            JSONObject result;
            if (action.equals("enhancement")) {
                String mode = arguments.getString("mode");
                if (!mode.equals("neutral") && !mode.equals("preset")) {
                    throw new IOException("不支持的增强选项");
                }
                try (ProfileLock lock = new ProfileLock()) {
                    result = RuntimeEdit.run(new String[]{mode.equals("neutral") ? "on" : "off"});
                }
            } else {
                result = edits(arguments.getJSONArray("edits"), arguments);
            }
            if (!result.optBoolean("ok")) {
                if (!result.optBoolean("rollback_complete", true)) tableStatusKnown = false;
                return result;
            }
            rememberTable(result.getJSONObject("table"));
            JSONObject state = action.equals("edit") ? settingsReply() : snapshot();
            JSONObject reload = result.optJSONObject("reload");
            return state.put("applied", true)
                    .put("pending", reload == null || !reload.optBoolean("profile_reloaded"))
                    .put("dsp_observed", result.optBoolean("dsp_setters_observed"));
        }
        if (action.equals("shutdown")) {
            stopping = true;
            handler.postDelayed(() -> System.exit(0), 150);
            return new JSONObject().put("ok", true);
        }
        throw new IOException("不支持的操作");
    }

    private static boolean authorizedOwner(LocalSocket socket) throws Exception {
        Credentials peer = socket.getPeerCredentials();
        int uid = peer.getUid();
        if (uid == ownerUid || uid == 0) return true;
        if (uid != soundAliveUid) throw new SecurityException("Untrusted caller");
        String name = new String(Files.readAllBytes(Path.of("/proc/" + peer.getPid() + "/cmdline")),
                StandardCharsets.UTF_8).split("\u0000", 2)[0];
        if (!name.equals(SOUND_ALIVE)) throw new SecurityException("Unexpected system caller");
        return false;
    }

    private static int requestRemaining(long deadline) throws IOException {
        long milliseconds = deadline - SystemClock.elapsedRealtime();
        if (milliseconds <= 0) throw new IOException("音效操作已过期，请重试");
        return (int) Math.min(milliseconds, Integer.MAX_VALUE);
    }

    private static void serve(LocalSocket socket, long acceptedAt) {
        try (LocalSocket client = socket) {
            long serverDeadline = acceptedAt + 18000;
            boolean owner = authorizedOwner(client);
            JSONObject request = new JSONObject(BoundedLine.read(client.getInputStream(),
                    32768, Math.min(5000, requestRemaining(serverDeadline)), client::setSoTimeout));
            // elapsedRealtime is shared by Android processes. Include socket-pool
            // queue time, not just Handler queue time, in the request's lifetime.
            long deadline = Math.min(serverDeadline,
                    request.optLong("deadline_elapsed_ms", serverDeadline));
            requestRemaining(deadline);
            String action = request.getString("action");
            JSONObject arguments = request.optJSONObject("args");
            RequestTask<JSONObject> task = new RequestTask<>(() ->
                    dispatch(action, arguments == null ? new JSONObject() : arguments, owner),
                    deadline, SystemClock::elapsedRealtime);
            if (!handler.post(task)) throw new IOException("Audio service is stopping");
            JSONObject result;
            try {
                result = task.await(requestRemaining(deadline), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                // Do not leave an expired mutation waiting behind a slow helper.
                // Never interrupt a transaction that has already started: it must
                // finish its guarded rollback before the next operation can run.
                handler.removeCallbacks(task);
                result = new JSONObject().put("ok", false)
                        .put("error", "音效操作超时，请刷新查看当前状态");
            } catch (InterruptedException error) {
                handler.removeCallbacks(task);
                Thread.currentThread().interrupt();
                throw error;
            } catch (ExecutionException error) {
                result = new JSONObject().put("ok", false).put("error", shortError(error.getCause()));
            }
            client.getOutputStream().write((result.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (Exception error) {
            System.err.println("Request rejected: " + shortError(error));
        }
    }

    private static void listen() {
        while (!stopping) {
            LocalSocket socket = null;
            try {
                socket = server.accept();
                long acceptedAt = SystemClock.elapsedRealtime();
                // Reject foreign UIDs before they occupy a worker or queue slot.
                int uid = socket.getPeerCredentials().getUid();
                if (uid != 0 && uid != ownerUid && uid != soundAliveUid) {
                    socket.close();
                    continue;
                }
                LocalSocket accepted = socket;
                CLIENTS.execute(() -> serve(accepted, acceptedAt));
            } catch (Exception error) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                if (!stopping && !(error instanceof RejectedExecutionException)) {
                    System.err.println(shortError(error));
                }
            }
        }
    }

    private static void scheduleMaintenance() {
        handler.postDelayed(new Runnable() {
            int cycles;
            @Override public void run() {
                try {
                    restoreAfterAudioRestart();
                    reassert();
                    if (++cycles % 30 == 0
                            && !apk.equals(context.getPackageManager().getApplicationInfo(APP, 0).sourceDir)) {
                        System.exit(0);
                    }
                } catch (Exception ignored) {}
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    public static void main(String[] arguments) throws Exception {
        if (android.os.Process.myUid() != 0 || arguments.length != 0) {
            throw new SecurityException("Root service only");
        }
        safeDirectory(new File(BASE));
        File singletonPath = new File(BASE + "daemon.lock");
        if (Files.isSymbolicLink(singletonPath.toPath())) throw new IOException("Unsafe service lock");
        try (RandomAccessFile singleton = new RandomAccessFile(singletonPath, "rw");
             FileLock lock = singleton.getChannel().tryLock()) {
            if (lock == null) return;
            RuntimeControl.verify();
            spatializer = RuntimeControl.spat();
            context = RuntimeControl.systemContext;
            android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(APP, 0);
            ownerUid = info.uid;
            apk = info.sourceDir;
            soundAliveUid = context.getPackageManager().getApplicationInfo(SOUND_ALIVE, 0).uid;
            if (!apk.equals(System.getenv("CLASSPATH"))) {
                throw new SecurityException("Installed application identity changed");
            }
            installHelper();
            File saved = new File(BASE + "policy.json");
            if (saved.isFile() && !Files.isSymbolicLink(saved.toPath()) && saved.length() < 65536) {
                policy = new JSONObject(Files.readString(saved.toPath()));
            }
            tableRestorePending = policy.has("edits");
            handler = new Handler(Looper.getMainLooper());
            try {
                table = RuntimeEdit.nativeCall(Arrays.asList("status"), null);
                tableStatusKnown = true;
                if (!policy.has("edits")) rememberTable(table);
            } catch (Exception error) {
                System.err.println(shortError(error));
            }
            snapshot();
            server = new LocalServerSocket(RootClient.SOCKET);
            Thread listener = new Thread(RootDaemon::listen, "dcl-control");
            listener.setDaemon(true);
            listener.start();
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations) {
                    handler.removeCallbacks(PLAYBACK_REASSERT);
                    for (long delay : PLAYBACK_RETRIES) handler.postDelayed(PLAYBACK_REASSERT, delay);
                }
            }, handler);
            scheduleMaintenance();
            Looper.loop();
        }
    }
}
