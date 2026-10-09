package com.inferencestack.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/** A controllable stand-in for the C++ engine (JDK HttpServer, no extra dependencies). */
public class FakeEngine {
    public enum Mode { OK, SLOW, HTTP_500, BAD_JSON, REJECT_422 }

    public volatile Mode mode = Mode.OK;
    public volatile String modelVersion = "fake-v1";
    public final AtomicInteger predictCalls = new AtomicInteger();
    private final HttpServer server;

    public FakeEngine() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/health", ex -> reply(ex, 200, "{\"status\":\"ready\",\"model_version\":\"" + modelVersion + "\"}"));
        server.createContext("/predict", ex -> {
            predictCalls.incrementAndGet();
            int len = ex.getRequestBody().readAllBytes().length;
            switch (mode) {
                case SLOW -> { sleep(1500); reply(ex, 200, ok(len)); }
                case HTTP_500 -> reply(ex, 500, "{\"error\":{\"code\":\"inference_failed\",\"message\":\"boom\"}}");
                case BAD_JSON -> reply(ex, 200, "{\"anomaly_score\":\"not a number\"}");
                case REJECT_422 -> reply(ex, 422, "{\"error\":{\"code\":\"wrong_dimensions\",\"message\":\"bad image\"}}");
                default -> reply(ex, 200, ok(len));
            }
        });
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public void reset() {
        mode = Mode.OK;
        modelVersion = "fake-v1";
        predictCalls.set(0);
    }

    private String ok(int len) {
        double score = 10 + (len % 50);  // deterministic per image size
        return "{\"anomaly_score\":" + score + ",\"is_anomalous\":" + (score > 30) + ",\"threshold\":30.0,\"model_version\":\""
            + modelVersion + "\",\"inference_ms\":1.5}";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes();
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
