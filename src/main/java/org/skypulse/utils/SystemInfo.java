package org.skypulse.utils;


import java.io.File;

public final class SystemInfo {
    private SystemInfo(){}

    public static long freeDiskBytes(String path) {
        File f = new File(path);
        if (!f.exists()) return -1;
        return f.getUsableSpace();
    }

    public static double freeDiskPercent(String path) {
        File f = new File(path);
        if (!f.exists()) return -1;
        long usable = f.getUsableSpace();
        long total = f.getTotalSpace();
        if (total <= 0) return -1;
        return (usable / (double) total) * 100.0;
    }
}