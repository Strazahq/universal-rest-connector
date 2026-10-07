package ai.straza.connector.rest.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process HTTP server for hermetic tests, built on the JDK's
 * {@code com.sun.net.httpserver.HttpServer}. By default {@code /ok} answers 200,
 * {@code /unauthorized} 401, {@code /forbidden} 403, {@code /boom} 500 and any
 * other path 404; stubs override that, and every request is recorded.
 */
public class EmbeddedRestServer implements AutoCloseable {

    private record Stub(int status, String body) { }

    /** One received request: method, raw URI (path + query), body, and key headers. */
    public record RecordedRequest(String method, String uri, String body,
                                  String authorization, String contentType) { }

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final Map<String, Stub> methodStubs = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sequences = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequenceIndex = new ConcurrentHashMap<>();
    private final List<String> requestUris = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final java.util.Queue<Stub> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile String lastAuthorization;

    public EmbeddedRestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Register a canned response for an exact path. */
    public EmbeddedRestServer stub(String path, int status, String body) {
        stubs.put(path, new Stub(status, body));
        return this;
    }

    /** Register a canned response for an exact method + path pair (checked before path-only stubs). */
    public EmbeddedRestServer stub(String method, String path, int status, String body) {
        methodStubs.put(method + " " + path, new Stub(status, body));
        return this;
    }

    /**
     * Queue mode for scripted replays: each request pops the next canned response
     * regardless of path. Takes priority over every stub while non-empty.
     */
    public EmbeddedRestServer enqueue(int status, String body) {
        queue.add(new Stub(status, body));
        return this;
    }

    /**
     * Register successive 200 responses for a path: the Nth request returns
     * {@code bodies.get(N)}, and the last body repeats.
     */
    public EmbeddedRestServer stubSequence(String path, List<String> bodies) {
        sequences.put(path, List.copyOf(bodies));
        sequenceIndex.put(path, new AtomicInteger(0));
        return this;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestUris.add(exchange.getRequestURI().toString());
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                requestBody,
                lastAuthorization,
                exchange.getRequestHeaders().getFirst("Content-Type")));
        String path = exchange.getRequestURI().getPath();
        int status;
        String body;
        List<String> sequence = sequences.get(path);
        Stub queued = queue.poll();
        Stub methodStub = methodStubs.get(exchange.getRequestMethod() + " " + path);
        Stub stub = stubs.get(path);
        if (queued != null) {
            status = queued.status();
            body = queued.body();
        } else if (sequence != null && !sequence.isEmpty()) {
            int i = sequenceIndex.get(path).getAndIncrement();
            status = 200;
            body = sequence.get(Math.min(i, sequence.size() - 1));
        } else if (methodStub != null) {
            status = methodStub.status();
            body = methodStub.body();
        } else if (stub != null) {
            status = stub.status();
            body = stub.body();
        } else {
            switch (path) {
                case "/ok":
                    status = 200;
                    body = "{\"ok\":true}";
                    break;
                case "/unauthorized":
                    status = 401;
                    body = "{\"error\":\"token rejected\"}";
                    break;
                case "/forbidden":
                    status = 403;
                    body = "{\"error\":\"forbidden\"}";
                    break;
                case "/boom":
                    status = 500;
                    body = "{\"error\":\"boom\"}";
                    break;
                default:
                    status = 404;
                    body = "{\"error\":\"not found\"}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** The server's base URL, http on the loopback interface. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The {@code Authorization} header seen on the most recent request, or null. */
    public String lastAuthorization() {
        return lastAuthorization;
    }

    /** Every request URI (path and query) received, in order. */
    public List<String> requestUris() {
        return Collections.unmodifiableList(requestUris);
    }

    /** Every request received, in order, with method, body, and key headers. */
    public List<RecordedRequest> requests() {
        return Collections.unmodifiableList(requests);
    }

    /** The most recent request, or null when none arrived yet. */
    public RecordedRequest lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
