package com.epfisztner.knowledgeassistant.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A local HTTP server that serves synthetic Source pages written by tests.
 * <p>
 * Tests put HTML at a path, can replace it later to simulate a Source change, make it fail
 * with an HTTP status, or remove it (404). One instance is shared by the whole test JVM; see
 * {@link #shared()}. Because it is shared, tests should use paths unique to themselves.
 */
public final class FixtureSite {

    private static final FixtureSite SHARED = new FixtureSite();

    private record Response(int status, String body, String contentType) {
        Response(int status, String body) {
            this(status, body, "text/html; charset=utf-8");
        }
    }

    private record ApiHandler(Duration delay, Function<String, Response> responder) {
    }

    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final Map<String, ApiHandler> apis = new ConcurrentHashMap<>();
    private final Map<String, List<String>> requests = new ConcurrentHashMap<>();
    private final HttpServer server;

    private FixtureSite() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fixture-site");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ApiHandler api = apis.get(path);
            Response response;
            if (api != null) {
                requests.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>()).add(requestBody);
                if (!api.delay().isZero() && !api.delay().isNegative()) {
                    try {
                        Thread.sleep(api.delay().toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                response = api.responder().apply(requestBody);
            } else {
                response = responses.getOrDefault(path, new Response(404, "Not Found"));
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            try {
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(bytes);
                }
            } catch (IOException ignored) {
                // client may already have closed the connection (e.g. after a client-side timeout)
            }
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    public static FixtureSite shared() {
        return SHARED;
    }

    /** Serves {@code html} with status 200 at {@code path} (e.g. {@code "/bankszamla/eco/"}), replacing any previous content. */
    public FixtureSite page(String path, String html) {
        responses.put(path, new Response(200, html));
        return this;
    }

    /**
     * Serves a fake JSON API at {@code path} (any method): each request body is recorded and the
     * response is computed from it. Used to stand in for a chat provider's HTTP API.
     */
    public FixtureSite api(String path, Function<String, String> responder) {
        apis.put(path, new ApiHandler(Duration.ZERO,
                request -> new Response(200, responder.apply(request), "application/json")));
        return this;
    }

    /**
     * Makes the fake JSON API at {@code path} answer with the given HTTP error status and body.
     */
    public FixtureSite apiFailure(String path, int status, String body) {
        apis.put(path, new ApiHandler(Duration.ZERO,
                request -> new Response(status, body, "application/json")));
        return this;
    }

    /**
     * Delays the fake JSON API at {@code path} by {@code delay} before answering with HTTP 200.
     */
    public FixtureSite apiDelayed(String path, Duration delay, Function<String, String> responder) {
        apis.put(path, new ApiHandler(delay,
                request -> new Response(200, responder.apply(request), "application/json")));
        return this;
    }

    /** The request bodies received so far by the fake API at {@code path}. */
    public List<String> requests(String path) {
        return List.copyOf(requests.getOrDefault(path, List.of()));
    }

    /** Makes {@code path} answer with the given HTTP error status. */
    public FixtureSite failing(String path, int status) {
        responses.put(path, new Response(status, "fixture failure " + status));
        return this;
    }

    /** Removes the page at {@code path}; it then answers 404. */
    public FixtureSite remove(String path) {
        responses.remove(path);
        return this;
    }

    /** Absolute URL of {@code path} on this server. */
    public String url(String path) {
        return baseUrl() + path;
    }

    /** Base URL without trailing slash, e.g. {@code http://127.0.0.1:54321}. */
    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }
}
