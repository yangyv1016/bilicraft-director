package dev.bilicraft.ai.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** Local HTTP boundary only; callbacks must promptly return futures scheduled by the game-thread owner. */
public final class LocalControlServer implements AutoCloseable {
    private static final int MAX_COMMAND_BYTES = 16_384;
    private static final String JSON = "application/json; charset=utf-8";
    private final HttpServer server;
    private final ExecutorService executor;
    private final Supplier<CompletableFuture<String>> readState;
    private final Function<String, CompletableFuture<String>> executeCommand;
    private final String authority;
    private final String origin;
    private final byte[] token;
    private final URI address;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LocalControlServer(Supplier<CompletableFuture<String>> readState,
                              Function<String, CompletableFuture<String>> executeCommand) throws IOException {
        this.readState = Objects.requireNonNull(readState);
        this.executeCommand = Objects.requireNonNull(executeCommand);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String sessionToken = HexFormat.of().formatHex(random);
        token = sessionToken.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        authority = "127.0.0.1:" + server.getAddress().getPort();
        origin = "http://" + authority;
        address = URI.create(origin + "/#" + sessionToken);
        executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon()
                .name("bilicraft-control-" + server.getAddress().getPort() + "-", 0).factory());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        try {
            // HttpServer's dispatcher inherits daemon status from the thread calling start().
            executor.submit(server::start).get();
        } catch (InterruptedException exception) {
            close();
            Thread.currentThread().interrupt();
            throw new IOException("Local control server startup interrupted", exception);
        } catch (ExecutionException exception) {
            close();
            throw new IOException("Local control server could not start", exception.getCause());
        }
    }

    /** The fragment is read by the local page and never sent as a URL credential to the HTTP server. */
    public URI address() {
        return address;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; script-src 'self'; "
                    + "style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            headers.set("X-Frame-Options", "DENY");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            List<String> hosts = exchange.getRequestHeaders().get("Host");
            List<String> origins = exchange.getRequestHeaders().get("Origin");
            if (hosts == null || hosts.size() != 1 || !authority.equals(hosts.getFirst())
                    || (origins != null && (origins.size() != 1 || !origin.equals(origins.getFirst())))) {
                error(exchange, 403, "请求来源无效");
                return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                error(exchange, 404, "路径不存在");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String resource = switch (path) {
                case "/", "/index.html" -> "index.html";
                case "/style.css" -> "style.css";
                case "/app.js" -> "app.js";
                default -> null;
            };
            boolean state = path.equals("/api/state");
            boolean command = path.equals("/api/command");
            if (resource == null && !state && !command) {
                error(exchange, 404, "路径不存在");
                return;
            }
            if (state || command) {
                List<String> credentials = exchange.getRequestHeaders().get("X-BiliCraft-Token");
                if (credentials == null || credentials.size() != 1
                        || !MessageDigest.isEqual(token, credentials.getFirst().getBytes(StandardCharsets.UTF_8))) {
                    error(exchange, 403, "会话凭据无效");
                    return;
                }
            }
            String method = command ? "POST" : "GET";
            if (!exchange.getRequestMethod().equals(method)) {
                headers.set("Allow", method);
                error(exchange, 405, "请求方法不支持");
                return;
            }
            if (resource != null) {
                try (InputStream input = LocalControlServer.class.getResourceAsStream(
                        "/assets/bilicraftai/control/" + resource)) {
                    if (input == null) {
                        error(exchange, 404, "资源不存在");
                        return;
                    }
                    String contentType = switch (resource) {
                        case "index.html" -> "text/html; charset=utf-8";
                        case "style.css" -> "text/css; charset=utf-8";
                        default -> "text/javascript; charset=utf-8";
                    };
                    respond(exchange, 200, contentType, input.readAllBytes());
                }
                return;
            }
            String body = null;
            if (command) {
                List<String> contentTypes = exchange.getRequestHeaders().get("Content-Type");
                if (contentTypes == null || contentTypes.size() != 1
                        || !contentTypes.getFirst().split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                    error(exchange, 415, "命令必须使用 application/json");
                    return;
                }
                byte[] bytes;
                try {
                    bytes = exchange.getRequestBody().readNBytes(MAX_COMMAND_BYTES + 1);
                } catch (IOException exception) {
                    error(exchange, 400, "无法读取命令");
                    return;
                }
                if (bytes.length > MAX_COMMAND_BYTES) {
                    error(exchange, 413, "命令内容过大");
                    return;
                }
                try {
                    body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException exception) {
                    error(exchange, 400, "命令必须使用 UTF-8");
                    return;
                }
            }
            CompletableFuture<String> pending = null;
            String result;
            try {
                pending = state ? readState.get() : executeCommand.apply(body);
                result = pending.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                pending.cancel(false);
                Thread.currentThread().interrupt();
                error(exchange, 503, "本地控制暂时不可用");
                return;
            } catch (TimeoutException exception) {
                pending.cancel(false);
                error(exchange, 503, "游戏响应超时，请稍后重试");
                return;
            } catch (ExecutionException | RuntimeException exception) {
                error(exchange, 503, "本地控制暂时不可用");
                return;
            }
            respond(exchange, 200, JSON, result.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, JSON,
                ("{\"ok\":false,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
