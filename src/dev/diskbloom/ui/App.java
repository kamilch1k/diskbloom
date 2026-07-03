package dev.diskbloom.ui;

import dev.diskbloom.core.Scanner;
import dev.diskbloom.core.Scanner.Node;
import dev.diskbloom.core.ScanCache;
import dev.diskbloom.core.Sizes;
import dev.diskbloom.llm.Ollama;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
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
import java.awt.Desktop;
import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The diskbloom window: scans a folder off the UI thread (with a live progress
 * overlay), then shows a nested squarified treemap (folders = containers, files
 * = type-coloured leaves) plus a sidebar. Right-click for actions.
 *
 * On launch it auto-scans the system drive; pass a folder path to scan that
 * instead, or -Ddiskbloom.shot=out.png to render one folder to a PNG and exit.
 */
public class App extends Application {

    private static final String BG = "#1e1e1e", PANEL = "#252526", LINE = "#3a3a3a",
            CONTAINER = "#242424", FG = "#e6e6e6", DIM = "#9a9a9a";
    private static final int MAX_DEPTH = 6;
    private static final double MIN_SUBDIV = 28, MIN_LEAF = 3;

    private enum Cat {
        VIDEO("Video", "#7f77dd"), IMAGE("Images", "#1d9e75"), AUDIO("Audio", "#d4537e"),
        ARCHIVE("Archives", "#ba7517"), CODE("Code", "#378add"), DOC("Documents", "#639922"),
        APP("Apps & binaries", "#6e7b8b"), FOLDER("Folders", "#565b62"), OTHER("Other", "#7c7c7c");
        final String label; final Color color;
        Cat(String label, String hex) { this.label = label; this.color = Color.web(hex); }
    }

    private static final Map<String, Cat> EXT = new HashMap<>();
    static {
        put(Cat.VIDEO, "mp4 mkv avi mov wmv flv webm m4v mpg mpeg");
        put(Cat.IMAGE, "jpg jpeg png gif bmp svg webp tif tiff heic ico raw psd");
        put(Cat.AUDIO, "mp3 wav flac aac ogg m4a wma opus");
        put(Cat.ARCHIVE, "zip rar 7z tar gz bz2 xz iso jar cab jmod");
        put(Cat.CODE, "java js ts jsx tsx py c cpp cc h hpp cs go rs rb php html htm css scss json xml yml yaml sh kt swift sql");
        put(Cat.DOC, "pdf doc docx xls xlsx ppt pptx txt md csv rtf odt ods");
        put(Cat.APP, "exe dll so dylib bin msi sys lib obj pdb node class");
    }
    private static void put(Cat c, String exts) { for (String e : exts.split(" ")) EXT.put(e, c); }

    private final Canvas canvas = new Canvas();
    private final ListView<Node> list = new ListView<>();
    private final Label crumb = new Label();
    private final Label status = new Label("Starting…");
    private final Label sTitle = new Label("—");
    private final Label sSize = new Label();
    private final Label sCount = new Label();
    private final Label driveLbl = new Label();
    private final ProgressBar driveBar = new ProgressBar(0);
    private final Button upBtn = new Button("↑ Up");
    private final HBox legendBar = new HBox();
    private final VBox legendRows = new VBox(3);

    private final Label scanTitle = new Label();
    private final Label scanInfo = new Label();
    private final Label scanPath = new Label();
    private final ProgressBar scanBar = new ProgressBar(-1);
    private final Button cancelBtn = new Button("Cancel");
    private Pane holder;
    private VBox startPane, scanPane;
    private AtomicBoolean cancelFlag;
    private final Button biggestBtn = new Button("Biggest files");
    private boolean biggestMode;
    private List<Node> biggestFiles;

    private BorderPane rootPane;
    private final Button assistantBtn = new Button("Assistant");
    private VBox assistantPanel;
    private final ComboBox<String> modelPicker = new ComboBox<>();
    private final TextArea responseArea = new TextArea();
    private final TextField questionField = new TextField();
    private final Button askBtn = new Button("Ask");
    private final VBox proposalsBox = new VBox(4);
    private final Button recycleBtn = new Button("Move checked to Recycle Bin");
    private VBox proposalsSection;

    private final Deque<Node> stack = new ArrayDeque<>();
    private final List<Tile> tiles = new ArrayList<>();
    private final List<Tile> topTiles = new ArrayList<>();
    private final IdentityHashMap<Node, Cat> dominant = new IdentityHashMap<>();
    private final EnumMap<Cat, Long> catSums = new EnumMap<>(Cat.class);
    private Node selected;
    private long currentTotal;

    private final String shotPath = System.getProperty("diskbloom.shot");
    private Scene scene;

