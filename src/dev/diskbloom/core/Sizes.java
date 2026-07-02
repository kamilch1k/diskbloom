package dev.diskbloom.core;

/** Human-readable byte sizes, shared by the CLI and the UI. */
public final class Sizes {

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }

    private Sizes() {}
}
