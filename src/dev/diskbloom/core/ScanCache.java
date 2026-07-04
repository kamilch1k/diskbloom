package dev.diskbloom.core;

import dev.diskbloom.core.Scanner.Node;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists a scanned Node tree to a compact binary file so re-launching can show
 * the previous scan instantly instead of walking the disk again. Paths are
 * reconstructed from parent + name on load, so only names are stored.
 *
 * ponytail: recursive read/write like the scanner; depth-bounded by real path
 * lengths. No versioned schema beyond a magic string — bump it if the format
 * changes and old caches will just be ignored.
 */
public final class ScanCache {

    private static final String MAGIC = "DBLM1";

    public record Cached(Node root, long timestamp) {}

    public static void save(Path file, Node root, long timestamp) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        // write to a temp file then move, so a crash/kill mid-write never leaves a corrupt cache
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeUTF(MAGIC);
            out.writeLong(timestamp);
            out.writeUTF(root.path.toString());
            writeNode(out, root);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Returns null if the file is missing, unreadable, or a different format. */
    public static Cached load(Path file) {
        if (!Files.exists(file)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!MAGIC.equals(in.readUTF())) return null;
            long ts = in.readLong();
            Path rootPath = Paths.get(in.readUTF());
            Node root = readNode(in, null, true, rootPath);
            return new Cached(root, ts);
        } catch (Exception e) {
            return null;
        }
    }

    public record Meta(Path root, long timestamp, long size) {}

    /** Read just the header + root totals (no tree recursion) — cheap enough for a recent-scans list. */
    public static Meta peek(Path file) {
        if (!Files.exists(file)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!MAGIC.equals(in.readUTF())) return null;
            long ts = in.readLong();
            Path root = Paths.get(in.readUTF());
            in.readUTF();               // root node name (skip)
            long size = in.readLong();  // root node aggregate size
            return new Meta(root, ts, size);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeNode(DataOutputStream out, Node node) throws IOException {
        out.writeUTF(node.name);
        out.writeLong(node.size);
        out.writeBoolean(node.dir);
        if (node.dir) {
            List<Node> children = node.children != null ? node.children : List.of();
            out.writeInt(children.size());
            for (Node c : children) writeNode(out, c);
        }
    }

    private static Node readNode(DataInputStream in, Path parentPath, boolean isRoot, Path rootPath) throws IOException {
        String name = in.readUTF();
        long size = in.readLong();
        boolean dir = in.readBoolean();
        Path path = isRoot ? rootPath : parentPath.resolve(name);
        Node node = new Node(name, path, dir);
        node.size = size;
        if (dir) {
            int n = in.readInt();
            node.children = new ArrayList<>(n);
            for (int i = 0; i < n; i++) node.children.add(readNode(in, path, false, rootPath));
        }
        return node;
    }

    // ponytail: one runnable check — save a small tree, load it, assert round-trip + path rebuild.
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Cached c = load(Paths.get(args[0]));
            if (c == null) { System.out.println("load() returned null (missing / corrupt / old format)"); return; }
            System.out.println("root path = " + c.root().path);
            System.out.println("root size = " + c.root().size + "  children = " + (c.root().children != null ? c.root().children.size() : "null (file)"));
            System.out.println("timestamp = " + new java.util.Date(c.timestamp()));
            return;
        }
        Path tmp = Files.createTempFile("dblm-cache", ".bin");
        try {
            Node root = new Node("x", Paths.get("C:\\x"), true);
            root.children = new ArrayList<>();
            Node a = new Node("a.txt", Paths.get("C:\\x\\a.txt"), false);
            a.size = 100;
            Node sub = new Node("sub", Paths.get("C:\\x\\sub"), true);
            sub.children = new ArrayList<>();
            Node b = new Node("b.bin", Paths.get("C:\\x\\sub\\b.bin"), false);
            b.size = 200;
            sub.children.add(b);
            sub.size = 200;
            root.children.add(sub);
            root.children.add(a);
            root.size = 300;

            save(tmp, root, 123L);
            Cached c = load(tmp);
            assert c != null && c.timestamp() == 123L : "timestamp";
            assert c.root().size == 300 && c.root().children.size() == 2 : "root";
            Node reSub = c.root().children.get(0);
            assert reSub.name.equals("sub") && reSub.children.get(0).size == 200 : "sub";
            assert reSub.children.get(0).path.toString().equals("C:\\x\\sub\\b.bin") : reSub.children.get(0).path.toString();
            Meta m = peek(tmp);
            assert m != null && m.timestamp() == 123L && m.size() == 300 : "peek header";
            assert m.root().toString().equals("C:\\x") : "peek root path";
            System.out.println("scancache selfcheck OK");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ScanCache() {}
}
