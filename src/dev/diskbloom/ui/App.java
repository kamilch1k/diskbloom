package dev.diskbloom.ui;

import dev.diskbloom.core.Scanner;
import dev.diskbloom.core.Scanner.Node;
import dev.diskbloom.core.ScanCache;
import dev.diskbloom.core.Sizes;
import dev.diskbloom.llm.Ollama;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.File;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int MAX_DEPTH = 4;
    private static final double MIN_SUBDIV = 38, MIN_LEAF = 3, GAP = 2, ARC = 5;

    private enum Cat {
        VIDEO("Video", "#7f77dd"), IMAGE("Images", "#1d9e75"), AUDIO("Audio", "#d4537e"),
        ARCHIVE("Archives", "#ba7517"), CODE("Code", "#378add"), DOC("Documents", "#639922"),
        APP("Apps & binaries", "#6d8bb0"), FOLDER("Folders", "#565b62"), OTHER("Other", "#857e76");
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
    private boolean biggestMode;
    private List<Node> biggestFiles;
    private final TreeTableView<Node> tree = new TreeTableView<>();
    private boolean listView = true;
    private final Button viewBtn = new Button("Map view");

    private final ComboBox<String> driveBox = new ComboBox<>();
    private final TextField addressField = new TextField();
    private final TextField searchField = new TextField();
    private final Button measureBtn = new Button("Measure folders");
    private Node browseRoot;          // non-null while the list is live-browsing the filesystem
    private List<Node> searchHits;    // non-null while showing search results
    private String searchLabel = "";
    private static final int SEARCH_CAP = 3000;

    private boolean autoScan = false;                 // persisted; when false the app never scans on launch
    private final Button dupBtn = new Button("Duplicates");
    private final Button settingsBtn = new Button("Settings");

    static final String VERSION = "0.25.0";           // shown in the title bar + sidebar; bump per release
    private final Button exportBtn = new Button("Export");
    private final MenuButton viewsMenu = new MenuButton("Views");   // Biggest / Big & old / File types
    private final MenuButton recentMenu = new MenuButton("Recent"); // recently-scanned roots, reopen from cache
    private boolean typesMode;                         // showing the file-type breakdown pane
    private ScrollPane typesPane;
    private static FileLock instanceLock;              // held for the process lifetime (single-instance guard)

    private BorderPane rootPane;
    private final Button assistantBtn = new Button("Assistant");
    private VBox assistantPanel;
    private final ComboBox<String> modelPicker = new ComboBox<>();
    private final VBox chatLog = new VBox(8);
    private ScrollPane chatScroll;
    private final List<Ollama.Msg> history = new ArrayList<>();
    private Path historyFile;   // this chat session's on-disk log (JSONL), created on first message
    private javafx.scene.Node thinking;
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

    private double zoom = 1, panX = 0, panY = 0;              // Map-view pan/zoom transform
    private double dragAnchorX, dragAnchorY, dragPanX, dragPanY;

    private final String shotPath = System.getProperty("diskbloom.shot");
    private Scene scene;

    private record Rect(Node node, double x, double y, double w, double h) {}
    private record Tile(Node node, double x, double y, double w, double h, Color color, int depth, boolean leaf) {}

    public static void main(String[] args) {
        if (System.getProperty("diskbloom.selftest") != null) {
            try { selfTest(); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
            System.exit(0);   // force exit: a JavaFX class-load can leave a non-daemon thread
        }
        launch(args);
    }

    // java -ea -Ddiskbloom.selftest=1 --add-modules javafx.controls,javafx.swing -cp out dev.diskbloom.ui.App
    private static void selfTest() throws Exception {
        assert isProtected(Paths.get("C:\\cc\\diskbloom\\.git\\objects\\ab\\cd")) : ".git guarded";
        assert isProtected(Paths.get("C:\\Windows\\System32\\evil.dll")) : "system guarded";
        assert isProtected(Paths.get("C:\\Program Files\\App\\a.exe")) : "program files guarded";
        assert !isProtected(Paths.get("C:\\Users\\me\\Downloads\\big.iso")) : "downloads allowed";
        Node javaFile = Scanner.shallow(Paths.get("C:\\x\\App.java"));
        assert parseQuery(".java").test(javaFile) && parseQuery("*.java").test(javaFile) : "ext match";
        assert parseQuery("app").test(javaFile) : "name substring";
        assert parseQuery("type:code").test(javaFile) : "type match";
        assert !parseQuery(".mp4").test(javaFile) : "ext non-match";
        Node noExt = Scanner.shallow(Paths.get("C:\\x\\LICENSE"));   // the "(no extension)" bucket
        assert extOf(noExt).isEmpty() && !extOf(javaFile).isEmpty() : "no-extension detection";

        // duplicate detection: two identical files + one different -> exactly one pair
        Path d = Files.createTempDirectory("dbdup");
        Files.writeString(d.resolve("a.txt"), "same-content-here");
        Files.writeString(d.resolve("b.txt"), "same-content-here");
        Files.writeString(d.resolve("c.txt"), "different-content");
        int[] prog = {0, 0};
        List<List<Node>> g = findDupes(Scanner.scan(d), (h, t) -> { prog[0] = h; prog[1] = t; });
        assert g.size() == 1 && g.get(0).size() == 2 : "expected one duplicate pair, got " + g;
        assert prog[1] == 3 && prog[0] == 3 : "dup progress should reach 3/3 (all same-size), got " + prog[0] + "/" + prog[1];
        for (String f : new String[]{"a.txt", "b.txt", "c.txt"}) Files.delete(d.resolve(f));
        Files.delete(d);

        // junk rules: a node_modules dir + a .log file are junk; a normal file is not
        Path j = Files.createTempDirectory("dbjunk");
        Files.createDirectory(j.resolve("node_modules"));
        Files.writeString(j.resolve("node_modules").resolve("x.js"), "x");
        Files.writeString(j.resolve("app.log"), "log");
        Files.writeString(j.resolve("keep.txt"), "keep");
        List<Node> junk = new ArrayList<>();
        collectJunk(Scanner.scan(j), junk);
        assert junk.size() == 2 : "expected node_modules + app.log, got " + junk;
        assert junk.stream().anyMatch(n -> n.dir && n.name.equals("node_modules")) : "node_modules flagged";
        Files.delete(j.resolve("node_modules").resolve("x.js"));
        Files.delete(j.resolve("node_modules"));
        Files.delete(j.resolve("app.log"));
        Files.delete(j.resolve("keep.txt"));
        Files.delete(j);

        // CSV export: header, a real byte count, and RFC-4180 quoting
        assert csvField("a,b").equals("\"a,b\"") : "csv quotes a comma field";
        assert csvField("plain").equals("plain") : "csv leaves a plain field alone";
        Path c = Files.createTempDirectory("dbcsv");
        Files.writeString(c.resolve("a.txt"), "hi");   // 2 bytes
        String csv = toCsv(Scanner.scan(c));
        assert csv.startsWith("Path,Bytes,Size,Type") : "csv header";
        assert csv.contains("a.txt") && csv.contains(",2,") : "csv file row with byte count";
        String js = toJson(Scanner.scan(c));
        assert jsonEsc("a\"b\\c").equals("a\\\"b\\\\c") : "json escaping";
        assert js.contains("\"bytes\":2") && js.contains("\"dir\":true") && js.contains("\"children\":[") : "json shape";
        assert js.contains("\"name\":\"a.txt\"") : "json child node";
        Files.delete(c.resolve("a.txt"));
        Files.delete(c);

        // export-subtree: exporting a child node yields only that subtree, not its siblings
        Path et = Files.createTempDirectory("dbexp");
        Files.createDirectory(et.resolve("sub"));
        Files.writeString(et.resolve("sub").resolve("x.txt"), "xx");
        Files.writeString(et.resolve("top.txt"), "top");
        Node subNode = null;
        for (Node ch : Scanner.scan(et).children) if (ch.name.equals("sub")) subNode = ch;
        assert subNode != null && toCsv(subNode).contains("x.txt") && !toCsv(subNode).contains("top.txt") : "subtree export excludes siblings";
        Files.walk(et).sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) { } });

        // per-root cache: a saved scan loads back for the same root, not for another
        assert hashName(Paths.get("C:\\Users")).equals(hashName(Paths.get("c:\\users"))) : "cache key case-stable";
        assert !hashName(Paths.get("C:\\Users")).equals(hashName(Paths.get("D:\\Users"))) : "cache key per-root";
        Path cc = Files.createTempDirectory("dbcache");
        Files.writeString(cc.resolve("f.dat"), "data");
        ScanCache.save(cacheFileFor(cc), Scanner.scan(cc), 999L);
        assert cachedFor(cc) != null : "cache hit for the same root";
        assert cachedFor(Paths.get("Z:\\definitely\\not\\scanned\\" + System.nanoTime())) == null : "no cache for an unknown root";
        Files.deleteIfExists(cacheFileFor(cc));
        Files.delete(cc.resolve("f.dat"));
        Files.delete(cc);

        // recent scans: two seeded caches come back newest-first
        Path r1 = Files.createTempDirectory("dbrec1");
        Files.writeString(r1.resolve("f"), "aa");
        Path r2 = Files.createTempDirectory("dbrec2");
        Files.writeString(r2.resolve("f"), "bbb");
        ScanCache.save(cacheFileFor(r1), Scanner.scan(r1), 1000L);
        ScanCache.save(cacheFileFor(r2), Scanner.scan(r2), 2000L);   // newer
        List<ScanCache.Meta> rec = recentRoots();
        int i1 = -1, i2 = -1;
        for (int i = 0; i < rec.size(); i++) {
            String p = rec.get(i).root().toString();
            if (p.equals(r1.toString())) i1 = i;
            if (p.equals(r2.toString())) i2 = i;
        }
        assert i1 >= 0 && i2 >= 0 && i2 < i1 : "recent scans newest-first";
        Files.deleteIfExists(cacheFileFor(r1));
        Files.deleteIfExists(cacheFileFor(r2));
        Files.delete(r1.resolve("f")); Files.delete(r1);
        Files.delete(r2.resolve("f")); Files.delete(r2);

        // clear-cache: removes only *.bin, on the given dir (so the test never touches real caches)
        Path cd = Files.createTempDirectory("dbclear");
        Files.writeString(cd.resolve("a.bin"), "x");
        Files.writeString(cd.resolve("b.bin"), "y");
        Files.writeString(cd.resolve("keep.txt"), "z");
        assert clearCacheFiles(cd) == 2 : "should clear 2 .bin files";
        assert Files.exists(cd.resolve("keep.txt")) : "non-.bin files kept";
        assert clearCacheFiles(cd) == 0 : "second clear removes nothing";
        Files.delete(cd.resolve("keep.txt")); Files.delete(cd);

        System.out.println("App self-check OK");
    }

    @Override
    public void start(Stage stage) {
        System.out.println("diskbloom v" + VERSION + " starting");
        if (!headlessMode() && !acquireInstanceLock()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "diskbloom is already running.", ButtonType.OK);
            a.setHeaderText(null);
            a.showAndWait();
            Platform.exit();
            return;
        }
        rootPane = new BorderPane();
        rootPane.setStyle("-fx-background-color:" + BG + ";");
        rootPane.setTop(new VBox(buildToolbar(stage), buildBrowseBar()));
        rootPane.setLeft(buildSidebar());
        rootPane.setCenter(buildCenter());
        rootPane.setBottom(buildStatusBar());

        scene = new Scene(rootPane, 1180, 720, Color.web(BG));
        Theme.apply(scene);
        stage.setTitle("diskbloom v" + VERSION + " — local LLM file manager");
        stage.setScene(scene);
        stage.show();

        assistantPanel = buildAssistantPanel();
        loadSettings();          // applies the saved Ollama endpoint before we probe it
        initAssistant();
        registerShortcuts(stage);

        if (System.getProperty("diskbloom.accels") != null) { printAccels(); return; }
        if (System.getProperty("diskbloom.recent") != null) { runRecent(); return; }
        String browse = System.getProperty("diskbloom.browse");
        if (browse != null) { browseTo(Paths.get(browse)); if (shotPath != null) Platform.runLater(this::exportAndExit); return; }
        String measureDemo = System.getProperty("diskbloom.measuredemo");   // browse + measure child folders, for a shot
        if (measureDemo != null) {
            browseTo(Paths.get(measureDemo));
            if (browseRoot != null && browseRoot.children != null)
                for (Node c : browseRoot.children) if (c.dir) c.size = Scanner.sizeOf(c.path);
            tree.refresh(); list.refresh();
            if (shotPath != null) Platform.runLater(this::exportAndExit);
            return;
        }
        List<String> params = getParameters().getRaw();
        if (!params.isEmpty()) { scan(Paths.get(params.get(0))); return; }
        ScanCache.Cached cached = newestCache();
        if (cached != null) {
            showCached(cached);
            if (shotPath != null) Platform.runLater(this::exportAndExit);
        }
        else if (autoScan) scan(systemRoot());
        else {
            showStart();
            status.setText("Pick a drive or folder to scan — or turn on auto-scan in Settings.");
            if (shotPath != null) Platform.runLater(this::exportAndExit);
        }
    }

    private static Path systemRoot() {
        Path home = Paths.get(System.getProperty("user.home"));
        Path root = home.getRoot();
        return root != null ? root : home;
    }

    // ---- keyboard shortcuts ---------------------------------------------

    private void registerShortcuts(Stage stage) {
        var acc = scene.getAccelerators();
        acc.put(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN), () -> chooseAndScan(stage));   // open folder
        acc.put(new KeyCodeCombination(KeyCode.F5), () -> { Node r = stack.peekLast(); scan(r != null ? r.path : systemRoot()); }); // rescan
        acc.put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), searchField::requestFocus);    // focus search
        acc.put(new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN), this::chooseAndExport); // export
        acc.put(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN), this::goUp);                    // up a level
        acc.put(new KeyCodeCombination(KeyCode.ESCAPE), this::onEscape);                                       // clear search / leave view
    }

    // Escape: drop a search first, otherwise leave any analysis view back to folders.
    private void onEscape() {
        if (searchHits != null || !searchField.getText().isEmpty()) { searchField.clear(); clearSearch(); }
        else if (biggestMode || typesMode) showFolders();
    }

    // Headless hook: -Ddiskbloom.accels prints the registered shortcuts and exits.
    private void printAccels() {
        System.out.println("=== SHORTCUTS ===");
        for (KeyCombination k : scene.getAccelerators().keySet()) System.out.println(k.getDisplayText());
        Platform.exit();
    }

    private static Path diskbloomDir() {
        String base = System.getenv("LOCALAPPDATA");
        return (base != null ? Paths.get(base) : Paths.get(System.getProperty("user.home"))).resolve("diskbloom");
    }

    private static Path cacheFile() { return diskbloomDir().resolve("lastscan.bin"); }  // legacy single slot
    private static Path cacheDir() { return diskbloomDir().resolve("cache"); }

    // A stable per-root file name; the stored root path is re-checked on load, so a hash collision is harmless.
    private static String hashName(Path root) {
        String key = root.toAbsolutePath().toString().toLowerCase();
        return Integer.toHexString(key.hashCode()) + "_" + key.length();
    }

    private static Path cacheFileFor(Path root) { return cacheDir().resolve(hashName(root) + ".bin"); }

    private static boolean usableCache(ScanCache.Cached c) {
        return c != null && c.root() != null && c.root().children != null && !c.root().children.isEmpty();
    }

    /** A cached scan for exactly this root, or null (missing / stale format / path mismatch). */
    private static ScanCache.Cached cachedFor(Path root) {
        ScanCache.Cached c = ScanCache.load(cacheFileFor(root));
        if (!usableCache(c)) return null;
        return c.root().path.toAbsolutePath().toString().equalsIgnoreCase(root.toAbsolutePath().toString()) ? c : null;
    }

    /** The most recently saved scan across all roots (for launch), or the legacy single slot. */
    private static ScanCache.Cached newestCache() {
        Path dir = cacheDir();
        Path newest = null;
        long best = Long.MIN_VALUE;
        if (Files.isDirectory(dir)) {
            try (var s = Files.newDirectoryStream(dir, "*.bin")) {
                for (Path p : s) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > best) { best = t; newest = p; }
                }
            } catch (Exception ignore) { }
        }
        if (newest != null) { ScanCache.Cached c = ScanCache.load(newest); if (usableCache(c)) return c; }
        ScanCache.Cached legacy = ScanCache.load(cacheFile());   // migrate old single-slot users
        return usableCache(legacy) ? legacy : null;
    }

    /** Show this root's cached scan instantly if we have one; otherwise walk it. */
    private void openOrScan(Path root) {
        ScanCache.Cached c = cachedFor(root);
        if (c != null) showCached(c);
        else scan(root);
    }

    /** Every cached scan's root/size/time, newest first — for the Recent menu. */
    private static List<ScanCache.Meta> recentRoots() {
        Path dir = cacheDir();
        List<ScanCache.Meta> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var s = Files.newDirectoryStream(dir, "*.bin")) {
                for (Path p : s) { ScanCache.Meta m = ScanCache.peek(p); if (m != null) out.add(m); }
            } catch (Exception ignore) { }
        }
        out.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
        return out;
    }

    private void populateRecent() {
        recentMenu.getItems().clear();
        List<ScanCache.Meta> recents = recentRoots();
        if (recents.isEmpty()) {
            MenuItem none = new MenuItem("No recent scans yet");
            none.setDisable(true);
            recentMenu.getItems().add(none);
            return;
        }
        int n = 0;
        for (ScanCache.Meta m : recents) {
            if (n++ >= 12) break;
            String when = new java.text.SimpleDateFormat("MMM d, HH:mm").format(new java.util.Date(m.timestamp()));
            MenuItem mi = new MenuItem(m.root() + "    ·  " + Sizes.human(m.size()) + "  ·  " + when);
            mi.setOnAction(a -> openOrScan(m.root()));   // cache hit -> opens instantly
            recentMenu.getItems().add(mi);
        }
        recentMenu.getItems().add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem("Clear recent scans…");
        clear.setOnAction(a -> clearRecent());
        recentMenu.getItems().add(clear);
    }

    private void clearRecent() {
        List<ScanCache.Meta> recents = recentRoots();
        if (recents.isEmpty()) { status.setText("No cached scans to clear."); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Clear " + recents.size() + " cached scan(s)? Those folders will be re-scanned next time you open them.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Clear recent scans");
        a.setHeaderText(null);
        Theme.apply(a.getDialogPane());
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        int removed = clearCacheFiles(cacheDir());
        try { Files.deleteIfExists(cacheFile()); } catch (Exception ignore) { }   // legacy single slot
        status.setText("Cleared " + removed + " cached scan(s).");
    }

    /** Delete every *.bin cache file in a directory; returns the count removed. */
    private static int clearCacheFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        List<Path> files = new ArrayList<>();
        try (var s = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path p : s) files.add(p);      // collect first — don't delete mid-iteration
        } catch (Exception ignore) { return 0; }
        int n = 0;
        for (Path p : files) { try { Files.delete(p); n++; } catch (Exception ignore) { } }
        return n;
    }

    // Headless hook: -Ddiskbloom.recent prints the recent-scans list and exits.
    private void runRecent() {
        System.out.println("=== RECENT SCANS ===");
        for (ScanCache.Meta m : recentRoots())
            System.out.println(Sizes.human(m.size()) + "  "
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(m.timestamp())) + "  " + m.root());
        Platform.exit();
    }

    private static Path settingsFile() {
        String base = System.getenv("LOCALAPPDATA");
        Path dir = (base != null ? Paths.get(base) : Paths.get(System.getProperty("user.home"))).resolve("diskbloom");
        return dir.resolve("settings.properties");
    }

    private void loadSettings() {
        Properties p = new Properties();
        Path f = settingsFile();
        if (Files.exists(f)) { try (var r = Files.newBufferedReader(f)) { p.load(r); } catch (Exception ignore) { } }
        autoScan = Boolean.parseBoolean(p.getProperty("autoScan", "false"));
        String url = p.getProperty("ollamaUrl");
        if (url != null && !url.isBlank()) Ollama.setBase(url);
    }

    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("autoScan", String.valueOf(autoScan));
        p.setProperty("ollamaUrl", Ollama.getBase());
        Path f = settingsFile();
        try {
            Files.createDirectories(f.getParent());
            try (var w = Files.newBufferedWriter(f)) { p.store(w, "diskbloom settings"); }
        } catch (Exception ignore) { }
    }

    /** True in the PNG-shot and headless test hooks — those skip the single-instance lock. */
    private boolean headlessMode() {
        if (shotPath != null) return true;
        for (String k : new String[]{"selftest", "ask", "analyze", "dupes", "junk", "exportcsv", "recent", "accels"})
            if (System.getProperty("diskbloom." + k) != null) return true;
        return false;
    }

    // Single-instance guard: hold an exclusive lock on a file for the process lifetime.
    private static boolean acquireInstanceLock() {
        try {
            String base = System.getenv("LOCALAPPDATA");
            Path f = (base != null ? Paths.get(base) : Paths.get(System.getProperty("user.home")))
                    .resolve("diskbloom").resolve("instance.lock");
            Files.createDirectories(f.getParent());
            FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = ch.tryLock();
            if (lock == null) { ch.close(); return false; }
            instanceLock = lock;   // keep the reference alive for the whole process
            return true;
        } catch (Exception e) { return true; }   // never block startup on a lock error
    }

    private void showSettings() {
        CheckBox auto = new CheckBox("Scan automatically on launch");
        auto.setSelected(autoScan);
        Label autoNote = new Label("When off, diskbloom opens to a start screen (or your last cached scan) and only scans when you ask.");
        autoNote.setWrapText(true); autoNote.setMaxWidth(380);
        autoNote.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");

        Region sep = new Region(); sep.setPrefHeight(1); sep.setStyle("-fx-background-color:" + LINE + ";");

        Label aiLabel = new Label("AI server (Ollama)");
        aiLabel.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:12px; -fx-font-weight:bold;");
        TextField aiUrl = new TextField(Ollama.getBase());
        HBox.setHgrow(aiUrl, Priority.ALWAYS);
        Button test = new Button("Test / auto-detect");
        Label aiStatus = new Label("");
        aiStatus.setWrapText(true); aiStatus.setMaxWidth(380);
        aiStatus.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        test.setOnAction(e -> {
            String typed = aiUrl.getText();
            aiStatus.setText("Checking…");
            new Thread(() -> {
                if (typed != null && !typed.isBlank()) Ollama.setBase(typed);
                List<String> m = Ollama.autodetect();
                Platform.runLater(() -> {
                    aiUrl.setText(Ollama.getBase());
                    aiStatus.setText(m.isEmpty()
                            ? "No Ollama server found. Is it running? Install from ollama.com, then Test again."
                            : "Connected — " + m.size() + " model(s) at " + Ollama.getBase());
                });
            }, "ollama-test").start();
        });
        Label aiNote = new Label("The assistant runs a local model via Ollama (default http://localhost:11434). Nothing leaves your PC.");
        aiNote.setWrapText(true); aiNote.setMaxWidth(380);
        aiNote.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");

        VBox content = new VBox(10, auto, autoNote, sep, aiLabel, new HBox(6, aiUrl, test), aiStatus, aiNote);
        content.setPadding(new Insets(6, 4, 4, 4));
        content.setPrefWidth(400);

        Dialog<Void> d = new Dialog<>();
        d.setTitle("Settings");
        d.getDialogPane().setHeaderText("diskbloom settings");
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Theme.apply(d.getDialogPane());
        d.showAndWait();

        autoScan = auto.isSelected();
        Ollama.setBase(aiUrl.getText());
        saveSettings();
        initAssistant();   // re-probe with the (possibly changed) endpoint
    }

    private void showCached(ScanCache.Cached cached) {
        setDriveUsage(cached.root().path);
        stack.clear();
        stack.push(cached.root());
        selected = null;
        biggestMode = false;
        browseRoot = null;
        searchHits = null; searchLabel = "";
        addressField.setText(cached.root().path.toString());
        showResults();
        showCurrent();
        buildTree(cached.root());
        String when = new java.text.SimpleDateFormat("MMM d, HH:mm").format(new java.util.Date(cached.timestamp()));
        status.setText("Showing cached scan from " + when + "  ·  click Rescan to refresh");
    }

    // ---- layout ----------------------------------------------------------

    private HBox buildToolbar(Stage stage) {
        Button open = new Button("Open folder…");
        open.setOnAction(e -> chooseAndScan(stage));
        open.setTooltip(new Tooltip("Open a folder to scan  (Ctrl+O)"));
        Button rescanBtn = new Button("Rescan");
        rescanBtn.setOnAction(e -> { Node r = stack.peekLast(); scan(r != null ? r.path : systemRoot()); });
        rescanBtn.setTooltip(new Tooltip("Rescan the current folder  (F5)"));
        exportBtn.setTooltip(new Tooltip("Export the scan to CSV or JSON  (Ctrl+E)"));
        upBtn.setOnAction(e -> goUp());
        upBtn.setDisable(true);
        crumb.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:13px;");
        HBox.setHgrow(crumb, Priority.ALWAYS);

        MenuItem miBiggest = new MenuItem("Biggest files");
        miBiggest.setOnAction(e -> enterBiggest());
        MenuItem miBigOld = new MenuItem("Big & old files");
        miBigOld.setOnAction(e -> enterBigOld());
        MenuItem miTypes = new MenuItem("File types");
        miTypes.setOnAction(e -> enterTypes());
        MenuItem miFolders = new MenuItem("Folders (normal view)");
        miFolders.setOnAction(e -> showFolders());
        viewsMenu.getItems().setAll(miBiggest, miBigOld, miTypes, new SeparatorMenuItem(), miFolders);
        recentMenu.setOnShowing(e -> populateRecent());
        dupBtn.setOnAction(e -> findDuplicates());
        exportBtn.setOnAction(e -> chooseAndExport());
        viewBtn.setOnAction(e -> toggleView());
        assistantBtn.setDisable(true);
        assistantBtn.setOnAction(e -> toggleAssistant());
        settingsBtn.setOnAction(e -> showSettings());
        HBox bar = new HBox(10, open, rescanBtn, recentMenu, upBtn, viewsMenu, dupBtn, exportBtn, viewBtn, crumb, assistantBtn, settingsBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color:#2b2b2b; -fx-border-color:" + LINE + "; -fx-border-width:0 0 1 0;");
        return bar;
    }

    private HBox buildBrowseBar() {
        driveBox.setPromptText("Drive");
        for (File r : File.listRoots()) if (r.exists()) driveBox.getItems().add(r.getPath());
        driveBox.setOnAction(e -> { String d = driveBox.getValue(); if (d != null) browseTo(Paths.get(d)); });

        addressField.setPromptText("Path — e.g. C:\\Users  (Enter to open)");
        HBox.setHgrow(addressField, Priority.ALWAYS);
        addressField.setOnAction(e -> go(addressField.getText()));
        Button goBtn = new Button("Go");
        goBtn.setOnAction(e -> go(addressField.getText()));
        measureBtn.setTooltip(new Tooltip("Compute the size of every folder in this listing (browse mode)"));
        measureBtn.setOnAction(e -> measureVisible());

        searchField.setPromptText("Search  ·  name, .mp4, or type:video");
        searchField.setPrefWidth(240);
        searchField.setOnAction(e -> runSearch(searchField.getText()));
        Button clearBtn = new Button("✕");
        clearBtn.setTooltip(new Tooltip("Clear search"));
        clearBtn.setOnAction(e -> { searchField.clear(); clearSearch(); });

        HBox bar = new HBox(8, driveBox, addressField, goBtn, measureBtn, searchField, clearBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setStyle("-fx-background-color:#262626; -fx-border-color:" + LINE + "; -fx-border-width:0 0 1 0;");
        return bar;
    }

    private void go(String text) {
        if (text == null || text.isBlank()) return;
        Path p = Paths.get(text.trim());
        if (Files.exists(p)) browseTo(p);
        else status.setText("No such path: " + text.trim());
    }

    private VBox buildSidebar() {
        Label brand = new Label("diskbloom");
        brand.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:16px; -fx-font-weight:bold;");
        Label ver = new Label("v" + VERSION);
        ver.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");
        HBox brandRow = new HBox(6, brand, ver);
        brandRow.setAlignment(Pos.BOTTOM_LEFT);
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
            if (e.getClickCount() == 2) navigateInto(list.getSelectionModel().getSelectedItem());
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

        VBox box = new VBox(4, brandRow, sTitle, sSize, sCount, driveBar, driveLbl,
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
            double wx = toWorldX(e.getX()), wy = toWorldY(e.getY());
            if (e.getClickCount() == 2) {
                Node d = dirAt(wx, wy);
                if (d != null) drill(d);
                else { zoom = 1; panX = 0; panY = 0; draw(); }   // double-click empty space -> reset zoom
            } else {
                Tile t = tileAt(wx, wy);
                if (t == null) return;
                if (list.getItems().contains(t.node())) list.getSelectionModel().select(t.node());
                else { selected = t.node(); updateStatus(t.node()); draw(); }
            }
        });
        canvas.setOnMouseMoved(e -> {
            Tile t = tileAt(toWorldX(e.getX()), toWorldY(e.getY()));
            if (t != null) updateStatus(t.node());
        });
        canvas.setOnContextMenuRequested(e -> {
            Tile t = tileAt(toWorldX(e.getX()), toWorldY(e.getY()));
            if (t != null) {
                selected = t.node();
                draw();
                menuFor(t.node()).show(canvas, e.getScreenX(), e.getScreenY());
            }
        });
        canvas.setOnScroll(e -> {
            double nz = clamp(zoom * (e.getDeltaY() > 0 ? 1.15 : 1 / 1.15), 1.0, 24.0);
            if (nz == zoom) return;
            double wx = toWorldX(e.getX()), wy = toWorldY(e.getY());
            zoom = nz;
            panX = e.getX() - wx * zoom;   // keep the point under the cursor fixed while zooming
            panY = e.getY() - wy * zoom;
            clampPan();
            draw();
        });
        canvas.setOnMousePressed(e -> { dragAnchorX = e.getX(); dragAnchorY = e.getY(); dragPanX = panX; dragPanY = panY; });
        canvas.setOnMouseDragged(e -> {
            if (zoom <= 1.0) return;
            panX = dragPanX + (e.getX() - dragAnchorX);
            panY = dragPanY + (e.getY() - dragAnchorY);
            clampPan();
            draw();
        });

        configureTree();
        typesPane = new ScrollPane();
        typesPane.setFitToWidth(true);
        typesPane.setVisible(false);
        typesPane.setStyle("-fx-background:" + BG + "; -fx-background-color:" + BG + ";");
        startPane = buildStartPane();
        scanPane = buildScanPane();
        scanPane.setVisible(false);
        applyView();
        return new StackPane(holder, tree, typesPane, startPane, scanPane);
    }

    private VBox buildStartPane() {
        Label title = new Label("diskbloom");
        title.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:32px; -fx-font-weight:bold;");
        Label sub = new Label("Pick a drive to see what's using space  ·  or Open folder… above");
        sub.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:14px;");
        VBox drives = new VBox(10);
        drives.setAlignment(Pos.CENTER);
        drives.setMaxWidth(580);
        for (File r : File.listRoots()) {
            if (!r.exists()) continue;
            drives.getChildren().add(driveCard(r.toPath()));
        }
        VBox v = new VBox(20, title, sub, drives);
        v.setAlignment(Pos.CENTER);
        v.setStyle("-fx-background-color:" + BG + ";");
        return v;
    }

    // A clickable drive row: name, a used/total bar, free space, and a "cached" badge when we can open it instantly.
    private HBox driveCard(Path root) {
        long total = 0, usable = 0;
        try { FileStore fs = Files.getFileStore(root); total = fs.getTotalSpace(); usable = fs.getUsableSpace(); } catch (Exception ignore) { }
        long used = total - usable;

        Label name = new Label(root.toString());
        name.setMinWidth(52);
        name.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:16px; -fx-font-weight:bold;");
        ProgressBar bar = new ProgressBar(total > 0 ? (double) used / total : 0);
        bar.setPrefHeight(10);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        Label info = new Label(total > 0 ? Sizes.human(usable) + " free of " + Sizes.human(total) : "unavailable");
        info.setMinWidth(160);
        info.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:12px;");
        Label badge = new Label(cachedFor(root) != null ? "cached ✓" : "");
        badge.setMinWidth(58);
        badge.setStyle("-fx-text-fill:#6fae8a; -fx-font-size:11px; -fx-font-weight:bold;");

        HBox card = new HBox(14, name, bar, info, badge);
        card.setAlignment(Pos.CENTER_LEFT);
        // card must be lighter than the progress-bar track (#2a2a2c) so the unfilled part of the bar is visible
        String base = "-fx-background-color:#33343a; -fx-background-radius:8; -fx-border-radius:8; -fx-border-color:#3f4048; -fx-border-width:1; -fx-cursor:hand; -fx-padding:14 18;";
        String hover = base.replace("#33343a", "#3c3d45").replace("#3f4048", "#4f505a");
        card.setStyle(base);
        card.setOnMouseEntered(e -> card.setStyle(hover));
        card.setOnMouseExited(e -> card.setStyle(base));
        card.setOnMouseClicked(e -> openOrScan(root));
        return card;
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
    private void showResults() { startPane.setVisible(false); scanPane.setVisible(false); applyView(); }

    private void applyView() {
        holder.setVisible(!listView && !typesMode);
        tree.setVisible(listView && !typesMode);
        if (typesPane != null) typesPane.setVisible(typesMode);
    }

    private void toggleView() {
        listView = !listView;
        viewBtn.setText(listView ? "Map view" : "List view");
        applyView();
        if (listView) rebuildTree();
        else {
            render();
            status.setText("Map view  ·  scroll to zoom, drag to pan, double-click a folder to open (empty space resets)");
        }
    }

    private void configureTree() {
        tree.setShowRoot(true);
        tree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY);
        TreeTableColumn<Node, String> nameCol = new TreeTableColumn<>("Name");
        nameCol.setCellValueFactory(p -> new ReadOnlyStringWrapper(p.getValue().getValue().name + (p.getValue().getValue().dir ? "\\" : "")));
        nameCol.setPrefWidth(380);
        TreeTableColumn<Node, Node> barCol = new TreeTableColumn<>("Share of folder");
        barCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().getValue()));
        barCol.setCellFactory(c -> new BarCell());
        barCol.setPrefWidth(200);
        barCol.setSortable(false);
        TreeTableColumn<Node, String> sizeCol = new TreeTableColumn<>("Size");
        sizeCol.setCellValueFactory(p -> {
            Node v = p.getValue().getValue();
            return new ReadOnlyStringWrapper(v.dir && v.size == 0 ? "" : Sizes.human(v.size)); // live folder size unknown
        });
        sizeCol.setPrefWidth(90);
        sizeCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        tree.getColumns().add(nameCol);
        tree.getColumns().add(barCol);
        tree.getColumns().add(sizeCol);
        tree.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> { if (nv != null) updateStatus(nv.getValue()); });
        tree.setOnContextMenuRequested(e -> {
            TreeItem<Node> it = tree.getSelectionModel().getSelectedItem();
            if (it != null) menuFor(it.getValue()).show(tree, e.getScreenX(), e.getScreenY());
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<Node> it = tree.getSelectionModel().getSelectedItem();
                if (it != null) navigateInto(it.getValue());
            }
        });
    }

    private void buildTree(Node root) {
        if (root == null) { tree.setRoot(null); return; }
        TreeItem<Node> r = lazyItem(root);
        r.setExpanded(true);
        tree.setRoot(r);
    }

    private static TreeItem<Node> lazyItem(Node n) {
        TreeItem<Node> item = new TreeItem<>(n) {
            @Override public boolean isLeaf() { return !n.dir; }   // folders always show a disclosure arrow
        };
        if (n.dir) item.expandedProperty().addListener((obs, was, is) -> {
            if (is && item.getChildren().isEmpty()) {
                if (n.children == null) Scanner.listChildren(n);   // live-load from disk on first expand
                if (n.children != null) {
                    List<Node> kids = new ArrayList<>(n.children);
                    kids.sort(Scanner.BROWSE_ORDER);
                    for (Node c : kids) item.getChildren().add(lazyItem(c));
                }
            }
        });
        return item;
    }

    // ---- scanning + navigation ------------------------------------------

    private void chooseAndScan(Stage stage) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose a folder to scan");
        Node cur = stack.peek();
        File init = cur != null ? cur.path.toFile() : new File(System.getProperty("user.home"));
        if (init.isDirectory()) dc.setInitialDirectory(init);
        File dir = dc.showDialog(stage);
        if (dir != null) openOrScan(dir.toPath());
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
            browseRoot = null;
            searchHits = null; searchLabel = "";
            addressField.setText(result.path.toString());
            showResults();
            showCurrent();
            buildTree(result);
            boolean usable = result.dir && result.children != null && !result.children.isEmpty();
            if (!usable) status.setText("Nothing to show for " + root + " — is that path valid and accessible?");
            final Node toCache = result;
            final Path cacheTarget = cacheFileFor(root);
            if (shotPath == null && usable) new Thread(() -> {
                try { ScanCache.save(cacheTarget, toCache, System.currentTimeMillis()); } catch (Exception ignore) { }
            }, "diskbloom-cache-save").start();
            String ask = System.getProperty("diskbloom.ask");
            if (ask != null) { runAsk(ask); return; }
            if (System.getProperty("diskbloom.analyze") != null) { runAnalyze(); return; }
            if (System.getProperty("diskbloom.dupes") != null) { runDupes(); return; }
            if (System.getProperty("diskbloom.junk") != null) { runJunk(); return; }
            String exportCsv = System.getProperty("diskbloom.exportcsv");
            if (exportCsv != null) { runExport(exportCsv); return; }
            if (System.getProperty("diskbloom.biggest") != null) enterBiggest();
            if (System.getProperty("diskbloom.types") != null) enterTypes();
            if (System.getProperty("diskbloom.bigold") != null) enterBigOld();
            if (System.getProperty("diskbloom.junkui") != null) findJunk();
            if (System.getProperty("diskbloom.map") != null) Platform.runLater(() -> {
                listView = false; viewBtn.setText("List view"); applyView(); render();
                String z = System.getProperty("diskbloom.zoom");
                if (z != null) {
                    zoom = Double.parseDouble(z);
                    panX = canvas.getWidth() * (1 - zoom) / 2;    // centre the zoom
                    panY = canvas.getHeight() * (1 - zoom) / 2;
                    clampPan(); draw();
                }
            });
            String search = System.getProperty("diskbloom.search");
            if (search != null) runSearch(search);
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
        typesMode = false;
        Node cur = stack.peek();
        upBtn.setDisable(stack.size() <= 1);
        applyView();
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

    // ---- live file browsing (Explorer-style, no scan needed) ------------

    /** Open a folder live (immediate children listed from disk, folders lazily on expand). */
    private void browseTo(Path p) {
        if (p == null) return;
        clearSearchState();
        Node n = Scanner.shallow(p);
        if (!n.dir) { Path par = p.getParent(); if (par == null) return; n = Scanner.shallow(par); }
        Scanner.listChildren(n);
        browseRoot = n;
        biggestMode = false;
        typesMode = false;
        selected = null;
        if (!listView) { listView = true; viewBtn.setText("Map view"); }

        addressField.setText(n.path.toString());
        setDriveUsage(n.path);
        updateSidebar(n);
        currentTotal = 0;                          // live folder size is unknown until scanned
        crumb.setText(n.path.toString());
        upBtn.setDisable(n.path.getParent() == null);

        List<Node> kids = n.children != null ? n.children : List.of();
        dominant.clear();
        for (Node k : kids) dominant.put(k, catOf(k));   // shallow: file's own type, folder -> FOLDER
        list.getItems().setAll(kids);
        catSums.clear();
        updateLegend();

        showResults();
        buildTree(n);
        status.setText("Browsing " + n.path + "  ·  double-click a folder to open, right-click to scan or delete");
    }

    private void goUp() {
        if (browseRoot != null) { Path par = browseRoot.path.getParent(); if (par != null) browseTo(par); return; }
        if (stack.size() > 1) { stack.pop(); selected = null; showCurrent(); if (listView) buildTree(stack.peek()); }
    }

    /** Double-click a folder: live-browse it when browsing, else drill within the scan. */
    private void navigateInto(Node n) {
        if (n == null || !n.dir) return;
        if (browseRoot != null) { browseTo(n.path); return; }
        drill(n);
        if (listView) buildTree(stack.peek());
    }

    // ---- rendering -------------------------------------------------------

    private void render() {
        zoom = 1; panX = 0; panY = 0;   // a fresh layout resets the pan/zoom
        tiles.clear();
        topTiles.clear();
        double W = canvas.getWidth(), H = canvas.getHeight();
        if (W >= 2 && H >= 2) {
            if (searchHits != null) {
                if (!searchHits.isEmpty()) layoutFlat(searchHits, W, H);
            } else if (biggestMode) {
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
        g.save();
        g.translate(panX, panY);   // Map-view pan/zoom
        g.scale(zoom, zoom);
        for (Tile t : tiles) {
            if (t.leaf()) drawLeaf(g, t);
            else drawFolder(g, t);
        }
        g.restore();
    }

    // screen -> layout(world) coordinates for hit-testing under the pan/zoom transform
    private double toWorldX(double sx) { return (sx - panX) / zoom; }
    private double toWorldY(double sy) { return (sy - panY) / zoom; }

    private void clampPan() {
        if (zoom <= 1.0) { panX = 0; panY = 0; return; }
        double W = canvas.getWidth(), H = canvas.getHeight();
        panX = Math.min(0, Math.max(W - W * zoom, panX));   // keep the scaled content covering the viewport
        panY = Math.min(0, Math.max(H - H * zoom, panY));
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

    private void drawFolder(GraphicsContext g, Tile t) {
        double x = t.x() + GAP, y = t.y() + GAP, w = t.w() - GAP * 2, h = t.h() - GAP * 2;
        if (w <= 0 || h <= 0) return;
        g.setFill(Color.web(CONTAINER));
        g.fillRoundRect(x, y, w, h, ARC, ARC);
        if (t.node() == selected) {
            g.setStroke(Color.WHITE);
            g.setLineWidth(2);
            g.strokeRoundRect(x + 1, y + 1, w - 2, h - 2, ARC, ARC);
        }
        if (h > 18 && w > 52) {
            g.setFill(Color.web("#c2c2c2"));
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
            g.fillText(clip(t.node().name + "\\", w - 12), x + 7, y + 13);
        }
    }

    private void drawLeaf(GraphicsContext g, Tile t) {
        Color c = shade(t.color(), t.node());
        if (t.w() < 7 || t.h() < 7) { // too small for a gap/round -> plain fill so dense areas stay solid, not dotty
            g.setFill(c);
            g.fillRect(t.x(), t.y(), t.w(), t.h());
            return;
        }
        double x = t.x() + GAP, y = t.y() + GAP, w = t.w() - GAP * 2, h = t.h() - GAP * 2;
        g.setFill(c);
        g.fillRoundRect(x, y, w, h, ARC, ARC);
        if (t.node() == selected) {
            g.setStroke(Color.WHITE);
            g.setLineWidth(2);
            g.strokeRoundRect(x + 1, y + 1, w - 2, h - 2, ARC, ARC);
        }
        if (w > 46 && h > 22) {
            g.setFill(luminance(c) > 0.55 ? Color.web("#141414") : Color.web("#f6f6f6"));
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
            g.fillText(clip(t.node().name + (t.node().dir ? "\\" : ""), w - 12), x + 6, y + 16);
            if (h > 36) {
                g.setFont(Font.font("Segoe UI", 11));
                g.fillText(Sizes.human(t.node().size), x + 6, y + 30);
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

    // subtle per-tile brightness variation so a folder full of one file type
    // reads as a mosaic instead of a flat wall
    private static Color shade(Color base, Node n) {
        double f = 0.9 + ((n.name.hashCode() >>> 24) & 0xFF) / 255.0 * 0.2;
        return base.deriveColor(0, 1, f, 1);
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
            + "reviews and approves every deletion; you cannot delete anything yourself. Never suggest OS, "
            + "system, program, or version-control (.git) files.";

    private VBox buildAssistantPanel() {
        Label title = new Label("Assistant");
        title.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:16px; -fx-font-weight:bold;");
        Button newChatBtn = new Button("New chat");
        newChatBtn.setStyle("-fx-font-size:11px;");
        newChatBtn.setOnAction(e -> newChat());
        Button historyBtn = new Button("History");
        historyBtn.setStyle("-fx-font-size:11px;");
        historyBtn.setTooltip(new Tooltip("Open the folder where your conversations & reports are saved locally"));
        historyBtn.setOnAction(e -> runQuietly(() -> { Files.createDirectories(historyDir()); Desktop.getDesktop().open(historyDir().toFile()); }));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox titleRow = new HBox(8, title, sp, historyBtn, newChatBtn);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        modelPicker.setMaxWidth(Double.MAX_VALUE);

        chatLog.setPadding(new Insets(4, 2, 4, 2));
        chatScroll = new ScrollPane(chatLog);
        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background:" + PANEL + "; -fx-background-color:" + PANEL + ";");
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        VBox presets = new VBox(6);
        Button driveBtn = new Button("Analyze drive & optimize (AI)");
        driveBtn.setMaxWidth(Double.MAX_VALUE);
        driveBtn.getStyleClass().add("accent");
        driveBtn.setOnAction(e -> analyzeDrive());
        presets.getChildren().add(driveBtn);
        Button analyzeBtn = new Button("Analyze my biggest files");
        analyzeBtn.setMaxWidth(Double.MAX_VALUE);
        analyzeBtn.setStyle("-fx-font-size:11px;");
        analyzeBtn.setOnAction(e -> analyzeCleanup());
        presets.getChildren().add(analyzeBtn);
        Button junkBtn = new Button("Find junk files (no AI)");
        junkBtn.setMaxWidth(Double.MAX_VALUE);
        junkBtn.setStyle("-fx-font-size:11px;");
        junkBtn.setOnAction(e -> findJunk());
        presets.getChildren().add(junkBtn);
        for (String p : new String[]{"What's using space?", "What's safe to delete?"}) {
            Button b = new Button(p);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setStyle("-fx-font-size:11px;");
            b.setOnAction(e -> ask(p));
            presets.getChildren().add(b);
        }

        questionField.setPromptText("Message…");
        HBox.setHgrow(questionField, Priority.ALWAYS);
        askBtn.setText("Send");
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
        pScroll.setMaxHeight(150);
        pScroll.setStyle("-fx-background:" + PANEL + "; -fx-background-color:" + PANEL + ";");
        recycleBtn.setMaxWidth(Double.MAX_VALUE);
        recycleBtn.getStyleClass().add("danger");
        recycleBtn.setOnAction(e -> recycleChecked());
        proposalsSection = new VBox(6, pTitle, pScroll, recycleBtn);
        proposalsSection.setVisible(false);
        proposalsSection.setManaged(false);

        VBox v = new VBox(8, titleRow, modelPicker, chatScroll, proposalsSection, presets, askRow, note);
        v.setPadding(new Insets(12));
        v.setPrefWidth(370);
        v.setStyle("-fx-background-color:" + PANEL + "; -fx-border-color:" + LINE + "; -fx-border-width:0 0 0 1;");
        newChat();
        return v;
    }

    private void initAssistant() {
        Thread t = new Thread(() -> {
            List<String> ms = Ollama.autodetect();   // try the configured endpoint, then common local ones
            final List<String> models = ms;
            Platform.runLater(() -> {
                if (models.isEmpty()) {
                    assistantBtn.setDisable(true);
                    assistantBtn.setTooltip(new Tooltip("No Ollama server found at " + Ollama.getBase()
                            + ". Start Ollama, or set the address in Settings."));
                } else {
                    modelPicker.getItems().setAll(models);
                    modelPicker.getSelectionModel().select(models.contains("qwen2.5:14b") ? "qwen2.5:14b" : models.get(0));
                    assistantBtn.setDisable(false);
                    assistantBtn.setTooltip(new Tooltip("Local AI assistant — " + models.size() + " model(s) at " + Ollama.getBase()));
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

    private void ask(String question) { ask(question, question); }

    /** display is shown in the chat bubble; sent is the full prompt handed to the model. */
    private void ask(String display, String sent) {
        if (sent == null || sent.isBlank()) return;
        String model = modelPicker.getSelectionModel().getSelectedItem();
        if (model == null) { addBubble("assistant", "No model available — is Ollama running?"); return; }
        refreshContext();
        addBubble("user", display);
        appendHistory("user", display);
        history.add(new Ollama.Msg("user", sent));
        questionField.clear();
        clearProposals();
        thinking = addBubble("assistant", "…thinking (the first reply loads the model, ~15s)");
        askBtn.setDisable(true);
        questionField.setDisable(true);
        List<Ollama.Msg> convo = new ArrayList<>(history);
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                return Ollama.chat(model, convo);
            }
        };
        task.setOnSucceeded(e -> { finishAsk(); handleResponse(task.getValue()); });
        task.setOnFailed(e -> {
            finishAsk();
            if (thinking != null) { chatLog.getChildren().remove(thinking); thinking = null; }
            addBubble("assistant", "Error: " + task.getException());
        });
        Thread th = new Thread(task, "ollama-chat");
        th.setDaemon(true);
        th.start();
    }

    private void finishAsk() {
        askBtn.setDisable(false);
        questionField.setDisable(false);
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

    // ---- cleanup analyzer -----------------------------------------------

    private void analyzeCleanup() {
        Node root = stack.peekLast();
        if (root == null) { addBubble("assistant", "Scan a folder first (Open folder, or pick a drive) — then I can analyze it."); return; }
        ask("Analyze my biggest files and tell me what's safe to clean up.", cleanupPrompt(root, 30));
    }

    /** List the N biggest files with the KEEP/JUNK + SUGGEST_DELETE protocol for the cleanup analyzer. */
    private static String cleanupPrompt(Node root, int n) {
        PriorityQueue<Node> heap = new PriorityQueue<>(Comparator.comparingLong((Node x) -> x.size));
        collectInto(root, heap);
        List<Node> files = new ArrayList<>(heap);
        files.sort(Comparator.comparingLong((Node x) -> x.size).reversed());
        int k = Math.min(files.size(), n);
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze my biggest files for cleanup. For EACH file, begin a line with KEEP or JUNK and a short reason. ")
          .append("Then give a few lines of overall disk-cleaning advice. ")
          .append("A large file that hasn't been modified in a long time is a stronger JUNK candidate. ")
          .append("For every file clearly safe to remove (caches, temp files, old installers/downloads, logs, duplicates), ")
          .append("also add a line formatted exactly: ").append(DELETE_TAG).append(" <full path>. ")
          .append("Never suggest deleting OS, system, or program files.\n\nMy ").append(k).append(" biggest files (with last-modified date):\n");
        for (int i = 0; i < k; i++) {
            Node f = files.get(i);
            sb.append(Sizes.human(f.size)).append("  ").append(f.path).append("  (modified ").append(modDate(f.path)).append(")\n");
        }
        return sb.toString();
    }

    private static String modDate(Path p) {
        try { return Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }
        catch (Exception e) { return "?"; }
    }

    // Headless hook: -Ddiskbloom.analyze prints the cleanup analysis and exits.
    private void runAnalyze() {
        Node root = stack.peekLast();
        if (root == null) { Platform.exit(); return; }
        String prompt = cleanupPrompt(root, 30);
        List<String> ms = modelPicker.getItems();
        String model = ms.contains("qwen2.5:14b") ? "qwen2.5:14b" : (ms.isEmpty() ? "qwen2.5:14b" : ms.get(0));
        new Thread(() -> {
            try { System.out.println("=== CLEANUP ANALYSIS (" + model + ") ===\n" + Ollama.chat(model, SYSTEM_PROMPT, prompt)); }
            catch (Exception ex) { System.out.println("analyze failed: " + ex); }
            Platform.exit();
        }, "ollama-analyze").start();
    }

    private void handleResponse(String text) {
        if (thinking != null) { chatLog.getChildren().remove(thinking); thinking = null; }
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
        history.add(new Ollama.Msg("assistant", text));
        appendHistory("assistant", text);
        String p = prose.toString().strip();
        addBubble("assistant", p.isEmpty() ? "(suggested deletions below)" : p);
        List<Node> found = new ArrayList<>();
        Node root = stack.peekLast();
        if (root != null && !paths.isEmpty()) matchWalk(root, new HashSet<>(paths), found);
        int dropped = 0;
        for (Iterator<Node> it = found.iterator(); it.hasNext(); ) if (isProtected(it.next().path)) { it.remove(); dropped++; }
        if (dropped > 0) addBubble("assistant", "Note: I filtered out " + dropped
                + " suggestion(s) pointing at system or version-control (.git) files — those aren't safe to remove here.");
        showProposals(found);
    }

    // A backstop for the approval gate: never let the model propose deleting OS,
    // program, or version-control internals, however it phrases it.
    private static boolean isProtected(Path p) {
        String s = p.toString().toLowerCase().replace('/', '\\');
        if (s.contains("\\.git\\") || s.endsWith("\\.git")) return true;
        for (String bad : new String[]{"\\windows\\", "\\system32\\", "\\program files\\",
                "\\program files (x86)\\", "\\programdata\\", "\\$recycle.bin\\"})
            if (s.contains(bad)) return true;
        return false;
    }

    private HBox addBubble(String role, String text) {
        boolean user = "user".equals(role);
        Label b = new Label(text);
        b.setWrapText(true);
        b.setMaxWidth(255);
        b.setStyle("-fx-background-color:" + (user ? "#3d5a8a" : "#2c2c2e") + "; -fx-text-fill:"
                + (user ? "white" : FG) + "; -fx-padding:7 11 7 11; -fx-background-radius:12; -fx-font-size:12px;");
        HBox row = new HBox(b);
        row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        chatLog.getChildren().add(row);
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
        return row;
    }

    private void newChat() {
        history.clear();
        historyFile = null;   // next message starts a fresh session log
        chatLog.getChildren().clear();
        clearProposals();
        addBubble("assistant", "Hi — ask what's using space, what's safe to delete, or anything about your files. I can suggest deletions for you to approve.");
    }

    // ---- local conversation history -------------------------------------

    private static Path historyDir() { return diskbloomDir().resolve("history"); }

    /** Append one message/report to this session's local JSONL log (nothing leaves the machine). */
    private void appendHistory(String role, String text) {
        try {
            if (historyFile == null) {
                Files.createDirectories(historyDir());
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                historyFile = historyDir().resolve("chat-" + stamp + ".jsonl");
            }
            String line = "{\"time\":" + System.currentTimeMillis()
                    + ",\"role\":\"" + jsonEsc(role) + "\",\"text\":\"" + jsonEsc(text) + "\"}\n";
            Files.writeString(historyFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) { }
    }

    /** Whole-drive optimization analysis (broader than the biggest-files pass). */
    private void analyzeDrive() {
        Node root = stack.peekLast();
        if (root == null) { addBubble("assistant", "Scan a drive or folder first, then I can analyze it."); return; }
        String prompt = "Give me a prioritized, practical plan to optimize and free up space here, using the scan summary you have. "
                + "Cover: (1) the biggest space users and whether each is safe to shrink; (2) cleanup steps ranked by space reclaimed vs risk; "
                + "(3) likely caches, temp files, old downloads/installers, logs, or duplicates. "
                + "For anything clearly safe to remove, add a line exactly: " + DELETE_TAG + " <full path>. "
                + "Never suggest OS, system, program, or .git files. Keep it concise and skimmable.";
        ask("Analyze this drive and recommend how to optimize it.", prompt);
    }

    private void refreshContext() {
        Ollama.Msg sys = new Ollama.Msg("system", SYSTEM_PROMPT + "\n\nCurrent folder scan:\n" + buildContext());
        if (!history.isEmpty() && history.get(0).role().equals("system")) history.set(0, sys);
        else history.add(0, sys);
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
        searchHits = null; searchLabel = ""; browseRoot = null;
        typesMode = false;
        PriorityQueue<Node> heap = new PriorityQueue<>(Comparator.comparingLong((Node n) -> n.size));
        collectInto(root, heap); // keeps only the top BIGGEST_N files, bounded memory
        List<Node> files = new ArrayList<>(heap);
        files.sort(Comparator.comparingLong((Node n) -> n.size).reversed());

        biggestFiles = files;
        biggestMode = true;
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
        rebuildTree();
        render();
    }

    /** Leave any analysis view (biggest / big&old / types / search) and show the scanned folders again. */
    private void showFolders() {
        biggestMode = false;
        typesMode = false;
        searchHits = null; searchLabel = "";
        showCurrent();
        if (listView) rebuildTree();
    }

    private static void collectInto(Node n, PriorityQueue<Node> heap) {
        if (!n.dir) {
            heap.offer(n);
            if (heap.size() > BIGGEST_N) heap.poll();
            return;
        }
        if (n.children != null) for (Node c : n.children) collectInto(c, heap);
    }

    // ---- search / filter ------------------------------------------------

    private void runSearch(String q) {
        if (q == null || q.isBlank()) { clearSearch(); return; }
        Node root = stack.peekLast();
        if (root == null) { status.setText("Scan a folder first (Open folder, or pick a drive) — then search it."); return; }
        java.util.function.Predicate<Node> pred = parseQuery(q.trim());
        List<Node> hits = new ArrayList<>();
        searchCollect(root, pred, hits);
        hits.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
        boolean capped = hits.size() > SEARCH_CAP;
        if (capped) hits = new ArrayList<>(hits.subList(0, SEARCH_CAP));
        String label = "Search: \"" + q.trim() + "\"";
        String st = hits.size() + " match(es) for \"" + q.trim() + "\"  ·  sorted by size"
                + (capped ? "  ·  showing largest " + SEARCH_CAP : "") + "  ·  right-click for actions";
        showFlat(hits, label, st);
    }

    private static void searchCollect(Node n, java.util.function.Predicate<Node> pred, List<Node> out) {
        if (!n.dir) { if (pred.test(n)) out.add(n); return; }
        if (n.children != null) for (Node c : n.children) searchCollect(c, pred, out);
    }

    // name substring, ".ext" / "*.ext", or "type:video|image|audio|archive|code|doc|app"
    private static java.util.function.Predicate<Node> parseQuery(String q) {
        String s = q.toLowerCase();
        if (s.startsWith("type:")) {
            String want = s.substring(5).trim();
            Cat cat = null;
            for (Cat c : Cat.values())
                if (!want.isEmpty() && (c.label.toLowerCase().contains(want) || c.name().toLowerCase().contains(want))) { cat = c; break; }
            final Cat target = cat;
            return n -> !n.dir && target != null && catOf(n) == target;
        }
        if (s.startsWith("*.") || s.startsWith(".")) {
            String ext = s.substring(s.indexOf('.') + 1);
            return n -> !n.dir && extOf(n).equals(ext);
        }
        return n -> !n.dir && n.name.toLowerCase().contains(s);
    }

    private static String extOf(Node n) {
        int d = n.name.lastIndexOf('.');
        return d >= 0 ? n.name.substring(d + 1).toLowerCase() : "";
    }

    /** Filter the scanned tree to files of one extension ("" = no extension) and show them flat. */
    private void filterByExt(String ext) {
        Node root = stack.peekLast();
        if (root == null) return;
        List<Node> hits = new ArrayList<>();
        searchCollect(root, n -> !n.dir && extOf(n).equals(ext), hits);
        hits.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
        boolean capped = hits.size() > SEARCH_CAP;
        if (capped) hits = new ArrayList<>(hits.subList(0, SEARCH_CAP));
        String what = ext.isEmpty() ? "no extension" : "." + ext;
        String label = ext.isEmpty() ? "Files with no extension" : "Type: ." + ext;
        String st = hits.size() + " file(s) with " + what
                + (capped ? "  ·  showing largest " + SEARCH_CAP : "") + "  ·  right-click for actions";
        showFlat(hits, label, st);
    }

    /** Show a flat list of files (search hits, big-&-old, etc.) in both the list and the treemap. */
    private void showFlat(List<Node> files, String title, String statusMsg) {
        searchHits = files;
        searchLabel = title;
        biggestMode = false;
        typesMode = false;
        browseRoot = null;
        selected = null;
        if (!listView) { listView = true; viewBtn.setText("Map view"); }

        long sum = 0;
        for (Node n : files) sum += n.size;
        currentTotal = sum;
        crumb.setText(title + "   ·   " + files.size() + " file(s)");
        sTitle.setText(title);
        sSize.setText(Sizes.human(sum));
        sCount.setText(files.size() + " files");

        dominant.clear();
        for (Node n : files) dominant.put(n, catOf(n));
        list.getItems().setAll(files);
        catSums.clear();
        for (Node n : files) catSums.merge(catOf(n), n.size, Long::sum);
        updateLegend();

        rebuildTree();
        showResults();
        status.setText(statusMsg);
        render();
    }

    // ---- big & old files (largest files, least-recently-modified first) --

    private void enterBigOld() {
        Node root = stack.peekLast();
        if (root == null) { info("Scan a folder first to find big, old files."); return; }
        PriorityQueue<Node> heap = new PriorityQueue<>(Comparator.comparingLong((Node n) -> n.size));
        collectInto(root, heap);   // the top BIGGEST_N biggest files
        List<Node> files = new ArrayList<>(heap);
        files.sort(Comparator.comparingLong((Node n) -> mtime(n.path))
                .thenComparing(Comparator.comparingLong((Node n) -> n.size).reversed()));
        showFlat(files, "Big & old files", files.size() + " large files, least-recently-modified first  ·  right-click for actions");
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return Long.MAX_VALUE; }
    }

    // ---- file-type breakdown --------------------------------------------

    private void enterTypes() {
        Node root = stack.peekLast();
        if (root == null) { info("Scan a folder first to see its file-type breakdown."); return; }
        typesMode = true;
        searchHits = null; searchLabel = "";
        biggestMode = false;
        selected = null;

        // sidebar reflects the scanned root while the centre shows the breakdown
        currentTotal = root.size;
        updateSidebar(root);
        List<Node> kids = new ArrayList<>();
        if (root.children != null) for (Node n : root.children) if (n.size > 0) kids.add(n);
        dominant.clear();
        for (Node k : kids) dominant.put(k, dominantCat(k));
        list.getItems().setAll(kids);
        catSums.clear(); accumulate(root, catSums); updateLegend();

        buildTypesPane(root);
        showResults();     // applyView() now shows typesPane
        crumb.setText("File types by size  ·  " + root.name);
        status.setText("Which file types use the most space  ·  click a type to see those files");
    }

    private void buildTypesPane(Node root) {
        Map<String, long[]> agg = new HashMap<>();
        aggExt(root, agg);                              // ext -> [totalSize, count]
        List<Map.Entry<String, long[]>> es = new ArrayList<>(agg.entrySet());
        es.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        long total = 0;
        for (var e : es) total += e.getValue()[0];
        long max = es.isEmpty() ? 0 : es.get(0).getValue()[0];

        VBox rows = new VBox(3);
        rows.setPadding(new Insets(16));
        Label h = new Label("File types by size");
        h.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:19px; -fx-font-weight:bold;");
        Label sub = new Label(Sizes.human(total) + " across " + es.size() + " file types  ·  click a row to filter to those files");
        sub.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:12px; -fx-padding:0 0 8 0;");
        rows.getChildren().addAll(h, sub);
        int shown = 0;
        for (var e : es) {
            if (shown++ >= 40) break;
            rows.getChildren().add(typeRow(e.getKey(), e.getValue()[0], (int) e.getValue()[1], total, max));
        }
        if (es.size() > 40) {
            Label more = new Label("… and " + (es.size() - 40) + " smaller file types");
            more.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px; -fx-padding:6 0 0 0;");
            rows.getChildren().add(more);
        }
        typesPane.setContent(rows);
        typesPane.setVvalue(0);
    }

    private HBox typeRow(String ext, long size, int count, long total, long max) {
        Cat cat = EXT.getOrDefault(ext, Cat.OTHER);
        Region sw = new Region();
        sw.setMinSize(11, 11); sw.setPrefSize(11, 11); sw.setMaxSize(11, 11);
        sw.setStyle("-fx-background-color:" + rgb(cat.color) + "; -fx-background-radius:2;");
        Label name = new Label(ext.isEmpty() ? "(no extension)" : "." + ext);
        name.setMinWidth(120); name.setPrefWidth(120);
        name.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:13px; -fx-font-weight:bold;");
        Region bar = new Region();
        bar.setPrefWidth(Math.max(2, max > 0 ? 320.0 * size / max : 0));
        bar.setMinWidth(Region.USE_PREF_SIZE);
        bar.setPrefHeight(13);
        bar.setStyle("-fx-background-color:" + rgb(cat.color) + "; -fx-background-radius:3;");
        Label sz = new Label(Sizes.human(size));
        sz.setMinWidth(80);
        sz.setStyle("-fx-text-fill:" + FG + "; -fx-font-size:12px;");
        Label cnt = new Label(count + (count == 1 ? " file" : " files"));
        cnt.setMinWidth(72);
        cnt.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        Label pct = new Label(String.format("%.1f%%", total > 0 ? 100.0 * size / total : 0));
        pct.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        HBox row = new HBox(10, sw, name, bar, sz, cnt, pct);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4));
        row.setStyle("-fx-cursor: hand;");
        row.setOnMouseClicked(e -> filterByExt(ext));
        Tooltip.install(row, new Tooltip(ext.isEmpty()
                ? "Files whose name has no .extension — e.g. LICENSE, README, or git object files. Click to list them."
                : "Click to list all ." + ext + " files"));
        return row;
    }

    private static void aggExt(Node n, Map<String, long[]> agg) {
        if (!n.dir) {
            if (n.size > 0) { long[] a = agg.computeIfAbsent(extOf(n), k -> new long[2]); a[0] += n.size; a[1]++; }
            return;
        }
        if (n.children != null) for (Node c : n.children) aggExt(c, agg);
    }

    // Re-root the list/tree for the current mode (search, biggest, live browse, or scanned tree).
    private void rebuildTree() {
        List<Node> flat = searchHits != null ? searchHits : (biggestMode ? biggestFiles : null);
        if (flat != null) {
            Node base = stack.peekLast();
            TreeItem<Node> r = new TreeItem<>(base);      // flat: matches listed directly, no drill-down
            for (Node n : flat) r.getChildren().add(new TreeItem<>(n));
            r.setExpanded(true);
            tree.setRoot(r);
        } else {
            buildTree(browseRoot != null ? browseRoot : stack.peek());
        }
    }

    /** Clear results and restore the scanned tree. */
    private void clearSearch() {
        if (searchHits == null) return;
        searchHits = null; searchLabel = "";
        showCurrent();
        if (listView) buildTree(stack.peek());
    }

    /** Drop results without restoring a view (for callers that set their own view next). */
    private void clearSearchState() { searchHits = null; searchLabel = ""; }

    // ---- duplicate finder (content-hash, deterministic — no LLM) --------

    private void findDuplicates() {
        Node root = stack.peekLast();
        if (root == null) { info("Scan a folder first, then find duplicates in it."); return; }
        status.setText("Finding duplicate files (hashing same-size files)…");
        dupBtn.setDisable(true);
        Task<List<List<Node>>> task = new Task<>() {
            @Override protected List<List<Node>> call() {
                return findDupes(root, (h, t) -> Platform.runLater(() ->
                        status.setText("Hashing files for duplicates…  " + h + " / " + t)));
            }
        };
        task.setOnSucceeded(e -> { dupBtn.setDisable(false); showDuplicates(task.getValue()); });
        task.setOnFailed(e -> { dupBtn.setDisable(false); status.setText("Duplicate scan failed: " + task.getException()); });
        Thread th = new Thread(task, "diskbloom-dupes");
        th.setDaemon(true);
        th.start();
    }

    private void showDuplicates(List<List<Node>> groups) {
        if (groups.isEmpty()) { status.setText("No duplicate files found in " + stack.peekLast().name + "."); return; }
        long wasted = 0;
        List<Node> extras = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (List<Node> g : groups) {
            long each = g.get(0).size;
            wasted += each * (g.size() - 1);
            for (int i = 1; i < g.size(); i++) if (!isProtected(g.get(i).path)) extras.add(g.get(i)); // keep the first copy
            if (shown < 12) {
                sb.append("• ").append(g.size()).append("× ").append(Sizes.human(each)).append("  ").append(g.get(0).name)
                  .append("   (keep ").append(g.get(0).path).append(")\n");
                shown++;
            }
        }
        if (extras.isEmpty()) { status.setText("Found duplicates, but every extra copy is in a protected location."); return; }
        if (rootPane.getRight() == null) toggleAssistant();   // the approval checklist lives in this panel
        addBubble("assistant", "Found " + groups.size() + " set(s) of identical files — about " + Sizes.human(wasted)
                + " reclaimable by keeping one copy of each. Tick the redundant copies below to Recycle them "
                + "(one copy of every set is always kept).\n\n" + sb.toString().strip());
        showProposals(extras);
        status.setText(groups.size() + " duplicate set(s)  ·  " + Sizes.human(wasted) + " reclaimable  ·  approve below");
    }

    interface DupProgress { void update(int hashed, int total); }

    private static List<List<Node>> findDupes(Node root) { return findDupes(root, null); }

    /** Group identical files (same size AND same SHA-256). Each returned group has >= 2 files. */
    private static List<List<Node>> findDupes(Node root, DupProgress progress) {
        Map<Long, List<Node>> bySize = new HashMap<>();
        collectBySize(root, bySize);
        List<Node> candidates = new ArrayList<>();              // only files in a same-size group can be dupes
        for (List<Node> g : bySize.values()) if (g.size() >= 2) candidates.addAll(g);
        int total = candidates.size();
        if (total == 0) return List.of();

        // Hash the candidates in parallel across all cores — SHA-256 is CPU-bound, so this scales well.
        AtomicInteger done = new AtomicInteger();
        Map<Node, String> hashes = new ConcurrentHashMap<>();
        candidates.parallelStream().forEach(n -> {
            String h = sha256(n.path);
            if (h != null) hashes.put(n, h);
            int c = done.incrementAndGet();
            if (progress != null && (c % 64 == 0 || c == total)) progress.update(c, total);
        });

        // Group serially from the precomputed hashes — same result and order as the old serial pass.
        List<List<Node>> groups = new ArrayList<>();
        for (List<Node> sameSize : bySize.values()) {
            if (sameSize.size() < 2) continue;
            Map<String, List<Node>> byHash = new HashMap<>();
            for (Node n : sameSize) {
                String h = hashes.get(n);
                if (h != null) byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(n);
            }
            for (List<Node> g : byHash.values()) if (g.size() >= 2) groups.add(g);
        }
        groups.sort((a, b) -> Long.compare(b.get(0).size * (b.size() - 1), a.get(0).size * (a.size() - 1))); // biggest win first
        return groups;
    }

    private static void collectBySize(Node n, Map<Long, List<Node>> bySize) {
        if (!n.dir) { if (n.size > 0) bySize.computeIfAbsent(n.size, k -> new ArrayList<>()).add(n); return; }
        if (n.children != null) for (Node c : n.children) collectBySize(c, bySize);
    }

    // ponytail: full-content SHA-256 (must be exact for a delete feature). Add a cheap
    // prefix pre-filter if huge same-size groups ever make this slow.
    private static String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { return null; }   // unreadable -> excluded (safe: never proposed for deletion)
    }

    // Headless hook: -Ddiskbloom.dupes prints duplicate sets and exits.
    private void runDupes() {
        Node root = stack.peekLast();
        if (root == null) { Platform.exit(); return; }
        new Thread(() -> {
            List<List<Node>> groups = findDupes(root, (h, t) -> { if (h == t) System.out.println("hashed " + h + " same-size file(s)"); });
            long wasted = 0;
            System.out.println("=== DUPLICATES in " + root.path + " ===");
            for (List<Node> g : groups) {
                long each = g.get(0).size; wasted += each * (g.size() - 1);
                System.out.println(g.size() + "x " + Sizes.human(each) + "  " + g.get(0).name);
                for (Node n : g) System.out.println("    " + n.path);
            }
            System.out.println("sets: " + groups.size() + ", reclaimable: " + Sizes.human(wasted));
            Platform.exit();
        }, "diskbloom-dupes").start();
    }

    // ---- rule-based junk finder (deterministic — no LLM) ----------------

    // Regenerable cache/build dirs, throwaway files, and installers left in Downloads.
    // All still go through the approval checklist + isProtected guard + Recycle Bin.
    private static final Set<String> JUNK_DIRS = Set.of(
            "node_modules", "__pycache__", ".gradle", ".cache", ".mypy_cache",
            ".pytest_cache", ".ipynb_checkpoints", ".next", ".parcel-cache");
    private static final Set<String> JUNK_FILES = Set.of("thumbs.db", ".ds_store");
    private static final Set<String> JUNK_EXTS = Set.of("log", "tmp", "temp", "bak", "dmp");
    private static final Set<String> INSTALLER_EXTS = Set.of("msi", "exe", "iso");

    private static boolean isJunkNode(Node n) {
        String name = n.name.toLowerCase();
        if (n.dir) return JUNK_DIRS.contains(name);
        if (JUNK_FILES.contains(name)) return true;
        String ext = extOf(n);
        if (JUNK_EXTS.contains(ext)) return true;
        return INSTALLER_EXTS.contains(ext) && n.path.toString().toLowerCase().contains("\\downloads\\");
    }

    private static void collectJunk(Node n, List<Node> out) {
        if (isJunkNode(n)) { out.add(n); return; }   // propose the whole item; don't descend into a junk dir
        if (n.dir && n.children != null) for (Node c : n.children) collectJunk(c, out);
    }

    private void findJunk() {
        Node root = stack.peekLast();
        if (root == null) { addBubble("assistant", "Scan a folder first, then I can find junk in it."); return; }
        List<Node> junk = new ArrayList<>();
        collectJunk(root, junk);
        junk.removeIf(n -> isProtected(n.path) || n.size == 0);
        junk.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
        if (rootPane.getRight() == null) toggleAssistant();
        if (junk.isEmpty()) {
            addBubble("assistant", "No obvious junk found by the built-in rules (cache/build folders, logs, temp files, thumbnail caches, installers left in Downloads).");
            clearProposals();
            return;
        }
        long sum = 0;
        for (Node n : junk) sum += n.size;
        addBubble("assistant", "Found " + junk.size() + " likely-junk item(s) — about " + Sizes.human(sum)
                + " — using built-in rules (cache/build folders, logs, temp files, thumbnail caches, Downloads installers). "
                + "These are commonly safe to remove; tick the ones you want and Recycle them.");
        showProposals(junk);
        status.setText(junk.size() + " likely-junk item(s)  ·  " + Sizes.human(sum) + " reclaimable  ·  approve below");
    }

    // Headless hook: -Ddiskbloom.junk prints the rule-based junk list and exits.
    private void runJunk() {
        Node root = stack.peekLast();
        if (root == null) { Platform.exit(); return; }
        List<Node> junk = new ArrayList<>();
        collectJunk(root, junk);
        junk.removeIf(n -> isProtected(n.path) || n.size == 0);
        junk.sort(Comparator.comparingLong((Node n) -> n.size).reversed());
        long sum = 0;
        System.out.println("=== JUNK in " + root.path + " ===");
        for (Node n : junk) { sum += n.size; System.out.println((n.dir ? "[dir] " : "      ") + Sizes.human(n.size) + "  " + n.path); }
        System.out.println("items: " + junk.size() + ", total: " + Sizes.human(sum));
        Platform.exit();
    }

    // ---- CSV export -----------------------------------------------------

    private void chooseAndExport() {
        Node root = stack.peekLast();
        if (root == null) { info("Scan a folder first, then export it."); return; }
        exportNode(root);
    }

    /** Save one node's subtree (the whole scan, or a right-clicked folder) as CSV or JSON. */
    private void exportNode(Node root) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export " + root.name + " (CSV or JSON)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV file", "*.csv"),
                new FileChooser.ExtensionFilter("JSON file", "*.json"));
        fc.setInitialFileName("diskbloom-" + safeName(root.name) + ".csv");
        File f = fc.showSaveDialog(scene.getWindow());
        if (f == null) return;
        boolean json = f.getName().toLowerCase().endsWith(".json");
        try {
            Files.writeString(f.toPath(), json ? toJson(root) : toCsv(root));
            status.setText("Exported " + rowCount(root) + (json ? " nodes (JSON) to " : " rows (CSV) to ") + f);
        } catch (Exception e) {
            info("Export failed: " + e.getMessage());
        }
    }

    private static String safeName(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Every file and folder in the tree as CSV: Path, Bytes, Size, Type. */
    private static String toCsv(Node root) {
        StringBuilder sb = new StringBuilder("Path,Bytes,Size,Type\n");
        csvRows(root, sb);
        return sb.toString();
    }

    private static void csvRows(Node n, StringBuilder sb) {
        sb.append(csvField(n.path.toString())).append(',')
          .append(n.size).append(',')
          .append(csvField(Sizes.human(n.size))).append(',')
          .append(n.dir ? "folder" : catOf(n).label).append('\n');
        if (n.children != null) for (Node c : n.children) csvRows(c, sb);
    }

    // ponytail: RFC-4180 quoting — wrap in quotes and double any embedded quote.
    private static String csvField(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0)
            return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    private static int rowCount(Node n) {
        int c = 1;
        if (n.children != null) for (Node k : n.children) c += rowCount(k);
        return c;
    }

    /** The scan as a nested JSON tree: {name, path, bytes, dir, children:[…]}. */
    private static String toJson(Node root) {
        StringBuilder sb = new StringBuilder();
        jsonNode(root, sb);
        return sb.append('\n').toString();
    }

    private static void jsonNode(Node n, StringBuilder sb) {
        sb.append("{\"name\":\"").append(jsonEsc(n.name))
          .append("\",\"path\":\"").append(jsonEsc(n.path.toString()))
          .append("\",\"bytes\":").append(n.size)
          .append(",\"dir\":").append(n.dir);
        if (n.dir && n.children != null && !n.children.isEmpty()) {
            sb.append(",\"children\":[");
            for (int i = 0; i < n.children.size(); i++) {
                if (i > 0) sb.append(',');
                jsonNode(n.children.get(i), sb);
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static String jsonEsc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }

    // Headless hook: -Ddiskbloom.exportcsv=<path> writes CSV or JSON (by the path's extension) and exits.
    private void runExport(String out) {
        Node root = stack.peekLast();
        try {
            if (root != null) {
                boolean json = out.toLowerCase().endsWith(".json");
                Files.writeString(Paths.get(out), json ? toJson(root) : toCsv(root));
                System.out.println("exported " + rowCount(root) + (json ? " nodes (JSON) to " : " rows (CSV) to ") + out);
            }
        } catch (Exception e) { System.out.println("export failed: " + e); }
        Platform.exit();
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
        if (n.dir) {
            MenuItem scanItem = new MenuItem("Scan this folder");
            scanItem.setOnAction(a -> scan(n.path));
            if (n.size == 0) {   // a browse folder whose size we haven't computed yet
                MenuItem measure = new MenuItem("Measure size");
                measure.setOnAction(a -> measureSize(n));
                return new ContextMenu(open, reveal, measure, scanItem, new SeparatorMenuItem(), del);
            }
            if (browseRoot == null && n.children != null && !n.children.isEmpty()) {   // scanned subtree -> exportable
                MenuItem exportItem = new MenuItem("Export this folder…");
                exportItem.setOnAction(a -> exportNode(n));
                return new ContextMenu(open, reveal, scanItem, exportItem, new SeparatorMenuItem(), del);
            }
            return new ContextMenu(open, reveal, scanItem, new SeparatorMenuItem(), del);
        }
        return new ContextMenu(open, reveal, new SeparatorMenuItem(), del);
    }

    // Measure every unmeasured folder in the current browse listing, one after another, showing progress.
    private void measureVisible() {
        if (browseRoot == null || browseRoot.children == null) {
            status.setText("\"Measure folders\" works while browsing — after a scan, folders already have sizes.");
            return;
        }
        List<Node> folders = new ArrayList<>();
        for (Node c : browseRoot.children) if (c.dir && c.size == 0) folders.add(c);
        if (folders.isEmpty()) { status.setText("No unmeasured folders in " + browseRoot.name + "."); return; }
        measureBtn.setDisable(true);
        Thread t = new Thread(() -> {
            for (int i = 0; i < folders.size(); i++) {
                Node f = folders.get(i);
                long size = Scanner.sizeOf(f.path);
                final int done = i + 1;
                Platform.runLater(() -> {
                    f.size = size;
                    tree.refresh(); list.refresh();
                    status.setText("Measuring folders…  " + done + "/" + folders.size());
                });
            }
            Platform.runLater(() -> {
                measureBtn.setDisable(false);
                status.setText("Measured " + folders.size() + " folder(s) in " + browseRoot.name);
            });
        }, "diskbloom-measure-all");
        t.setDaemon(true);
        t.start();
    }

    // Compute one folder's size on demand (off-thread) and show it in place — Explorer never does this.
    private void measureSize(Node n) {
        status.setText("Measuring " + n.name + " …");
        Thread t = new Thread(() -> {
            long size = Scanner.sizeOf(n.path);
            Platform.runLater(() -> {
                n.size = size;
                tree.refresh();
                list.refresh();
                status.setText(n.name + "  is  " + Sizes.human(size));
            });
        }, "diskbloom-measure");
        t.setDaemon(true);
        t.start();
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

    // Bar in the list view: a proportional colored bar (share of the parent folder).
    private final class BarCell extends TreeTableCell<Node, Node> {
        private final Region fill = new Region();
        private final HBox box = new HBox(fill);
        BarCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            fill.setMinHeight(11); fill.setPrefHeight(11); fill.setMaxHeight(11);
        }
        @Override protected void updateItem(Node n, boolean empty) {
            super.updateItem(n, empty);
            fill.prefWidthProperty().unbind();
            if (empty || n == null) { setGraphic(null); return; }
            TreeItem<Node> row = getTableRow() != null ? getTableRow().getTreeItem() : null;
            double frac = 1;
            if (row != null && row.getParent() != null) {
                long ps = row.getParent().getValue().size;
                frac = ps > 0 ? Math.max(0.015, Math.min(1.0, (double) n.size / ps)) : 0;
            }
            fill.setStyle("-fx-background-color:" + rgb(colorOf(n)) + "; -fx-background-radius:3;");
            fill.prefWidthProperty().bind(widthProperty().subtract(12).multiply(frac));
            setGraphic(box);
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
            String sizeText = (n.dir && n.size == 0) ? "" : Sizes.human(n.size);   // live folder size unknown
            String pctText = (currentTotal > 0 && n.size > 0) ? "   " + String.format("%.0f%%", 100.0 * n.size / currentTotal) : "";
            Label size = new Label(sizeText + pctText);
            size.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
            HBox row = new HBox(8, sw, name, size);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
