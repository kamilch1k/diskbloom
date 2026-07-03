package dev.diskbloom.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Minimal client for a local Ollama server (default http://localhost:11434).
 * Uses the JDK's built-in HttpClient — no dependencies. Everything stays on the
 * machine; nothing is sent anywhere but localhost.
 */
public final class Ollama {

    private static final String BASE = System.getProperty("diskbloom.ollama", "http://localhost:11434");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** True if the server responds and has at least one model. */
    public static boolean available() {
        try { return !models().isEmpty(); } catch (Exception e) { return false; }
    }

    public static List<String> models() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/tags"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.fields(resp.body(), "name");
    }

    /** One-shot chat; returns the assistant's text. Blocking — call off the UI thread. */
    public static String chat(String model, String system, String user) throws Exception {
        String body = "{\"model\":\"" + Json.escape(model) + "\",\"stream\":false,\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + Json.escape(system) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + Json.escape(user) + "\"}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/chat"))
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
        List<String> m = models();
        System.out.println("models: " + m);
        if (!m.isEmpty()) {
            String model = m.contains("qwen2.5:14b") ? "qwen2.5:14b" : m.get(0);
            long t = System.nanoTime();
            String r = chat(model, "You are terse.", "Reply with exactly one word: pong");
            System.out.println(model + " replied in " + (System.nanoTime() - t) / 1000 / 1000 + " ms: " + r.strip());
        }
    }

    private Ollama() {}
}