    private record Rect(Node node, double x, double y, double w, double h) {}
    private record Tile(Node node, double x, double y, double w, double h, Color color, int depth, boolean leaf) {}

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        rootPane = new BorderPane();
        rootPane.setStyle("-fx-background-color:" + BG + ";");
        rootPane.setTop(buildToolbar(stage));
        rootPane.setLeft(buildSidebar());
        rootPane.setCenter(buildCenter());
        rootPane.setBottom(buildStatusBar());

        scene = new Scene(rootPane, 1180, 720, Color.web(BG));
        Theme.apply(scene);
        stage.setTitle("diskbloom");
        stage.setScene(scene);
        stage.show();

        assistantPanel = buildAssistantPanel();
        initAssistant();

        List<String> params = getParameters().getRaw();
        if (!params.isEmpty()) { scan(Paths.get(params.get(0))); return; }
        ScanCache.Cached cached = ScanCache.load(cacheFile());
        if (cached != null && cached.root() != null && cached.root().children != null && !cached.root().children.isEmpty())
            showCached(cached);
        else scan(systemRoot());
    }

    private static Path systemRoot() {
        Path home = Paths.get(System.getProperty("user.home"));
        Path root = home.getRoot();
        return root != null ? root : home;
    }

    private static Path cacheFile() {
        String base = System.getenv("LOCALAPPDATA");
        Path dir = (base != null ? Paths.get(base) : Paths.get(System.getProperty("user.home"))).resolve("diskbloom");
        return dir.resolve("lastscan.bin");
    }

    private void showCached(ScanCache.Cached cached) {
        setDriveUsage(cached.root().path);
        stack.clear();
        stack.push(cached.root());
        selected = null;
        biggestMode = false;
        biggestBtn.setText("Biggest files");
        showResults();
        showCurrent();
        String when = new java.text.SimpleDateFormat("MMM d, HH:mm").format(new java.util.Date(cached.timestamp()));
        status.setText("Showing cached scan from " + when + "  ·  click Rescan to refresh");
    }

    // ---- layout ----------------------------------------------------------

    private HBox buildToolbar(Stage stage) {
        Button open = new Button("Open folder…");
        open.setOnAction(e -> chooseAndScan(stage));
        Button rescanBtn = new Button("Rescan");
        rescanBtn.setOnAction(e -> { Node r = stack.peekLast(); scan(r != null ? r.path : systemRoot()); });
        upBtn.setOnAction(e -> { if (stack.size() > 1) { stack.pop(); selected = null; showCurrent(); } });
        upBtn.setDisable(true);
        crumb.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:13px;");
        HBox.setHgrow(crumb, Priority.ALWAYS);

        biggestBtn.setOnAction(e -> { if (biggestMode) exitBiggest(); else enterBiggest(); });
        assistantBtn.setDisable(true);
        assistantBtn.setOnAction(e -> toggleAssistant());
        HBox bar = new HBox(10, open, rescanBtn, upBtn, biggestBtn, crumb, assistantBtn);
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

        Label byType = new Label("By type");
        byType.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px; -fx-font-weight:bold; -fx-padding:2 0 0 0;");
        legendBar.setPrefHeight(10);
        legendBar.setMaxWidth(Double.MAX_VALUE);

        Label listTitle = new Label("Largest items");
        listTitle.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px; -fx-font-weight:bold; -fx-padding:4 0 2 0;");
        list.setStyle("-fx-control-inner-background:" + PANEL + "; -fx-background-color:" + PANEL + ";");
        list.setCellFactory(lv -> new ItemCell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) drill(list.getSelectionModel().getSelectedItem());
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            selected = nv;
            if (nv != null) updateStatus(nv);
            draw();
        });
        list.setOnContextMenuRequested(e -> {
            Node n = list.getSelectionModel().getSelectedItem();
            if (n != null) menuFor(n).show(list, e.getScreenX(), e.getScreenY());
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox box = new VBox(4, brand, sTitle, sSize, sCount, driveBar, driveLbl,
                divider(), byType, legendBar, legendRows, divider(), listTitle, list);
        box.setPadding(new Insets(12));
        box.setPrefWidth(300);
        box.setStyle("-fx-background-color:" + PANEL + "; -fx-border-color:" + LINE + "; -fx-border-width:0 1 0 0;");
        return box;
    }

    private StackPane buildCenter() {
        holder = new Pane(canvas);
        holder.setStyle("-fx-background-color:" + BG + ";");
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener(o -> render());
        canvas.heightProperty().addListener(o -> render());
        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Node d = dirAt(e.getX(), e.getY());
                if (d != null) drill(d);
            } else {
                Tile t = tileAt(e.getX(), e.getY());
                if (t == null) return;
                if (list.getItems().contains(t.node())) list.getSelectionModel().select(t.node());
                else { selected = t.node(); updateStatus(t.node()); draw(); }
            }
        });
        canvas.setOnMouseMoved(e -> {
            Tile t = tileAt(e.getX(), e.getY());
            if (t != null) updateStatus(t.node());
        });
        canvas.setOnContextMenuRequested(e -> {
            Tile t = tileAt(e.getX(), e.getY());
            if (t != null) {
                selected = t.node();
                draw();
                menuFor(t.node()).show(canvas, e.getScreenX(), e.getScreenY());
            }
        });

        startPane = buildStartPane();
        scanPane = buildScanPane();
        scanPane.setVisible(false);
        return new StackPane(holder, startPane, scanPane);
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

    private VBox buildScanPane() {
        scanTitle.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:20px; -fx-font-weight:bold;");
        scanBar.setPrefWidth(380);
        scanBar.setPrefHeight(12);
        scanInfo.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:13px;");
        scanPath.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        scanPath.setMaxWidth(480);
        cancelBtn.setOnAction(e -> { if (cancelFlag != null) cancelFlag.set(true); });

        VBox inner = new VBox(14, scanTitle, scanBar, scanInfo, scanPath, cancelBtn);
        inner.setAlignment(Pos.CENTER);
        inner.setMaxWidth(520);
        VBox v = new VBox(inner);
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

    private void showStart() { startPane.setVisible(true); scanPane.setVisible(false); }
    private void showScanning() { scanPane.setVisible(true); startPane.setVisible(false); }
    private void showResults() { startPane.setVisible(false); scanPane.setVisible(false); }

    // ---- scanning + navigation ------------------------------------------

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
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlag = cancel;
        Task<Node> task = new Task<>() {
            @Override protected Node call() {
                return Scanner.scan(root, (files, bytes, path) -> Platform.runLater(() -> {
                    scanInfo.setText(String.format("%,d files · %s scanned", files, Sizes.human(bytes)));
                    scanPath.setText(path);
                }), cancel);
            }
        };
        scanTitle.setText("Scanning " + root);
        scanInfo.setText("Starting…");
        scanPath.setText("");
        crumb.setText("Scanning " + root + " …");
        showScanning();

        task.setOnSucceeded(e -> {
            Node result = task.getValue();
            if (result == null) { showStart(); crumb.setText("Scan cancelled."); return; } // cancelled
            setDriveUsage(root);
            stack.clear();
            stack.push(result);
            selected = null;
            biggestMode = false;
            biggestBtn.setText("Biggest files");
            showResults();
            showCurrent();
            boolean usable = result.dir && result.children != null && !result.children.isEmpty();
            if (!usable) status.setText("Nothing to show for " + root + " — is that path valid and accessible?");
            final Node toCache = result;
            if (shotPath == null && usable) new Thread(() -> {
                try { ScanCache.save(cacheFile(), toCache, System.currentTimeMillis()); } catch (Exception ignore) { }
            }, "diskbloom-cache-save").start();
            String ask = System.getProperty("diskbloom.ask");
            if (ask != null) { runAsk(ask); return; }
            if (System.getProperty("diskbloom.biggest") != null) enterBiggest();
            if (System.getProperty("diskbloom.assistant") != null) toggleAssistant();
            if (System.getProperty("diskbloom.testproposals") != null) { toggleAssistant(); injectTestProposals(); }
            if (shotPath != null) Platform.runLater(this::exportAndExit);
        });
        task.setOnFailed(e -> { showStart(); crumb.setText("Scan failed: " + task.getException()); });
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
            showCurrent();
        }
    }

    private void showCurrent() {
        Node cur = stack.peek();
        upBtn.setDisable(stack.size() <= 1);
        updateSidebar(cur);
        if (cur == null) { list.getItems().clear(); render(); return; }
        crumb.setText(breadcrumb() + "      " + Sizes.human(cur.size));
        currentTotal = cur.size;

        List<Node> kids = new ArrayList<>();
        if (cur.children != null) for (Node n : cur.children) if (n.size > 0) kids.add(n);
        dominant.clear();
        for (Node k : kids) dominant.put(k, dominantCat(k));
        list.getItems().setAll(kids);

        catSums.clear();
        accumulate(cur, catSums);
        updateLegend();
        status.setText("Hover for details  ·  double-click to open a folder  ·  right-click for actions");
        render();
    }

    // ---- rendering -------------------------------------------------------

    private void render() {
        tiles.clear();
        topTiles.clear();
        double W = canvas.getWidth(), H = canvas.getHeight();
        if (W >= 2 && H >= 2) {
            if (biggestMode) {
                if (biggestFiles != null && !biggestFiles.isEmpty()) layoutFlat(biggestFiles, W, H);
            } else {
                Node cur = stack.peek();
                if (cur != null) layout(cur, 0, 0, W, H, 0);
            }
        }
        draw();
    }

    private void layoutFlat(List<Node> items, double W, double H) {
        double total = 0;
        for (Node n : items) total += n.size;
        if (total <= 0) return;
        double scale = (W * H) / total;
        double[] areas = new double[items.size()];
        for (int i = 0; i < items.size(); i++) areas[i] = items.get(i).size * scale;
        List<Rect> rects = new ArrayList<>();
        squarify(items, areas, 0, 0, W, H, rects);
        for (Rect r : rects) {
            Tile t = new Tile(r.node(), r.x(), r.y(), r.w(), r.h(), colorOf(r.node()), 0, true);
            tiles.add(t);
            topTiles.add(t);
        }
    }

    private void layout(Node node, double x, double y, double w, double h, int depth) {
        List<Node> kids = new ArrayList<>();
        if (node.children != null) for (Node n : node.children) if (n.size > 0) kids.add(n);
        if (kids.isEmpty()) return;
        double total = 0;
        for (Node n : kids) total += n.size;
        if (total <= 0) return;
        double scale = (w * h) / total;
        double[] areas = new double[kids.size()];
        for (int i = 0; i < kids.size(); i++) areas[i] = kids.get(i).size * scale;

        List<Rect> rects = new ArrayList<>();
        squarify(kids, areas, x, y, w, h, rects);
        for (Rect r : rects) {
            boolean drillable = r.node().dir && r.node().children != null && !r.node().children.isEmpty();
            boolean recurse = drillable && depth < MAX_DEPTH && r.w() > MIN_SUBDIV && r.h() > MIN_SUBDIV;
            Tile t = new Tile(r.node(), r.x(), r.y(), r.w(), r.h(), colorOf(r.node()), depth, !recurse);
            tiles.add(t);
            if (depth == 0) topTiles.add(t);
            if (recurse) {
                double header = r.h() > 30 ? 16 : 0;
                double iw = r.w() - 4, ih = r.h() - header - 2;
                if (iw > MIN_LEAF && ih > MIN_LEAF) layout(r.node(), r.x() + 2, r.y() + header, iw, ih, depth + 1);
            }
        }
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web(BG));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (Tile t : tiles) {
            if (t.leaf()) drawLeaf(g, t);
            else drawFolder(g, t);
        }
    }

    private void drawFolder(GraphicsContext g, Tile t) {
        g.setFill(Color.web(CONTAINER));
        g.fillRect(t.x(), t.y(), t.w(), t.h());
        boolean sel = t.node() == selected;
        g.setStroke(sel ? Color.WHITE : Color.web("#111111"));
        g.setLineWidth(sel ? 2.5 : 1);
        g.strokeRect(t.x() + 0.5, t.y() + 0.5, t.w() - 1, t.h() - 1);
        if (t.h() > 18 && t.w() > 52) {
            g.setFill(Color.web("#cfcfcf"));
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
            g.fillText(clip(t.node().name + "\\", t.w() - 10), t.x() + 5, t.y() + 12);
        }
    }

    private void drawLeaf(GraphicsContext g, Tile t) {
        boolean sel = t.node() == selected;
        g.setFill(t.color());
        g.fillRect(t.x(), t.y(), t.w(), t.h());
        g.setStroke(sel ? Color.WHITE : Color.web(BG));
        g.setLineWidth(sel ? 2.5 : 1);
        g.strokeRect(t.x(), t.y(), t.w(), t.h());
        if (t.w() > 46 && t.h() > 22) {
            g.setFill(luminance(t.color()) > 0.55 ? Color.web("#141414") : Color.web("#f6f6f6"));
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
            g.fillText(clip(t.node().name + (t.node().dir ? "\\" : ""), t.w() - 12), t.x() + 6, t.y() + 16);
            if (t.h() > 36) {
                g.setFont(Font.font("Segoe UI", 11));
                g.fillText(Sizes.human(t.node().size), t.x() + 6, t.y() + 30);
            }
        }
    }

    // ---- sidebar / legend ------------------------------------------------

    private void updateSidebar(Node cur) {
        if (cur == null) { sTitle.setText("—"); sSize.setText(""); sCount.setText(""); return; }
        sTitle.setText(cur.name);
        sSize.setText(Sizes.human(cur.size));
        int folders = 0, files = 0;
        if (cur.children != null) for (Node c : cur.children) { if (c.dir) folders++; else files++; }
        sCount.setText(folders + " folders · " + files + " files");
    }

    private void updateLegend() {
        legendBar.getChildren().clear();
        legendRows.getChildren().clear();
        long total = 0;
        for (long v : catSums.values()) total += v;
        if (total <= 0) return;

        List<Map.Entry<Cat, Long>> entries = new ArrayList<>(catSums.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        for (Map.Entry<Cat, Long> e : entries) {
            Region seg = new Region();
            seg.setPrefWidth(Math.max(1, (double) e.getValue() / total * 276));
            seg.setMinWidth(Region.USE_PREF_SIZE);
            seg.setPrefHeight(10);
            seg.setStyle("-fx-background-color:" + rgb(e.getKey().color) + ";");
            legendBar.getChildren().add(seg);
        }
        int shown = 0;
        for (Map.Entry<Cat, Long> e : entries) {
            double pct = 100.0 * e.getValue() / total;
            if (pct < 1 || shown >= 5) break;
            Region sw = new Region();
            sw.setMinSize(9, 9); sw.setPrefSize(9, 9); sw.setMaxSize(9, 9);
            sw.setStyle("-fx-background-color:" + rgb(e.getKey().color) + "; -fx-background-radius:2;");
            Label l = new Label(e.getKey().label + "  " + String.format("%.0f%%", pct) + "  ·  " + Sizes.human(e.getValue()));
            l.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
            HBox row = new HBox(6, sw, l);
            row.setAlignment(Pos.CENTER_LEFT);
            legendRows.getChildren().add(row);
            shown++;
        }
    }

    private void updateStatus(Node n) {
        double pct = currentTotal > 0 ? 100.0 * n.size / currentTotal : 0;
        status.setText(String.format("%s      %s   (%.1f%%)", n.path, Sizes.human(n.size), pct));
    }

    // ---- treemap maths + categories -------------------------------------

    private void squarify(List<Node> kids, double[] areas, double x, double y, double w, double h, List<Rect> out) {
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
                    out.add(new Rect(kids.get(i), x, cy, rowW, th));
                    cy += th;
                }
                x += rowW; w -= rowW;
            } else {
                double rowH = rowArea / w, cx = x;
                for (int i = start; i < start + count; i++) {
                    double tw = areas[i] / rowH;
                    out.add(new Rect(kids.get(i), cx, y, tw, rowH));
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

    private static Cat catOf(Node n) {
        if (n.dir) return Cat.FOLDER;
        String nm = n.name;
        int d = nm.lastIndexOf('.');
        String ext = d >= 0 ? nm.substring(d + 1).toLowerCase() : "";
        return EXT.getOrDefault(ext, Cat.OTHER);
    }

    private static Color colorOf(Node n) {
        return catOf(n).color;
    }

    private static void accumulate(Node n, EnumMap<Cat, Long> sums) {
        if (!n.dir) { sums.merge(catOf(n), n.size, Long::sum); return; }
        if (n.children != null) for (Node c : n.children) accumulate(c, sums);
    }

    private static Cat dominantCat(Node n) {
        if (!n.dir) return catOf(n);
        EnumMap<Cat, Long> m = new EnumMap<>(Cat.class);
        accumulate(n, m);
        Cat best = Cat.FOLDER;
        long bv = -1;
        for (Map.Entry<Cat, Long> e : m.entrySet()) if (e.getValue() > bv) { bv = e.getValue(); best = e.getKey(); }
        return best;
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
        Tile found = null;
        for (Tile t : tiles) if (contains(t, x, y)) found = t;
        return found;
    }

    private Node dirAt(double x, double y) {
        Node found = null;
        for (Tile t : tiles) {
            if (contains(t, x, y) && t.node().dir && t.node().children != null && !t.node().children.isEmpty()) found = t.node();
        }
        return found;
    }

    private static boolean contains(Tile t, double x, double y) {
        return x >= t.x() && x < t.x() + t.w() && y >= t.y() && y < t.y() + t.h();
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

    // ---- assistant (local LLM via Ollama) -------------------------------

    private static final String DELETE_TAG = "SUGGEST_DELETE:";

    private static final String SYSTEM_PROMPT =
            "You are diskbloom's local disk-cleanup assistant. You are given a summary of a disk scan. "
            + "Answer concisely and practically. When you recommend removing something, add one line per item, "
            + "each on its own line, formatted exactly as: SUGGEST_DELETE: <full path>  (copy the full path "
            + "verbatim from the summary). Only suggest things clearly safe to remove (caches, temp files, "
            + "downloads, old installers, logs); never OS, system, or program files. Keep prose short. The user "
            + "reviews and approves every deletion; you cannot delete anything yourself.";

    private VBox buildAssistantPanel() {
        Label title = new Label("Assistant");
        title.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:16px; -fx-font-weight:bold;");
        modelPicker.setMaxWidth(Double.MAX_VALUE);

        responseArea.setEditable(false);
        responseArea.setWrapText(true);
        responseArea.setFocusTraversable(false);
        responseArea.setStyle("-fx-control-inner-background:#1c1c1c; -fx-text-fill:" + FG + "; -fx-font-size:12px;");
        responseArea.setText("Ask what's using space, or what looks safe to delete.\nAnything to delete will go through an approval step.");
        VBox.setVgrow(responseArea, Priority.ALWAYS);

        VBox presets = new VBox(6);
        for (String p : new String[]{"What's using space?", "What's safe to delete?", "Find big old files"}) {
            Button b = new Button(p);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setStyle("-fx-font-size:11px;");
            b.setOnAction(e -> ask(p));
            presets.getChildren().add(b);
        }

        questionField.setPromptText("Ask a question…");
        HBox.setHgrow(questionField, Priority.ALWAYS);
        askBtn.getStyleClass().add("accent");
        askBtn.setOnAction(e -> ask(questionField.getText()));
        questionField.setOnAction(e -> ask(questionField.getText()));
        HBox askRow = new HBox(6, questionField, askBtn);

        Label note = new Label("Local · nothing leaves your PC");
        note.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");

        Label pTitle = new Label("Suggested deletions — you approve each");
        pTitle.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px; -fx-font-weight:bold;");
        ScrollPane pScroll = new ScrollPane(proposalsBox);
        pScroll.setFitToWidth(true);
        pScroll.setMaxHeight(170);
        pScroll.setStyle("-fx-background:" + PANEL + "; -fx-background-color:" + PANEL + ";");
        recycleBtn.setMaxWidth(Double.MAX_VALUE);
        recycleBtn.getStyleClass().add("danger");
        recycleBtn.setOnAction(e -> recycleChecked());
        proposalsSection = new VBox(6, pTitle, pScroll, recycleBtn);
        proposalsSection.setVisible(false);
        proposalsSection.setManaged(false);

        VBox v = new VBox(8, title, modelPicker, presets, responseArea, proposalsSection, askRow, note);
        v.setPadding(new Insets(12));
        v.setPrefWidth(360);
        v.setStyle("-fx-background-color:" + PANEL + "; -fx-border-color:" + LINE + "; -fx-border-width:0 0 0 1;");
        return v;
    }

    private void initAssistant() {
        Thread t = new Thread(() -> {
            List<String> ms;
            try { ms = Ollama.models(); } catch (Exception e) { ms = List.of(); }
            final List<String> models = ms;
            Platform.runLater(() -> {
                if (models.isEmpty()) {
                    assistantBtn.setDisable(true);
                    assistantBtn.setTooltip(new Tooltip("Start Ollama (localhost:11434) to enable the assistant"));
                } else {
                    modelPicker.getItems().setAll(models);
                    modelPicker.getSelectionModel().select(models.contains("qwen2.5:14b") ? "qwen2.5:14b" : models.get(0));
                    assistantBtn.setDisable(false);
                }
            });
        }, "ollama-check");
        t.setDaemon(true);
        t.start();
    }

    private void toggleAssistant() {
        if (rootPane.getRight() == null) {
            rootPane.setRight(assistantPanel);
            assistantBtn.setText("Assistant ✕");
        } else {
            rootPane.setRight(null);
            assistantBtn.setText("Assistant");
        }
    }

    private void ask(String question) {
        if (question == null || question.isBlank()) return;
        String model = modelPicker.getSelectionModel().getSelectedItem();
        if (model == null) { responseArea.setText("No model available. Is Ollama running?"); return; }
        String prompt = "Disk scan summary:\n" + buildContext() + "\nQuestion: " + question;
        responseArea.setText("Thinking… (the first query loads the model into memory, ~15s)");
        clearProposals();
        askBtn.setDisable(true);
        questionField.setDisable(true);
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                return Ollama.chat(model, SYSTEM_PROMPT, prompt);
            }
        };
        task.setOnSucceeded(e -> { handleResponse(task.getValue()); askBtn.setDisable(false); questionField.setDisable(false); });
        task.setOnFailed(e -> { responseArea.setText("Error: " + task.getException()); askBtn.setDisable(false); questionField.setDisable(false); });
        Thread th = new Thread(task, "ollama-chat");
        th.setDaemon(true);
        th.start();
    }

    // Headless verification hook: -Ddiskbloom.ask="question" prints the answer and exits.
    private void runAsk(String question) {
        String prompt = "Disk scan summary:\n" + buildContext() + "\nQuestion: " + question;
        List<String> ms = modelPicker.getItems();
        String model = ms.contains("qwen2.5:14b") ? "qwen2.5:14b" : (ms.isEmpty() ? "qwen2.5:14b" : ms.get(0));
        new Thread(() -> {
            try { System.out.println("=== ASSISTANT (" + model + ") ===\n" + Ollama.chat(model, SYSTEM_PROMPT, prompt)); }
            catch (Exception ex) { System.out.println("ask failed: " + ex); }
            Platform.exit();
        }, "ollama-ask").start();
    }

    private void handleResponse(String text) {
        List<String> paths = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith(DELETE_TAG)) {
                String p = stripEnds(t.substring(DELETE_TAG.length()).strip());
                if (!p.isEmpty()) paths.add(p);
            } else {
                prose.append(line).append('\n');
            }
        }
        responseArea.setText(prose.toString().strip());
        List<Node> found = new ArrayList<>();
        Node root = stack.peekLast();
        if (root != null && !paths.isEmpty()) matchWalk(root, new HashSet<>(paths), found);
        showProposals(found);
    }

    private void showProposals(List<Node> nodes) {
        proposalsBox.getChildren().clear();
        if (nodes.isEmpty()) { proposalsSection.setVisible(false); proposalsSection.setManaged(false); return; }
        for (Node n : nodes) {
            CheckBox cb = new CheckBox(Sizes.human(n.size) + "   " + n.path);
            cb.setUserData(n);
            cb.setWrapText(true);
            cb.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:11px;");
            proposalsBox.getChildren().add(cb);
        }
        proposalsSection.setVisible(true);
        proposalsSection.setManaged(true);
    }

    private void clearProposals() {
        proposalsBox.getChildren().clear();
        if (proposalsSection != null) { proposalsSection.setVisible(false); proposalsSection.setManaged(false); }
    }

    private void recycleChecked() {
        List<Node> sel = new ArrayList<>();
        for (javafx.scene.Node child : proposalsBox.getChildren()) {
            if (child instanceof CheckBox cb && cb.isSelected()) sel.add((Node) cb.getUserData());
        }
        if (sel.isEmpty()) return;
        long sum = 0;
        for (Node n : sel) sum += n.size;
        Desktop d = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
        if (d == null || !d.isSupported(Desktop.Action.MOVE_TO_TRASH)) { info("Recycle Bin isn't available on this system."); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Move " + sel.size() + " item(s) (" + Sizes.human(sum) + ") to the Recycle Bin?",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Delete to Recycle Bin");
        a.setHeaderText("Approve these deletions?");
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        for (Node n : sel) { try { d.moveToTrash(n.path.toFile()); } catch (Exception ignore) { } }
        clearProposals();
        Node cur = stack.peek();
        if (cur != null) scan(cur.path);
    }

    private static void matchWalk(Node n, Set<String> paths, List<Node> out) {
        if (paths.contains(n.path.toString())) out.add(n);
        if (n.children != null) for (Node c : n.children) matchWalk(c, paths, out);
    }

    private static String stripEnds(String s) {
        return s.replaceAll("^[\\s`\"']+", "").replaceAll("[\\s`\"']+$", "");
    }

    // Headless check of the propose->approve pipeline: inject a canned response
    // referencing two real files in the scan so the checklist populates.
    private void injectTestProposals() {
        Node r = stack.peekLast();
        if (r == null) return;
        String canned = "These small leftover files are examples you could remove:\n"
                + DELETE_TAG + " " + r.path.resolve("readme.txt") + "\n"
                + DELETE_TAG + " " + r.path.resolve("release") + "\n";
        handleResponse(canned);
    }

    private String buildContext() {
        Node scanRoot = stack.peekLast();
        StringBuilder sb = new StringBuilder();
        if (scanRoot != null) {
            sb.append("Scanned: ").append(scanRoot.path).append("  (").append(Sizes.human(scanRoot.size)).append(" total)\n");
        }
        if (!driveLbl.getText().isEmpty()) sb.append("Drive: ").append(driveLbl.getText()).append('\n');
        long tot = 0;
        for (long v : catSums.values()) tot += v;
        if (tot > 0) {
            sb.append("By type: ");
            List<Map.Entry<Cat, Long>> es = new ArrayList<>(catSums.entrySet());
            es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<Cat, Long> e : es) sb.append(e.getKey().label).append(' ').append(Sizes.human(e.getValue())).append(", ");
            sb.append('\n');
        }
        if (scanRoot != null) {
            sb.append("Largest folders:\n");
            int n = 0;
            if (scanRoot.children != null) {
                for (Node c : scanRoot.children) {
                    if (!c.dir) continue;
                    sb.append("  ").append(Sizes.human(c.size)).append("  ").append(c.path).append('\n');
                    if (++n >= 20) break;
                }
            }
            PriorityQueue<Node> heap = new PriorityQueue<>(Comparator.comparingLong((Node x) -> x.size));
            collectInto(scanRoot, heap);
            List<Node> files = new ArrayList<>(heap);
            files.sort(Comparator.comparingLong((Node x) -> x.size).reversed());
            sb.append("Largest files:\n");
            n = 0;
            for (Node f : files) {
                sb.append("  ").append(Sizes.human(f.size)).append("  ").append(f.path).append('\n');
                if (++n >= 20) break;
            }
        }
        return sb.toString();
    }

    // ---- biggest files view ---------------------------------------------

    private static final int BIGGEST_N = 500;

    private void enterBiggest() {
        Node root = stack.peekLast();
        if (root == null) return;
        PriorityQueue<Node> heap = new PriorityQueue<>(Comparator.comparingLong((Node n) -> n.size));
        collectInto(root, heap); // keeps only the top BIGGEST_N files, bounded memory
        List<Node> files = new ArrayList<>(heap);
        files.sort(Comparator.comparingLong((Node n) -> n.size).reversed());

        biggestFiles = files;
        biggestMode = true;
        biggestBtn.setText("← Folders");
        upBtn.setDisable(true);
        selected = null;

        long sum = 0;
        for (Node f : files) sum += f.size;
        currentTotal = sum;
        crumb.setText("Biggest files  ·  top " + files.size() + " across " + root.name);
        sTitle.setText("Biggest files");
        sSize.setText(Sizes.human(sum));
        sCount.setText(files.size() + " files");
        dominant.clear();
        for (Node f : files) dominant.put(f, catOf(f));
        list.getItems().setAll(files);
        catSums.clear();
        for (Node f : files) catSums.merge(catOf(f), f.size, Long::sum);
        updateLegend();
        status.setText("Biggest individual files across the whole scan  ·  right-click for actions");
        render();
    }

    private void exitBiggest() {
        biggestMode = false;
        biggestBtn.setText("Biggest files");
        showCurrent();
    }

    private static void collectInto(Node n, PriorityQueue<Node> heap) {
        if (!n.dir) {
            heap.offer(n);
            if (heap.size() > BIGGEST_N) heap.poll();
            return;
        }
        if (n.children != null) for (Node c : n.children) collectInto(c, heap);
    }

    // ---- actions ---------------------------------------------------------

    private ContextMenu menuFor(Node n) {
        File f = n.path.toFile();
        MenuItem open = new MenuItem(n.dir ? "Open folder" : "Open");
        open.setOnAction(a -> runQuietly(() -> Desktop.getDesktop().open(f)));
        MenuItem reveal = new MenuItem("Reveal in Explorer");
        reveal.setOnAction(a -> runQuietly(() -> new ProcessBuilder("explorer.exe", "/select," + f.getAbsolutePath()).start()));
        MenuItem del = new MenuItem("Delete to Recycle Bin…");
        del.setOnAction(a -> deleteToTrash(n));
        return new ContextMenu(open, reveal, new SeparatorMenuItem(), del);
    }

    private void deleteToTrash(Node n) {
        File f = n.path.toFile();
        Desktop d = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
        if (d == null || !d.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            info("Recycle Bin isn't available on this system.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                f.getAbsolutePath() + "\n\n" + Sizes.human(n.size) + " will be moved to the Recycle Bin.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Delete to Recycle Bin");
        a.setHeaderText("Move to Recycle Bin?");
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        boolean ok;
        try { ok = d.moveToTrash(f); } catch (Exception ex) { ok = false; }
        if (ok) {
            Node cur = stack.peek();
            if (cur != null) scan(cur.path); // re-scan current folder to reflect the deletion
        } else {
            info("Couldn't delete " + f.getName() + ".");
        }
    }

    private static void runQuietly(ThrowingRunnable r) {
        try { r.run(); } catch (Exception ignore) { }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
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

    // Row in the "Largest items" list: swatch (dominant type) + name + size + %.
    private final class ItemCell extends ListCell<Node> {
        @Override protected void updateItem(Node n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) { setGraphic(null); return; }
            Cat c = dominant.getOrDefault(n, catOf(n));
            Region sw = new Region();
            sw.setMinSize(11, 11); sw.setPrefSize(11, 11); sw.setMaxSize(11, 11);
            sw.setStyle("-fx-background-color:" + rgb(c.color) + "; -fx-background-radius:2;");
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
