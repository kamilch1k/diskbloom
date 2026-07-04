package dev.diskbloom.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks a directory tree and aggregates sizes into a Node tree, children sorted
 * largest-first. Zero dependencies so it stays trivially testable and reusable
 * under any UI. An optional Progress callback reports running totals during long
 * scans, and an AtomicBoolean allows cancellation.
 *
 * Parallel ForkJoin walk with one stat (readAttributes) per entry — the two
 * things that dominate Windows scan time. ponytail: next speed ceiling is a raw
 * NTFS MFT read via FFI for WizTree-class scans; accuracy ceiling is
 * hardlink/junction dedup + on-disk (allocated) size.
 */
public final class Scanner {

    /** Called periodically during a scan with running totals and the folder in progress. */
    public interface Progress { void update(long files, long bytes, String path); }

    public static final class Node {
        public final String name;
        public final Path path;
        public final boolean dir;
        public long size;            // bytes; for a dir, the aggregate of its subtree
        public List<Node> children;  // null for files

        Node(String name, Path path, boolean dir) {
            this.name = name;
            this.path = path;
            this.dir = dir;
        }
    }

    public static Node scan(Path root) {
        return scan(root, null, null);
    }

    /**
     * Progress and cancel may be null. Returns null if cancelled before completion.
     * Parallel: subdirectories are walked concurrently across a ForkJoin pool, and
     * each entry's attributes are read in a single stat. Same result as a serial walk.
     */
    public static Node scan(Path root, Progress progress, AtomicBoolean cancel) {
        Counters c = new Counters(progress);
        ForkJoinPool pool = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            return pool.invoke(new ScanTask(root, c, cancel));
        } catch (CancellationException e) {
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- live browsing (no full recursion): for the Explorer-style file view ----

    /** Folders before files, then larger before smaller, then by name. */
    public static final Comparator<Node> BROWSE_ORDER =
            Comparator.comparing((Node n) -> !n.dir)
                    .thenComparing(Comparator.comparingLong((Node n) -> n.size).reversed())
                    .thenComparing(n -> n.name.toLowerCase());

    /**
     * A single node for one path, not recursed. Files carry their real size; a
     * directory gets size 0 and null children (unknown until listed or scanned).
     */
    public static Node shallow(Path p) {
        boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
        Node n = new Node(displayName(p), p, isDir);
        if (!isDir) {
            try { n.size = Files.size(p); } catch (IOException e) { /* unreadable -> 0 */ }
        }
        return n;
    }

    /**
     * Populate a directory node's children with its immediate entries as shallow
     * nodes (folders first, then largest-first). A no-op for files. An unreadable
     * directory yields an empty child list, so callers can browse without a scan.
     */
    public static void listChildren(Node n) {
        if (!n.dir) return;
        List<Node> kids = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(n.path)) {
            for (Path child : ds) {
                if (Files.isSymbolicLink(child)) continue; // ponytail: skip links, matches scan()
                kids.add(shallow(child));
            }
        } catch (IOException | RuntimeException e) {
            // unreadable (usually perms) -> empty listing, keep going
        }
        kids.sort(BROWSE_ORDER);
        n.children = kids;
    }

    /** Total bytes under a path, summed without building a Node tree (bounded memory). Skips symlinks. */
    public static long sizeOf(Path root) {
        long[] total = {0};
        sumInto(root, total);
        return total[0];
    }

