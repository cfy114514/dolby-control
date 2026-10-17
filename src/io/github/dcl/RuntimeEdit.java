package io.github.dcl;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed-record editor; user input never supplies an address, path or command. */
public final class RuntimeEdit {
    static final String BASE = "/data/adb/dolby_control_app/";
    static final String HELPER = BASE + "bin/table-runtime";
    private static final Pattern DSP_SETTER = Pattern.compile(
            "setParam\\(([a-z]{3,4}) = #1\\[(-?[0-9]+)\\]\\)");

    private RuntimeEdit() {}

    private static final class NativeFailure extends Exception {
        final JSONObject receipt;

        NativeFailure(JSONObject receipt) {
            super(receipt.optString("error", "Native audio operation failed"));
            this.receipt = receipt;
        }
    }

    static String run(List<String> arguments, String processIdentity) throws Exception {
        return BoundedProcess.run(arguments, processIdentity, 8000, 2 * 1024 * 1024);
    }

    static JSONObject nativeCall(List<String> arguments, String processIdentity) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(HELPER);
        command.addAll(arguments);
        String output;
        try {
            output = run(command, processIdentity);
        } catch (BoundedProcess.Failure failure) {
            JSONObject receipt;
            try { receipt = new JSONObject(failure.output); }
            catch (Exception malformed) { throw failure; }
            if (!receipt.optBoolean("ok", true)) throw new NativeFailure(receipt);
            throw failure;
        }
        JSONObject result = new JSONObject(output);
        if (!result.getBoolean("ok")) throw new NativeFailure(result);
        return result;
    }

    static String identity(JSONObject table) throws Exception {
        return table.getInt("pid") + ":" + table.getString("process_starttime")
                + ":" + table.getString("boot_id");
    }

    static void save(String name, JSONObject value) throws Exception {
        File target = new File(BASE + name);
        File temporary = new File(BASE + name + ".tmp");
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(temporary.toPath())) {
            throw new Exception("Unsafe receipt path");
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new Exception("Receipt rename failed");
    }

    private static JSONObject restore(JSONObject before, JSONArray edits,
                                      String processIdentity, boolean uncertain) throws Exception {
        JSONObject current = nativeCall(Arrays.asList("status"), processIdentity);
        Map<String, Integer> previous = recordValues(before.getJSONArray("records"));
        Map<String, Integer> now = recordValues(current.getJSONArray("records"));
        RestorePlan plan = new RestorePlan(uncertain);
        for (int index = 0; index < edits.length(); index++) {
            JSONObject edit = edits.getJSONObject(index);
            int profile = edit.getInt("profile");
            String key = edit.getString("key");
            String id = profile + ":" + key;
            if (!previous.containsKey(id) || !now.containsKey(id)) {
                throw new Exception("Recovery record identity changed");
            }
            plan.add(profile, key, previous.get(id), edit.getInt("value"), now.get(id));
        }
        return plan.isEmpty() ? current : nativeCall(plan.arguments, processIdentity);
    }

    private static Map<String, Integer> recordValues(JSONArray records) throws Exception {
        Map<String, Integer> values = new HashMap<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            values.put(record.getInt("profile") + ":" + record.getString("key"), record.getInt("current"));
        }
        return values;
    }

    interface ProfileAccess {
        boolean sameActiveRoute(int output) throws Exception;
        int profile() throws Exception;
        void profile(int value) throws Exception;
        void resetCommon() throws Exception;
    }

    /** Keep the first route/profile even when a failed setter leaves the temporary mode active. */
    static final class ReloadTarget {
        final int profile;
        final int output;

        ReloadTarget(int profile, int output) {
            this.profile = profile;
            this.output = output;
        }

        void apply(ProfileAccess access, boolean resetCommon) throws Exception {
            if (output <= 0 || !access.sameActiveRoute(output)) {
                throw new Exception("Route or Spatializer state changed");
            }
            int temporary = profile == 3 ? 2 : 3;
            int current = access.profile();
            if (current != profile && current != temporary) {
                throw new Exception("Profile changed outside the reload operation");
            }
            Exception failure = null;
            if (current == profile) {
                try { access.profile(temporary); }
                catch (Exception error) { failure = error; }
            }
            // A failed return setter may have left 'temporary' active. Retry the
            // original target directly; never adopt the temporary mode as target.
            if (!access.sameActiveRoute(output)) throw new Exception("Route or Spatializer state changed");
            current = access.profile();
            if (current != profile && current != temporary) {
                throw new Exception("Profile changed outside the reload operation");
            }
            try { access.profile(profile); }
            catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
            if (!access.sameActiveRoute(output) || access.profile() != profile) {
                throw new Exception("Route or Spatializer state changed");
            }
            if (resetCommon) access.resetCommon();
            if (!access.sameActiveRoute(output) || access.profile() != profile) {
                throw new Exception("Route or Spatializer state changed");
            }
        }
    }

    static JSONObject reload(Object spatializer, boolean resetCommon) throws Exception {
        JSONObject before = RuntimeControl.state(spatializer);
        return reload(spatializer, resetCommon,
                new ReloadTarget(before.getInt("profile"), before.getInt("output")));
    }

    private static JSONObject reload(Object spatializer, boolean resetCommon, ReloadTarget target) throws Exception {
        JSONObject before = RuntimeControl.state(spatializer);
        target.apply(new ProfileAccess() {
            @Override public boolean sameActiveRoute(int output) throws Exception {
                JSONObject state = RuntimeControl.state(spatializer);
                return state.getBoolean("enabled") && state.getBoolean("available")
                        && state.getInt("output") == output;
            }
            @Override public int profile() throws Exception { return RuntimeControl.get(spatializer, 0x100); }
            @Override public void profile(int value) throws Exception { RuntimeControl.profile(spatializer, value); }
            @Override public void resetCommon() throws Exception {
                spatializer.getClass().getMethod("setEffectParameter", int.class, byte[].class)
                        .invoke(spatializer, 0x10c, ByteBuffer.allocate(4)
                                .order(ByteOrder.LITTLE_ENDIAN).putInt(0).array());
            }
        }, resetCommon);
        JSONObject after = RuntimeControl.state(spatializer);
        return new JSONObject().put("before", before).put("after", after)
                .put("profile_reloaded", true).put("specific_overrides_reset", true)
                .put("common_parameters_reloaded", resetCommon);
    }

    private static String[] presetLines(JSONArray records, String mode) throws Exception {
        List<String> generated = new ArrayList<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            if (mode.equals("on") && !record.getBoolean("is_flag")) continue;
            generated.add(record.getInt("profile") + "\t" + record.getString("key")
                    + "\t" + (mode.equals("on") ? 0 : record.getInt("original")));
        }
        return generated.toArray(new String[0]);
    }

    private static Map<String, Integer> checkPreconditions(JSONObject expected,
            JSONObject table, JSONObject active) throws Exception {
        Map<String, Integer> values = new HashMap<>();
        if (expected == null) return values;
        if (expected.has("expected_table_identity")
                && !identity(table).equals(expected.getString("expected_table_identity"))) {
            throw new Exception("音效后台已重启，请刷新后再调整");
        }
        // Inactive profiles are still editable, but an active route must keep the
        // profile the user was editing when the page was loaded.
        if (expected.has("expected_profile") && active.getInt("profile") >= 0
                && active.getInt("profile") != expected.getInt("expected_profile")) {
            throw new Exception("播放档位已改变，请刷新后再调整");
        }
        JSONArray records = expected.optJSONArray("expected_records");
        if (records == null) return values;
        if (records.length() < 1 || records.length() > 128) {
            throw new Exception("Invalid expected record count");
        }
        Map<String, Integer> actual = new HashMap<>();
        JSONArray current = table.getJSONArray("records");
        for (int index = 0; index < current.length(); index++) {
            JSONObject record = current.getJSONObject(index);
            actual.put(record.getInt("profile") + ":" + record.getString("key"),
                    record.getInt("current"));
        }
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            String key = record.getInt("profile") + ":" + record.getString("key");
            int value = record.getInt("current");
            if (values.put(key, value) != null || !actual.containsKey(key)
                    || actual.get(key) != value) {
                throw new Exception("参数已改变，请刷新后再调整");
            }
        }
        return values;
    }

    public static void main(String[] arguments) throws Exception {
        try (ProfileLock operation = new ProfileLock()) {
            JSONObject value = run(arguments);
            System.out.println(value);
            if (!value.optBoolean("ok")) System.exit(4);
        }
    }

    static JSONObject run(String[] arguments) throws Exception {
        return run(arguments, null);
    }

    /** Caller holds ProfileLock until any rollback and receipt writes have finished. */
    static JSONObject run(String[] arguments, JSONObject preconditions) throws Exception {
        JSONObject result = new JSONObject().put("ok", false);
        JSONObject before = null;
        Object spatializer = null;
        boolean changed = false;
        boolean needsReload = false;
        boolean common = false;
        boolean nativeCompleted = false;
        JSONArray edits = new JSONArray();
        ReloadTarget reloadTarget = null;
        String processIdentity = null;
        try {
            if (arguments.length != 1 || arguments[0].length() > 32768
                    || !arguments[0].matches("[A-Za-z0-9+/=]+")) {
                throw new Exception("Expected bounded base64 edit batch");
            }
            String mode = arguments[0].equals("on") || arguments[0].equals("off")
                    ? arguments[0] : "custom";
            String[] lines = mode.equals("custom")
                    ? new String(Base64.getDecoder().decode(arguments[0]), StandardCharsets.UTF_8)
                            .trim().split("\n")
                    : new String[0];
            if (mode.equals("custom") && (lines.length < 1 || lines.length > 128)) {
                throw new Exception("Invalid edit count");
            }
            RuntimeControl.verify();
            before = nativeCall(Arrays.asList("status"), null);
            processIdentity = identity(before);
            JSONArray records = before.getJSONArray("records");
            if (!mode.equals("custom")) lines = presetLines(records, mode);
            spatializer = RuntimeControl.spat();
            JSONObject active = RuntimeControl.state(spatializer);
            reloadTarget = new ReloadTarget(active.getInt("profile"), active.getInt("output"));
            Map<String, Integer> expected = checkPreconditions(preconditions, before, active);
            boolean conditional = preconditions != null && preconditions.has("expected_records");
            int profile = active.getInt("profile");
            boolean rendering = RuntimeControl.editableProfile(profile)
                    && active.getBoolean("enabled") && active.getBoolean("available")
                    && active.getInt("output") > 0;
            List<String> set = new ArrayList<>();
            set.add("set");
            Set<String> seen = new HashSet<>();
            for (String line : lines) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 3 || !fields[0].matches("-1|[0-3]|8")
                        || !fields[1].matches("[a-z][a-z0-9-]{2,63}")
                        || !fields[2].matches("-?[0-9]{1,4}")) {
                    throw new Exception("Invalid edit record");
                }
                int scope = Integer.parseInt(fields[0]);
                int value = Integer.parseInt(fields[2]);
                String key = fields[1];
                String id = scope + ":" + key;
                if (!seen.add(id)) throw new Exception("Duplicate runtime target " + id);
                if (conditional && !expected.containsKey(id)) {
                    throw new Exception("Missing expected runtime target " + id);
                }
                JSONObject found = null;
                for (int index = 0; index < records.length(); index++) {
                    JSONObject record = records.getJSONObject(index);
                    if (record.getInt("profile") == scope && record.getString("key").equals(key)) {
                        found = record;
                        break;
                    }
                }
                if (found == null || value < found.getInt("minimum")
                        || value > found.getInt("maximum")) {
                    throw new Exception("Unsupported target or value " + id);
                }
                set.add(fields[0]);
                set.add(key);
                set.add(String.valueOf(value));
                edits.put(new JSONObject().put("profile", scope).put("key", key)
                        .put("dsp_key", found.getString("dsp_key")).put("value", value));
                needsReload |= rendering && (scope == profile || scope == -1);
                common |= scope == -1;
            }
            save("edit-before.json", before);
            String mark = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            // A child can write successfully and then fail, be killed, or return
            // malformed output. Any failure after launch needs a guarded rollback.
            changed = true;
            JSONObject applied;
            try {
                applied = nativeCall(set, processIdentity);
            } catch (NativeFailure failure) {
                // The native transaction already decided which values it can
                // safely roll back. A second Java snapshot write would override
                // any later writer that native deliberately preserved.
                changed = false;
                result.put("native_receipt", failure.receipt)
                        .put("rollback_complete", failure.receipt.optBoolean("rollback_complete"));
                throw failure;
            }
            nativeCompleted = true;
            if (!processIdentity.equals(identity(applied))) throw new Exception("Audio process changed");
            JSONObject now = RuntimeControl.state(spatializer);
            if (rendering && (now.getInt("profile") != profile
                    || now.getInt("output") != active.getInt("output")
                    || !now.getBoolean("enabled"))) {
                needsReload = false;
                throw new Exception("Route/profile changed before reload; table restoration only");
            }
            JSONObject reload = needsReload ? reload(spatializer, common, reloadTarget)
                    : new JSONObject().put("profile_reloaded", false)
                            .put("notice", "Target profile is inactive; stored for its next activation");
            String log = needsReload ? run(Arrays.asList("/system/bin/logcat", "-b", "main", "-d",
                    "-v", "threadtime", "--pid=" + before.getInt("pid"), "-T", mark), null) : "";
            JSONObject values = new JSONObject();
            Matcher matcher = DSP_SETTER.matcher(log);
            while (matcher.find()) values.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            boolean verified = needsReload;
            for (int index = 0; index < edits.length(); index++) {
                JSONObject edit = edits.getJSONObject(index);
                if (edit.getInt("profile") == profile || edit.getInt("profile") == -1) {
                    String key = edit.getString("dsp_key");
                    verified &= values.has(key) && values.getInt(key) == edit.getInt("value");
                }
            }
            result.put("ok", true).put("mode", mode)
                    .put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()))
                    .put("table", applied).put("reload", reload).put("requested", edits)
                    .put("dsp_last_setters", values).put("dsp_setters_observed", verified)
                    .put("notice", "GLOBAL 参数影响所有档位/输出；DDP 会强制 dvle=0。重载清除六项临时覆盖值。");
            save("neutral-last.json", result);
            new File(BASE + "edit-before.json").delete();
            changed = false;
        } catch (Exception error) {
            result.put("ok", false).put("error", RootDaemon.shortError(error));
            if (changed && before != null) {
                try {
                    if (error instanceof BoundedProcess.TerminationFailure) throw error;
                    result.put("rollback_table", restore(before, edits, processIdentity, !nativeCompleted));
                    if (needsReload && spatializer != null && reloadTarget != null) {
                        result.put("rollback_reload", reload(spatializer, common, reloadTarget));
                    }
                    result.put("rollback_complete", true);
                } catch (Exception recovery) {
                    result.put("rollback_complete", false).put("rollback_error", RootDaemon.shortError(recovery));
                }
            }
            if (changed || result.has("native_receipt")) {
                try {
                    save("edit-recovery.json", result);
                } catch (Exception receiptError) {
                    result.put("receipt_error", RootDaemon.shortError(receiptError));
                }
            }
        }
        return result;
    }
}
