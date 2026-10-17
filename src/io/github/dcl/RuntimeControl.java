package io.github.dcl;

import android.content.Context;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONObject;

/** Bounded profile reload of the existing system Spatializer; no effect construction. */
public final class RuntimeControl {
    static final String FP = "samsung/dm1qzcx/dm1q:16/BP4A.251205.006/S9110ZCS8FZF5:user/release-keys";
    private static final String[][] LIBRARIES = {
            {"/system/lib64/libswspatializer_legacy.so", "ea8f708f531f9a996d622df545eec51d23be8adcab63635e4bea2a117c3e0135"},
            {"/system/lib64/libspatializerparamstorage.so", "649c9f11ce3d2682c877a20272dd924d65c83e5500100596880d4ccefe89a1ca"}
    };
    private static Object cachedSpatializer;
    static Context systemContext;

    private RuntimeControl() {}

    static void verify() throws Exception {
        if (!FP.equals(Class.forName("android.os.Build").getField("FINGERPRINT").get(null))) {
            throw new Exception("Unsupported firmware");
        }
        for (String[] library : LIBRARIES) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(library[0])) {
                byte[] block = new byte[32768];
                for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (!library[1].equals(hash.toString())) throw new Exception("Unsupported library " + library[0]);
        }
    }

    static boolean editableProfile(int profile) {
        return profile == 0 || profile == 1 || profile == 2 || profile == 3 || profile == 8;
    }

    static synchronized Object spat() throws Exception {
        if (cachedSpatializer != null) return cachedSpatializer;
        Class<?> looper = Class.forName("android.os.Looper");
        if (looper.getMethod("myLooper").invoke(null) == null) {
            looper.getMethod("prepareMainLooper").invoke(null);
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Object audio = systemContext.getSystemService("audio");
        cachedSpatializer = Class.forName("android.media.AudioManager").getMethod("getSpatializer").invoke(audio);
        return cachedSpatializer;
    }

    static int get(Object spatializer, int parameter) throws Exception {
        Method getter = spatializer.getClass().getMethod("getEffectParameter", int.class, byte[].class);
        byte[] first = new byte[4];
        byte[] second = new byte[4];
        Arrays.fill(first, (byte) 0x5a);
        Arrays.fill(second, (byte) 0xa5);
        getter.invoke(spatializer, parameter, first);
        getter.invoke(spatializer, parameter, second);
        if (!Arrays.equals(first, second)) throw new Exception("Unwritten parameter readback");
        return ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static void profile(Object spatializer, int value) throws Exception {
        if (!editableProfile(value)) throw new Exception("Unsupported main profile");
        spatializer.getClass().getMethod("setEffectParameter", int.class, byte[].class)
                .invoke(spatializer, 0x100, ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        if (get(spatializer, 0x100) != value) throw new Exception("Profile readback mismatch");
    }

    static JSONObject state(Object spatializer) throws Exception {
        boolean enabled = (Boolean) spatializer.getClass().getMethod("isEnabled").invoke(spatializer);
        boolean available = (Boolean) spatializer.getClass().getMethod("isAvailable").invoke(spatializer);
        JSONObject result = new JSONObject().put("enabled", enabled).put("available", available)
                .put("output", 0).put("profile", -1);
        if (!enabled || !available) return result;
        try {
            int output = (Integer) spatializer.getClass().getMethod("getOutput").invoke(spatializer);
            result.put("output", output);
            if (output > 0) result.put("profile", get(spatializer, 0x100));
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalStateException)) throw error;
            result.put("enabled", false).put("output", 0).put("profile", -1);
        }
        return result;
    }

    private static void inspect(JSONObject result, boolean reload) throws Exception {
        Object spatializer = spat();
        JSONObject before = state(spatializer);
        result.put("before", before);
        int original = before.getInt("profile");
        if (!editableProfile(original)) throw new Exception("Unsupported main profile");
        if (!before.getBoolean("enabled") || !before.getBoolean("available") || before.getInt("output") <= 0) {
            throw new Exception("Spatializer is not active");
        }
        if (reload) {
            // The native same-profile setter returns early. Reload the existing
            // instance without calling any enable/render/head-tracking APIs.
            result.put("temporary_profile", original == 3 ? 2 : 3).put("audio_written", true);
            JSONObject reloaded = RuntimeEdit.reload(spatializer, false);
            result.put("after", reloaded.getJSONObject("after")).put("profile_reloaded", true)
                    .put("specific_overrides_reset", true);
        }
        result.put("ok", true);
    }

    public static void main(String[] arguments) throws Exception {
        JSONObject result = new JSONObject().put("effect_created", false).put("audio_written", false);
        int exitCode = 0;
        try {
            if (arguments.length != 1 || (!arguments[0].equals("check") && !arguments[0].equals("reload"))) {
                throw new Exception("Expected check or reload");
            }
            verify();
            if (arguments[0].equals("reload")) {
                try (ProfileLock operation = new ProfileLock()) { inspect(result, true); }
            } else {
                inspect(result, false);
            }
        } catch (Exception error) {
            result.put("ok", false).put("error", RootDaemon.shortError(error));
            exitCode = 4;
        }
        System.out.println(result);
        if (exitCode != 0) System.exit(exitCode);
    }
}
