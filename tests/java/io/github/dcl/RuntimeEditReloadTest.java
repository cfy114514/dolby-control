package io.github.dcl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Inject setter/route failures into the production reload target, without Android services. */
public final class RuntimeEditReloadTest {
    private static int passed;
    private interface Check { void run() throws Exception; }

    private static final class Access implements RuntimeEdit.ProfileAccess {
        int current;
        int output = 42;
        int original;
        int failReturn;
        boolean enabled = true;
        boolean available = true;
        boolean changeRoute;
        boolean changeProfile;
        boolean failReadback;
        int resets;
        final List<Integer> writes = new ArrayList<>();

        Access(int profile) { current = original = profile; }
        @Override public boolean sameActiveRoute(int expected) { return enabled && available && output == expected; }
        @Override public int profile() { return current; }
        @Override public void profile(int value) throws Exception {
            writes.add(value);
            if (value == original && failReturn-- > 0) throw new Exception("Injected return failure");
            current = value;
            if (value != original && changeRoute) output++;
            if (value != original && changeProfile) current = 2;
            if (value == original && failReadback) { failReadback = false; throw new Exception("Injected readback failure"); }
        }
        @Override public void resetCommon() throws Exception {
            require(current == original && enabled && available && output == 42, "Common reset before restoration");
            resets++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Check operation) throws Exception {
        try { operation.run(); }
        catch (Exception expected) { return; }
        throw new AssertionError("Expected a guarded failure");
    }

    private static void check(String name, Check operation) throws Exception {
        operation.run();
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] arguments) throws Exception {
        check("normal Spatial Audio reload retains its original target", () -> {
            Access access = new Access(8);
            new RuntimeEdit.ReloadTarget(8, 42).apply(access, true);
            require(access.writes.equals(Arrays.asList(3, 8)) && access.current == 8 && access.resets == 1, access.writes.toString());
        });
        check("voice reload uses Music temporarily and restores voice", () -> {
            Access access = new Access(3);
            new RuntimeEdit.ReloadTarget(3, 42).apply(access, false);
            require(access.writes.equals(Arrays.asList(2, 3)) && access.resets == 0, access.writes.toString());
        });
        check("Auto profile is restored without relying on manual hold maintenance", () -> {
            Access access = new Access(0);
            new RuntimeEdit.ReloadTarget(0, 42).apply(access, false);
            require(access.writes.equals(Arrays.asList(3, 0)) && access.current == 0, access.writes.toString());
        });
        check("failed original setter retries original rather than adopting temporary profile", () -> {
            Access access = new Access(8);
            RuntimeEdit.ReloadTarget target = new RuntimeEdit.ReloadTarget(8, 42);
            access.failReturn = 1;
            fails(() -> target.apply(access, true));
            require(access.current == 3 && access.resets == 0, "Failure did not leave temporary profile");
            target.apply(access, true);
            require(access.writes.equals(Arrays.asList(3, 8, 8)) && access.current == 8 && access.resets == 1, access.writes.toString());
        });
        check("a changed output cannot receive an old route restoration", () -> {
            Access access = new Access(8);
            access.current = 3;
            access.output = 43;
            fails(() -> new RuntimeEdit.ReloadTarget(8, 42).apply(access, true));
            require(access.writes.isEmpty() && access.resets == 0, "Changed route was written");
        });
        check("disabled Dolby is never enabled or written by recovery", () -> {
            Access access = new Access(8);
            access.enabled = false;
            fails(() -> new RuntimeEdit.ReloadTarget(8, 42).apply(access, true));
            require(access.writes.isEmpty() && access.resets == 0, "Disabled route was written");
        });
        check("unavailable Spatializer is never written by recovery", () -> {
            Access access = new Access(8);
            access.available = false;
            fails(() -> new RuntimeEdit.ReloadTarget(8, 42).apply(access, true));
            require(access.writes.isEmpty() && access.resets == 0, "Unavailable route was written");
        });
        check("another selected mode is preserved", () -> {
            Access access = new Access(8);
            access.current = 2;
            fails(() -> new RuntimeEdit.ReloadTarget(8, 42).apply(access, true));
            require(access.writes.isEmpty() && access.current == 2, "Unrelated mode was overwritten");
        });
        check("route change during the temporary write prevents the return setter", () -> {
            Access access = new Access(8);
            access.changeRoute = true;
            fails(() -> new RuntimeEdit.ReloadTarget(8, 42).apply(access, true));
            require(access.writes.equals(Arrays.asList(3)) && access.resets == 0, access.writes.toString());
        });
        check("another mode selected during reload is preserved", () -> {
            Access access = new Access(8);
            access.changeProfile = true;
            fails(() -> new RuntimeEdit.ReloadTarget(8, 42).apply(access, true));
            require(access.writes.equals(Arrays.asList(3)) && access.current == 2 && access.resets == 0, access.writes.toString());
        });
        check("failed readback after an actual return still reloads restored parameters", () -> {
            Access access = new Access(8);
            RuntimeEdit.ReloadTarget target = new RuntimeEdit.ReloadTarget(8, 42);
            access.failReadback = true;
            fails(() -> target.apply(access, true));
            require(access.current == 8, "Return setter did not apply");
            target.apply(access, true);
            require(access.writes.equals(Arrays.asList(3, 8, 3, 8)) && access.resets == 1, access.writes.toString());
        });
        System.out.println(passed + " reload recovery tests passed");
    }
}
