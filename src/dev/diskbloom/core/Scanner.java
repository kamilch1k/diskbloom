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

    private Scanner() {}
}
