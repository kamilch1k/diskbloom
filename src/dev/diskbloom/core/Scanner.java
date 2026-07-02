package dev.diskbloom.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walks a directory tree and aggregates sizes into a Node tree, children sorted
 * largest-first. Zero dependencies so it stays trivially testable and reusable
 * under any UI.
 *
 * ponytail: single-threaded logical-size walk. Known ceilings, upgrade when they
 * bite: (1) speed -> parallel ForkJoin walk, then raw NTFS MFT read via FFI for
 * WizTree-class scans; (2) accuracy -> hardlink/junction dedup + on-disk
 * (allocated) size. All are v2 — correct-and-simple first.
 */
public final class Scanner {

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
        boolean isDir = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS);
        Node node = new Node(displayName(root), root, isDir);
        if (!isDir) {
            node.size = fileSize(root);
            return node;
        }
        node.children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path child : ds) {
                if (Files.isSymbolicLink(child)) continue; // skip links: avoids cycles + double counting
                Node c = scan(child);
                node.children.add(c);
                node.size += c.size;
            }
        } catch (IOException | RuntimeException e) {
            // ponytail: unreadable dir (usually perms) counts as 0 and we keep going.
            // Surfacing which paths were skipped is a later feature, not a v0 need.
        }
        node.children.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
        return node;
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String displayName(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString(); // drive root (C:\) has a null file name
    }

    private Scanner() {}
}
