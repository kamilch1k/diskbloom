package dev.diskbloom.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Walks a directory tree and aggregates sizes into a Node tree, children sorted
 * largest-first. Zero dependencies so it stays trivially testable and reusable
 * under any UI. An optional Progress callback reports running totals during long
 * scans, and an AtomicBoolean allows cancellation.
 *
 * ponytail: single-threaded logical-size walk. Known ceilings, upgrade when they
 * bite: (1) speed -> parallel ForkJoin walk, then raw NTFS MFT read via FFI for
 * WizTree-class scans; (2) accuracy -> hardlink/junction dedup + on-disk size.
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

    /** Progress and cancel may be null. Returns null if cancelled before completion. */
    public static Node scan(Path root, Progress progress, AtomicBoolean cancel) {
        return new Walk(progress, cancel).run(root);
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
        if (Files.isSymbolicLink(p)) return;
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) sumInto(c, total);
            } catch (IOException | RuntimeException e) {
                // unreadable dir -> counts as 0, keep going
            }
        } else {
            try { total[0] += Files.size(p); } catch (IOException e) { /* skip */ }
        }
    }

    private static final class Cancelled extends RuntimeException {}

    private static final class Walk {
        private final Progress progress;
        private final AtomicBoolean cancel;
        private long files, bytes, since;
        private String currentDir = "";

        Walk(Progress progress, AtomicBoolean cancel) {
            this.progress = progress;
            this.cancel = cancel;
        }

        Node run(Path root) {
            try {
                return scan(root);
            } catch (Cancelled c) {
                return null;
            }
        }

        private Node scan(Path path) {
            if (cancel != null && cancel.get()) throw new Cancelled();
            boolean isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            Node node = new Node(displayName(path), path, isDir);
            if (!isDir) {
                node.size = fileSize(path);
                return node;
            }
            currentDir = path.toString();
            node.children = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path child : ds) {
                    if (Files.isSymbolicLink(child)) continue; // skip links: avoids cycles + double counting
                    Node c = scan(child);
                    node.children.add(c);
                    node.size += c.size;
                }
            } catch (Cancelled c) {
                throw c;
            } catch (IOException | RuntimeException e) {
                // unreadable dir (usually perms) -> counts as 0 and we keep going
            }
            node.children.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
            return node;
        }

        private long fileSize(Path p) {
            try {
                long s = Files.size(p);
                files++;
                bytes += s;
                if (++since >= 4096) report();
                return s;
            } catch (IOException e) {
                return 0;
            }
        }

        private void report() {
            since = 0;
            if (progress != null) progress.update(files, bytes, currentDir);
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
        System.out.println("Scanner self-check OK");
    }

    private Scanner() {}
}
