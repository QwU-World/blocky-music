import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A JDK HttpServer on 127.0.0.1 standing in for blocky-music.qwu.world (or Mojang) in the Workshop checks. Routes are
 * "METHOD /path" (query ignored); every request is recorded with headers and body so a check can prove what was sent.
 */
final class FakeWorkshopServer implements AutoCloseable {
    record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        /** Everything the client sent, as text, for secret scanning. */
        String everything() {
            StringBuilder out = new StringBuilder(method).append(' ').append(path).append('?').append(query).append('\n');
            headers.forEach((k, v) -> out.append(k).append(": ").append(v).append('\n'));
            return out.append(bodyText()).toString();
        }
    }

    /** A response; {@code chunkSize > 0} streams the body in chunks with pauses. {@code declaredLength}: -1 real, 0 chunked, else announced. */
    record Response(int status, Map<String, String> headers, byte[] body, int chunkSize, long chunkDelayMs, long declaredLength) {
        static Response json(int status, String json, String... headerPairs) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                headers.put(headerPairs[i], headerPairs[i + 1]);
            }
            return new Response(status, headers, json.getBytes(StandardCharsets.UTF_8), 0, 0, -1);
        }

        static Response bytes(int status, byte[] body) {
            return new Response(status, Map.of("Content-Type", "application/octet-stream"), body, 0, 0, -1);
        }

        static Response empty(int status) {
            return new Response(status, Map.of(), new byte[0], 0, 0, -1);
        }

        Response slow(int chunkSize, long delayMs) {
            return new Response(status, headers, body, chunkSize, delayMs, declaredLength);
        }

        Response declared(long length) {
            return new Response(status, headers, body, chunkSize, chunkDelayMs, length);
        }
    }

    @FunctionalInterface
    interface Handler {
        Response handle(Request request) throws Exception;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Handler> routes = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    FakeWorkshopServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.setExecutor(executor);
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void on(String method, String path, Handler handler) {
        routes.put(method + " " + path, handler);
    }

    List<Request> requests(String method, String path) {
        synchronized (requests) {
            return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
        }
    }

    List<Request> snapshot() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        Request request = new Request(exchange.getRequestMethod(), path, query == null ? "" : query,
                new LinkedHashMap<>(exchange.getRequestHeaders()), body);
        requests.add(request);
        Handler handler = routes.get(request.method() + " " + path);
        Response response;
        try {
            response = handler == null
                    ? Response.json(404, "{\"schema\":1,\"error\":{\"code\":\"not_found\",\"message\":\"Not found.\"}}")
                    : handler.handle(request);
        } catch (Exception exception) {
            response = Response.json(500, "{\"schema\":1,\"error\":{\"code\":\"internal_error\",\"message\":\"" + exception + "\"}}");
        }
        try {
            response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            if (response.status() == 204 || response.status() == 304 || (response.body().length == 0 && response.declaredLength() == -1)) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            // -1: the real length; 0: chunked (no Content-Length); anything else is announced as is.
            long length = response.declaredLength() == -1 ? response.body().length : response.declaredLength();
            exchange.sendResponseHeaders(response.status(), length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (response.chunkSize() <= 0) {
                    out.write(response.body());
                } else {
                    for (int i = 0; i < response.body().length; i += response.chunkSize()) {
                        out.write(response.body(), i, Math.min(response.chunkSize(), response.body().length - i));
                        out.flush();
                        Thread.sleep(response.chunkDelayMs());
                    }
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // The client went away (cancel, size cap): nothing to do.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
