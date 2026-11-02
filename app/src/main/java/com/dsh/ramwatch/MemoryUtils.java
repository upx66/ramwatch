package com.dsh.ramwatch;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;

/** 读系统内存。不依赖任何权限。 */
public final class MemoryUtils {

    public static final class Mem {
        public long total;      // bytes
        public long avail;      // bytes
        public long used;       // bytes
        public long swapTotal;  // bytes
        public long swapFree;   // bytes
        public boolean low;
    }

    private MemoryUtils() {}

    public static Mem read(Context ctx) {
        Mem m = new Mem();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(mi);
            m.total = mi.totalMem;
            m.avail = mi.availMem;
            m.low = mi.lowMemory;
        }
        m.used = Math.max(0L, m.total - m.avail);

        // 交换分区（/proc/meminfo 普通应用可读）
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    m.swapTotal = kb(line) * 1024L;
                } else if (line.startsWith("SwapFree:")) {
                    m.swapFree = kb(line) * 1024L;
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static long kb(String line) {
        try {
            String[] p = line.trim().split("\\s+");
            return Long.parseLong(p[1]);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 大数字：15.0 GB / 980 MB */
    public static String size(long bytes) {
        if (bytes <= 0) return "0 MB";
        double gb = bytes / 1073741824.0;
        if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gb);
        double mb = bytes / 1048576.0;
        if (mb >= 1.0) return String.format(java.util.Locale.US, "%.0f MB", mb);
        return Math.max(1, bytes / 1024) + " KB";
    }

    public static int percent(long used, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(used * 100.0 / total);
    }
}
