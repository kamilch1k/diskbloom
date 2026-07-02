package dev.diskbloom;

import dev.diskbloom.core.Scanner;
import dev.diskbloom.core.Scanner.Node;
import dev.diskbloom.core.Sizes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Headless runner for the scan engine: prints the biggest children of a path,
 * plus timing. `--selfcheck` runs an assert-based check of the aggregation.
 * The JavaFX UI reuses Scanner directly; this stays as a quick CLI.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--selfcheck")) {
            selfCheck();
            return;
        }
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        long t0 = System.nanoTime();
        Node tree = Scanner.scan(root);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("%s  -  %s total, scanned in %d ms%n", root, Sizes.human(tree.size), ms);
        int shown = 0;
        if (tree.children != null) {
            for (Node c : tree.children) {
                System.out.printf("  %10s  %s%s%n", Sizes.human(c.size), c.name, c.dir ? "\\" : "");
                if (++shown >= 20) break;
            }
        }
    }

    // ponytail: one runnable check for the non-trivial bit (size aggregation + ordering).
    // Build a temp tree of known sizes, assert the totals. Run with `java -ea`.
    static void selfCheck() throws Exception {
        Path tmp = Files.createTempDirectory("diskbloom-check");
        try {
            Files.write(tmp.resolve("a.bin"), new byte[3000]);
            Path sub = Files.createDirectory(tmp.resolve("sub"));
            Files.write(sub.resolve("b.bin"), new byte[5000]);
            Files.write(sub.resolve("c.bin"), new byte[1000]);

            Node t = Scanner.scan(tmp);
            assert t.size == 9000 : "total should be 9000, was " + t.size;
            assert t.children.get(0).name.equals("sub") : "biggest child should be sub, was " + t.children.get(0).name;
            assert t.children.get(0).size == 6000 : "sub should be 6000, was " + t.children.get(0).size;
            System.out.println("selfcheck OK");
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignore) {}
            });
        }
    }

    private Main() {}
}
