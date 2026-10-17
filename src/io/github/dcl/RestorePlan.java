package io.github.dcl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Restore only our own changed values, never a distinguishable later writer's value. */
final class RestorePlan {
    final List<String> arguments = new ArrayList<>();
    private final boolean uncertainWrite;

    RestorePlan(boolean uncertainWrite) {
        this.uncertainWrite = uncertainWrite;
        arguments.add("restore");
    }

    void add(int profile, String key, int before, int requested, int current) throws IOException {
        if (before == requested || before == current) return;
        if (current != requested && (!uncertainWrite || !possiblePrefix(before, requested, current))) {
            throw new IOException("参数已被其他操作改变，未覆盖新的值；请刷新查看当前状态");
        }
        arguments.add(String.valueOf(profile));
        arguments.add(key);
        arguments.add(String.valueOf(current));
        arguments.add(String.valueOf(before));
    }

    boolean isEmpty() { return arguments.size() == 1; }

    private static boolean possiblePrefix(int before, int requested, int current) {
        // The guarded target is little-endian AArch64. A killed/short write can
        // leave only a byte prefix of our requested value. Equal external values
        // are indistinguishable; recovery is best effort, never full isolation.
        for (int bytes = 1; bytes < 4; bytes++) {
            int mask = -1 >>> (32 - bytes * 8);
            if (((before & ~mask) | (requested & mask)) == current) return true;
        }
        return false;
    }
}
