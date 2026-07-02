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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * The diskbloom window: a folder tree scanned off the UI thread, shown as a
 * squarified treemap plus a sidebar (summary + largest-items list). Click a
 * tile or row to select; double-click to drill in.
 *
 * Launch with -Ddiskbloom.shot=out.png to render one folder to a PNG and exit.
 */
public class App extends Application {

    private static final String BG = "#1e1e1e", PANEL = "#252526", LINE = "#3a3a3a",
            FG = "#e6e6e6", DIM = "#9a9a9a";

    private final Canvas canvas = new Canvas();
    private final ListView<Node> list = new ListView<>();
    private final Label crumb = new Label();
    private final Label status = new Label("Pick a folder to begin.");
    private final Label sTitle = new Label("—");
    private final Label sSize = new Label();
    private final Label sCount = new Label();
    private final Label driveLbl = new Label();
    private final ProgressBar driveBar = new ProgressBar(0);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button upBtn = new Button("↑ Up");
    private VBox startPane;

    private final Deque<Node> stack = new ArrayDeque<>();
    private final List<Tile> tiles = new ArrayList<>();
    private Node selected;
    private long currentTotal;

    private final String shotPath = System.getProperty("diskbloom.shot");
    private Scene scene;

    private record Tile(Node node, double x, double y, double w, double h, Color color) {}

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG + ";");
        root.setTop(buildToolbar(stage));
        root.setLeft(buildSidebar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        scene = new Scene(root, 1180, 720, Color.web(BG));
        stage.setTitle("diskbloom");
        stage.setScene(scene);
        stage.show();

        List<String> params = getParameters().getRaw();
        if (!params.isEmpty()) scan(Paths.get(params.get(0)));
    }

    // ---- layout ----------------------------------------------------------

    private HBox buildToolbar(Stage stage) {
        Button open = new Button("Open folder…");
        open.setOnAction(e -> chooseAndScan(stage));
        upBtn.setOnAction(e -> { if (stack.size() > 1) { stack.pop(); selected = null; render(); } });
        upBtn.setDisable(true);
        spinner.setVisible(false);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        crumb.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:13px;");
        HBox.setHgrow(crumb, Priority.ALWAYS);

        HBox bar = new HBox(10, open, upBtn, spinner, crumb);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color:#2b2b2b; -fx-border-color:" + LINE + "; -fx-border-width:0 0 1 0;");
        return bar;
    }

    private VBox buildSidebar() {
        Label brand = new Label("diskbloom");
        brand.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:16px; -fx-font-weight:bold;");
        sTitle.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:13px;");
        sTitle.setMaxWidth(Double.MAX_VALUE);
        sSize.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:26px; -fx-font-weight:bold;");
        sCount.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:12px;");
        driveBar.setMaxWidth(Double.MAX_VALUE);
        driveBar.setPrefHeight(8);
        driveLbl.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");

        Label listTitle = new Label("Largest items");
        listTitle.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px; -fx-font-weight:bold; -fx-padding:6 0 2 0;");
        list.setStyle("-fx-control-inner-background:" + PANEL + "; -fx-background-color:" + PANEL + ";");
        list.setCellFactory(lv -> new ItemCell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) drill(list.getSelectionModel().getSelectedItem());
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            selected = nv;
            if (nv != null) updateStatus(nv);
            redraw();
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox box = new VBox(4, brand, sTitle, sSize, sCount, driveBar, driveLbl, divider(), listTitle, list);
        box.setPadding(new Insets(12));
        box.setPrefWidth(300);
        box.setStyle("-fx-background-color:" + PANEL + "; -fx-border-color:" + LINE + "; -fx-border-width:0 1 0 0;");
        return box;
    }

    private StackPane buildCenter() {
        Pane holder = new Pane(canvas);
        holder.setStyle("-fx-background-color:" + BG + ";");
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener(o -> render());
        canvas.heightProperty().addListener(o -> render());
        canvas.setOnMouseClicked(e -> {
            Tile t = tileAt(e.getX(), e.getY());
            if (t == null) return;
            if (e.getClickCount() == 2) drill(t.node());
            else { list.getSelectionModel().select(t.node()); }
        });
        canvas.setOnMouseMoved(e -> {
            Tile t = tileAt(e.getX(), e.getY());
            if (t != null) updateStatus(t.node());
        });

        startPane = buildStartPane();
        return new StackPane(holder, startPane);
    }

    private VBox buildStartPane() {
        Label title = new Label("diskbloom");
        title.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:32px; -fx-font-weight:bold;");
        Label sub = new Label("Pick a drive or folder to see what's using space");
        sub.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:14px;");

        HBox drives = new HBox(10);
        drives.setAlignment(Pos.CENTER);
        for (File r : File.listRoots()) {
            if (!r.exists()) continue;
            Button b = new Button(r.getPath());
            b.setOnAction(e -> scan(r.toPath()));
            drives.getChildren().add(b);
        }
        VBox v = new VBox(18, title, sub, drives);
        v.setAlignment(Pos.CENTER);
        v.setStyle("-fx-background-color:" + BG + ";");
        return v;
    }

    private HBox buildStatusBar() {
        status.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:12px;");
        HBox b = new HBox(status);
        b.setPadding(new Insets(6, 12, 6, 12));
        b.setStyle("-fx-background-color:#2b2b2b; -fx-border-color:" + LINE + "; -fx-border-width:1 0 0 0;");
        return b;
    }

    private Region divider() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color:" + LINE + ";");
        VBox.setMargin(r, new Insets(8, 0, 4, 0));
        return r;
    }

    // ---- scanning --------------------------------------------------------

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
        task.setOnSucceeded(e -> {
            spinner.setVisible(false);
            startPane.setVisible(false);
            setDriveUsage(root);
            stack.clear();
            stack.push(task.getValue());
            selected = null;
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

    private void setDriveUsage(Path root) {
        try {
            FileStore fs = Files.getFileStore(root);
            long total = fs.getTotalSpace(), used = total - fs.getUsableSpace();
            driveBar.setProgress(total > 0 ? (double) used / total : 0);
            Path drive = root.toAbsolutePath().getRoot();
            driveLbl.setText((drive != null ? drive.toString() : "") + " — "
                    + Sizes.human(used) + " of " + Sizes.human(total) + " used");
        } catch (Exception ex) {
            driveBar.setProgress(0);
            driveLbl.setText("");
        }
    }

    private void drill(Node n) {
        if (n != null && n.dir && n.children != null && !n.children.isEmpty()) {
            stack.push(n);
            selected = null;
            render();
        }
    }

    // ---- rendering -------------------------------------------------------

    private void render() {
        Node cur = stack.peek();
        upBtn.setDisable(stack.size() <= 1);
        updateSidebar(cur);
        if (cur == null) { tiles.clear(); redraw(); return; }
        crumb.setText(breadcrumb() + "      " + Sizes.human(cur.size));

        tiles.clear();
        currentTotal = cur.size;
        List<Node> kids = new ArrayList<>();
        if (cur.children != null) for (Node n : cur.children) if (n.size > 0) kids.add(n);
        list.getItems().setAll(kids);

        double W = canvas.getWidth(), H = canvas.getHeight();
        if (!kids.isEmpty() && W >= 2 && H >= 2) {
            double total = 0;
            for (Node n : kids) total += n.size;
            double scale = (W * H) / total;
            double[] areas = new double[kids.size()];
            for (int i = 0; i < kids.size(); i++) areas[i] = kids.get(i).size * scale;
            squarify(kids, areas, 0, 0, W, H, tiles);
        }
        redraw();
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web(BG));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (Tile t : tiles) draw(g, t);
    }

    private void draw(GraphicsContext g, Tile t) {
        boolean sel = t.node() == selected;
        g.setFill(t.color());
        g.fillRect(t.x(), t.y(), t.w(), t.h());
        g.setStroke(sel ? Color.WHITE : Color.web(BG));
        g.setLineWidth(sel ? 3 : 1.5);
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

    private void updateSidebar(Node cur) {
        if (cur == null) { sTitle.setText("—"); sSize.setText(""); sCount.setText(""); return; }
        sTitle.setText(cur.name);
        sSize.setText(Sizes.human(cur.size));
        int folders = 0, files = 0;
        if (cur.children != null) for (Node c : cur.children) { if (c.dir) folders++; else files++; }
        sCount.setText(folders + " folders · " + files + " files");
    }

    private void updateStatus(Node n) {
        double pct = currentTotal > 0 ? 100.0 * n.size / currentTotal : 0;
        status.setText(String.format("%s      %s   (%.1f%%)", n.path, Sizes.human(n.size), pct));
    }

    // Squarified treemap (Bruls, Huizing, van Wijk): rows kept near aspect 1.
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
        return Color.hsb((i * 137.508) % 360.0, 0.55, 0.80);
    }

    private static double luminance(Color c) {
        return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
    }

    private static String rgb(Color c) {
        return String.format("#%02X%02X%02X", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    // ponytail: rough char-width estimate instead of measuring each label.
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
        Iterator<Node> it = stack.descendingIterator();
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

    // Row in the "Largest items" list: colour swatch (matching its tile) + name + size + %.
    private final class ItemCell extends ListCell<Node> {
        @Override protected void updateItem(Node n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) { setGraphic(null); return; }
            Region sw = new Region();
            sw.setMinSize(11, 11); sw.setPrefSize(11, 11); sw.setMaxSize(11, 11);
            sw.setStyle("-fx-background-color:" + rgb(colorFor(getIndex())) + "; -fx-background-radius:2;");
            Label name = new Label(n.name + (n.dir ? "\\" : ""));
            name.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:12px;");
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            double pct = currentTotal > 0 ? 100.0 * n.size / currentTotal : 0;
            Label size = new Label(Sizes.human(n.size) + "   " + String.format("%.0f%%", pct));
            size.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
            HBox row = new HBox(8, sw, name, size);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
