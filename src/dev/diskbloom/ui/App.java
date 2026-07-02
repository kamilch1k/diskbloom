package dev.diskbloom.ui;

import dev.diskbloom.core.Scanner;
import dev.diskbloom.core.Scanner.Node;
import dev.diskbloom.core.Sizes;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * The diskbloom window: pick a folder, scan it off the UI thread, and render a
 * squarified treemap. Click a rectangle to drill in, hover for details.
 *
 * Launch with -Ddiskbloom.shot=out.png to render one folder and export the
 * treemap to a PNG (used for previews and headless verification).
 */
public class App extends Application {

    private final Canvas canvas = new Canvas();
    private final Label crumb = new Label("");
    private final Label status = new Label("Open a folder to begin.");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button upBtn = new Button("↑ Up");

    private final Deque<Node> stack = new ArrayDeque<>(); // root .. current (peek = current)
    private final List<Tile> tiles = new ArrayList<>();   // current layout, for hit-testing

    private final String shotPath = System.getProperty("diskbloom.shot"); // non-null => export + exit
    private Scene scene;

    private record Tile(Node node, double x, double y, double w, double h, Color color) {}

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Button openBtn = new Button("Open folder…");
        openBtn.setOnAction(e -> chooseAndScan(stage));
        upBtn.setOnAction(e -> { if (stack.size() > 1) { stack.pop(); render(); } });
        upBtn.setDisable(true);
        spinner.setVisible(false);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);

        crumb.setStyle("-fx-text-fill: #e6e6e6; -fx-font-size: 13px;");
        HBox.setHgrow(crumb, Priority.ALWAYS);
        HBox bar = new HBox(10, openBtn, upBtn, spinner, crumb);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: #2b2b2b;");

        Pane holder = new Pane(canvas);
        holder.setStyle("-fx-background-color: #1e1e1e;");
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener(o -> render());
        canvas.heightProperty().addListener(o -> render());
        canvas.setOnMouseClicked(e -> {
            Tile t = tileAt(e.getX(), e.getY());
            if (t != null && t.node().dir && t.node().children != null && !t.node().children.isEmpty()) {
                stack.push(t.node());
                render();
            }
        });
        canvas.setOnMouseMoved(e -> {
            Tile t = tileAt(e.getX(), e.getY());
            Node cur = stack.peek();
            if (t != null && cur != null) {
                double pct = cur.size > 0 ? 100.0 * t.node().size / cur.size : 0;
                status.setText(String.format("%s      %s   (%.1f%%)", t.node().path, Sizes.human(t.node().size), pct));
            }
        });

        status.setStyle("-fx-text-fill: #bdbdbd; -fx-font-size: 12px;");
        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-background-color: #2b2b2b;");

        BorderPane root = new BorderPane();
        root.setTop(bar);
        root.setCenter(holder);
        root.setBottom(statusBar);

        scene = new Scene(root, 1040, 700, Color.web("#1e1e1e"));
        stage.setTitle("diskbloom");
        stage.setScene(scene);
        stage.show();

        // scan the folder given on the command line, else the user's home, for an instant first view
        List<String> params = getParameters().getRaw();
        scan(params.isEmpty()
                ? Paths.get(System.getProperty("user.home"))
                : Paths.get(params.get(0)));
    }

    private void chooseAndScan(Stage stage) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose a folder to scan");
        Node cur = stack.peek();
        File init = cur != null ? cur.path.toFile() : new File(System.getProperty("user.home"));
        if (init.isDirectory()) dc.setInitialDirectory(init);
        File dir = dc.showDialog(stage);
        if (dir != null) scan(dir.toPath());
    }

    private void scan(Path root) {
        Task<Node> task = new Task<>() {
            @Override protected Node call() {
                return Scanner.scan(root);
            }
        };
        spinner.setVisible(true);
        crumb.setText("Scanning " + root + " …");
        status.setText("");
        task.setOnSucceeded(e -> {
            spinner.setVisible(false);
            stack.clear();
            stack.push(task.getValue());
            render();
            if (shotPath != null) Platform.runLater(this::exportAndExit);
        });
        task.setOnFailed(e -> {
            spinner.setVisible(false);
            crumb.setText("Scan failed: " + task.getException());
        });
        Thread th = new Thread(task, "diskbloom-scan");
        th.setDaemon(true);
        th.start();
    }

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth(), H = canvas.getHeight();
        g.setFill(Color.web("#1e1e1e"));
        g.fillRect(0, 0, W, H);
        tiles.clear();

        Node cur = stack.peek();
        upBtn.setDisable(stack.size() <= 1);
        if (cur == null) return;
        crumb.setText(breadcrumb() + "      " + Sizes.human(cur.size));

        if (cur.children == null || cur.children.isEmpty() || W < 2 || H < 2) return;

        List<Node> kids = new ArrayList<>();
        for (Node n : cur.children) if (n.size > 0) kids.add(n);
        if (kids.isEmpty()) return;

        double total = 0;
        for (Node n : kids) total += n.size;
        double scale = (W * H) / total;
        double[] areas = new double[kids.size()];
        for (int i = 0; i < kids.size(); i++) areas[i] = kids.get(i).size * scale;

        squarify(kids, areas, 0, 0, W, H, tiles);
        for (Tile t : tiles) draw(g, t);
    }

    private void draw(GraphicsContext g, Tile t) {
        g.setFill(t.color());
        g.fillRect(t.x(), t.y(), t.w(), t.h());
        g.setStroke(Color.web("#1e1e1e"));
        g.setLineWidth(1.5);
        g.strokeRect(t.x(), t.y(), t.w(), t.h());

        if (t.w() > 46 && t.h() > 22) {
            g.setFill(luminance(t.color()) > 0.55 ? Color.web("#141414") : Color.web("#f6f6f6"));
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
            g.fillText(clip(t.node().name + (t.node().dir ? "\\" : ""), t.w() - 12), t.x() + 6, t.y() + 17);
            if (t.h() > 38) {
                g.setFont(Font.font("Segoe UI", 11));
                g.fillText(Sizes.human(t.node().size), t.x() + 6, t.y() + 32);
            }
        }
    }

    // Squarified treemap (Bruls, Huizing, van Wijk): pack children into rows that
    // keep rectangle aspect ratios near 1, so nothing degenerates into slivers.
    private void squarify(List<Node> kids, double[] areas, double x, double y, double w, double h, List<Tile> out) {
        int start = 0, n = kids.size();
        while (start < n) {
            double side = Math.min(w, h);
            if (side <= 0) break;
            double sum = areas[start], mn = areas[start], mx = areas[start];
            int count = 1;
            double best = worst(sum, mx, mn, side);
            while (start + count < n) {
                double a = areas[start + count];
                double nsum = sum + a, nmx = Math.max(mx, a), nmn = Math.min(mn, a);
                double cand = worst(nsum, nmx, nmn, side);
                if (cand <= best) { sum = nsum; mx = nmx; mn = nmn; best = cand; count++; }
                else break;
            }
            double rowArea = 0;
            for (int i = start; i < start + count; i++) rowArea += areas[i];
            if (w >= h) {
                double rowW = rowArea / h, cy = y;
                for (int i = start; i < start + count; i++) {
                    double th = areas[i] / rowW;
                    out.add(new Tile(kids.get(i), x, cy, rowW, th, colorFor(i)));
                    cy += th;
                }
                x += rowW; w -= rowW;
            } else {
                double rowH = rowArea / w, cx = x;
                for (int i = start; i < start + count; i++) {
                    double tw = areas[i] / rowH;
                    out.add(new Tile(kids.get(i), cx, y, tw, rowH, colorFor(i)));
                    cx += tw;
                }
                y += rowH; h -= rowH;
            }
            start += count;
        }
    }

    private static double worst(double sum, double mx, double mn, double side) {
        double s2 = sum * sum, side2 = side * side;
        return Math.max(side2 * mx / s2, s2 / (side2 * mn));
    }

    private static Color colorFor(int i) {
        return Color.hsb((i * 137.508) % 360.0, 0.55, 0.80); // golden-angle hues: distinct + pleasant
    }

    private static double luminance(Color c) {
        return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
    }

    // ponytail: rough char-width estimate instead of measuring each label with a Text node.
    // Fine for tile labels; swap to real metrics if truncation ever looks off.
    private static String clip(String s, double maxW) {
        int max = (int) (maxW / 6.6);
        if (s.length() <= max) return s;
        return max <= 1 ? "" : s.substring(0, max - 1) + "…";
    }

    private Tile tileAt(double x, double y) {
        for (Tile t : tiles) {
            if (x >= t.x() && x < t.x() + t.w() && y >= t.y() && y < t.y() + t.h()) return t;
        }
        return null;
    }

    private String breadcrumb() {
        StringBuilder sb = new StringBuilder();
        Iterator<Node> it = stack.descendingIterator(); // root -> current
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append("  ›  ");
            sb.append(it.next().name);
        }
        return sb.toString();
    }

    private void exportAndExit() {
        try {
            WritableImage img = scene.snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(shotPath));
            System.out.println("wrote " + shotPath);
        } catch (Exception ex) {
            System.err.println("snapshot failed: " + ex);
        } finally {
            Platform.exit();
        }
    }
}
