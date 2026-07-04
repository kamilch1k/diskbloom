package dev.diskbloom.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for a local Ollama server (default http://localhost:11434).
 * Uses the JDK's built-in HttpClient — no dependencies. Everything stays on the
 * machine; nothing is sent anywhere but localhost. The endpoint can be set at
 * runtime (Settings) and is auto-detected across common local addresses.
 */
public final class Ollama {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static volatile String base = resolveInitial();

    // Locate order: explicit -D flag, then Ollama's own OLLAMA_HOST env, then the default port.
    private static String resolveInitial() {
        String p = System.getProperty("diskbloom.ollama");
        if (p != null && !p.isBlank()) return normalize(p);
        String env = System.getenv("OLLAMA_HOST");
        if (env != null && !env.isBlank()) return normalize(env);
        return "http://localhost:11434";
    }

    public static String getBase() { return base; }
    public static void setBase(String url) { if (url != null && !url.isBlank()) base = normalize(url); }

    static String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** Try the current endpoint, then common local ones; on success set base and return its models. */
    public static List<String> autodetect() {
        List<String> tried = new ArrayList<>();
        tried.add(base);
        for (String c : new String[]{"http://localhost:11434", "http://127.0.0.1:11434"})
            if (!tried.contains(c)) tried.add(c);
        for (String url : tried) {
            try {
                List<String> m = modelsAt(url);
                if (!m.isEmpty()) { base = url; return m; }
            } catch (Exception ignore) { }
        }
        return List.of();
    }

    /** True if the server responds and has at least one model. */
    public static boolean available() {
        try { return !models().isEmpty(); } catch (Exception e) { return false; }
    }

    public static List<String> models() throws Exception { return modelsAt(base); }

    private static List<String> modelsAt(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/tags"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.fields(resp.body(), "name");
    }

    public record Msg(String role, String content) {}

    /** One-shot chat with a system + user message. */
    public static String chat(String model, String system, String user) throws Exception {
        return chat(model, List.of(new Msg("system", system), new Msg("user", user)));
    }

    /** Multi-turn chat over a full message history. Blocking — call off the UI thread. */
    public static String chat(String model, List<Msg> messages) throws Exception {
        StringBuilder arr = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (i > 0) arr.append(',');
            arr.append("{\"role\":\"").append(Json.escape(m.role()))
               .append("\",\"content\":\"").append(Json.escape(m.content())).append("\"}");
        }
        String body = "{\"model\":\"" + Json.escape(model) + "\",\"stream\":false,\"messages\":[" + arr + "]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String content = Json.field(resp.body(), "content");
        return content != null ? content : "(no response — HTTP " + resp.statusCode() + ")";
    }

    // Connectivity check: java -ea -cp out dev.diskbloom.llm.Ollama
    public static void main(String[] args) throws Exception {
        Json.selfCheck();
        assert normalize("localhost:11434").equals("http://localhost:11434") : "normalize adds scheme";
        assert normalize("http://x:1/").equals("http://x:1") : "normalize trims trailing slash";
        System.out.println("endpoint: " + getBase());
        List<String> m = autodetect();
        System.out.println("models: " + m + "  at " + getBase());
        if (!m.isEmpty()) {
            String model = m.contains("qwen2.5:14b") ? "qwen2.5:14b" : m.get(0);
            long t = System.nanoTime();
            String r = chat(model, "You are terse.", "Reply with exactly one word: pong");
            System.out.println(model + " replied in " + (System.nanoTime() - t) / 1000 / 1000 + " ms: " + r.strip());
        }
    }

    private Ollama() {}
}