    private static void sumInto(Path p, long[] total) {
        BasicFileAttributes a = readAttrs(p);
        if (a == null || a.isSymbolicLink()) return;
        if (a.isDirectory()) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) sumInto(c, total);
            } catch (IOException | RuntimeException e) {
                // unreadable dir -> counts as 0, keep going
            }
        } else {
            total[0] += a.size();
        }
    }

    /** One stat per entry — on Windows this is the scan's dominant cost, so read it once. */
    private static BasicFileAttributes readAttrs(Path p) {
        try { return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException | RuntimeException e) { return null; }
    }

    // Shared running totals + throttled progress across all scan threads.
    private static final class Counters {
        final Progress progress;
        final AtomicLong files = new AtomicLong(), bytes = new AtomicLong(), since = new AtomicLong();
        volatile String currentDir = "";
        Counters(Progress progress) { this.progress = progress; }

        void file(long size, String dir) {
            long f = files.incrementAndGet();
            long b = bytes.addAndGet(size);
            if (since.incrementAndGet() >= 8192) {   // report roughly every 8k files, from whichever thread
                since.set(0);
                currentDir = dir;
                if (progress != null) progress.update(f, b, dir);
            }
        }
    }

    // Recursive parallel walk: fork a task per subdirectory, sum files inline.
    private static final class ScanTask extends RecursiveTask<Node> {
        private final Path path;
        private final Counters c;
        private final AtomicBoolean cancel;

        ScanTask(Path path, Counters c, AtomicBoolean cancel) { this.path = path; this.c = c; this.cancel = cancel; }

        @Override protected Node compute() {
            if (cancel != null && cancel.get()) throw new CancellationException();
            BasicFileAttributes at = readAttrs(path);
            boolean isDir = at != null ? at.isDirectory() : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            Node node = new Node(displayName(path), path, isDir);
            if (!isDir) {
                long sz = at != null ? at.size() : 0;
                node.size = sz;
                c.file(sz, node.path.toString());
                return node;
            }
            node.children = new ArrayList<>();
            List<ScanTask> forks = new ArrayList<>();
            String dir = path.toString();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path child : ds) {
                    BasicFileAttributes a = readAttrs(child);
                    if (a == null || a.isSymbolicLink()) continue;   // unreadable or link -> skip
                    if (a.isDirectory()) {
                        forks.add(new ScanTask(child, c, cancel));
                    } else {
                        Node fn = new Node(displayName(child), child, false);
                        fn.size = a.size();
                        node.children.add(fn);
                        node.size += a.size();
                        c.file(a.size(), dir);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable dir (usually perms) -> counts as what we already gathered
            }
            for (ScanTask t : forks) t.fork();
            for (ScanTask t : forks) {
                Node cn = t.join();
                node.children.add(cn);
                node.size += cn.size;
            }
            node.children.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
            return node;
        }
    }

    private static String displayName(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString(); // drive root (C:\) has a null file name
    }

    // self-check: java -ea -cp out dev.diskbloom.core.Scanner
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("dbscan");
        Files.writeString(tmp.resolve("a.txt"), "hello");   // 5 bytes
        Files.createDirectory(tmp.resolve("sub"));
        Node full = scan(tmp);
        assert full.size == 5 : "full size " + full.size;

        Node sh = shallow(tmp);
        assert sh.dir && sh.size == 0 && sh.children == null : "shallow dir is lazy";
        listChildren(sh);
        assert sh.children.size() == 2 : "listed " + sh.children.size();
        assert sh.children.get(0).dir : "folders sort first";           // sub before a.txt
        Node file = shallow(tmp.resolve("a.txt"));
        assert !file.dir && file.size == 5 : "shallow file size " + file.size;
        assert sizeOf(tmp) == 5 : "sizeOf " + sizeOf(tmp);   // matches the full-scan aggregate

        Files.delete(tmp.resolve("a.txt"));
        Files.delete(tmp.resolve("sub"));
        Files.delete(tmp);

        // parallel walk must match the independent serial sum (sizeOf) on a deeper, wider tree
        Path big = Files.createTempDirectory("dbscan-par");
        long expected = 0;
        for (int d = 0; d < 6; d++) {
            Path sub = big.resolve("dir" + d);
            Files.createDirectories(sub.resolve("nested"));
            for (int f = 0; f < 5; f++) {
                byte[] data = new byte[100 * (d + 1) + f];
                Files.write(sub.resolve("f" + f + ".bin"), data);
                Files.write(sub.resolve("nested").resolve("g" + f + ".bin"), data);
                expected += 2L * data.length;
            }
        }
        Node par = scan(big);
        assert par.size == expected : "parallel scan size " + par.size + " != expected " + expected;
        assert par.size == sizeOf(big) : "scan " + par.size + " != sizeOf " + sizeOf(big);
        for (int i = 1; i < par.children.size(); i++)   // children sorted largest-first
            assert par.children.get(i - 1).size >= par.children.get(i).size : "children not sorted largest-first";
        Files.walk(big).sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) { } });

        System.out.println("Scanner self-check OK");
    }

    private Scanner() {}
}
