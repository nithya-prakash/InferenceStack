package com.inferencestack.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferencestack.gateway.auth.ApiKeyRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String KEY_A = "test-key-a-0123456789", KEY_B = "test-key-b-0123456789";

    private TestSupport() {}

    /** Database comes from TEST_DB_* env vars (see gateway/run-tests.sh). */
    static void databaseProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/inferencestack_test"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "postgres"));
    }

    static void keyProps(DynamicPropertyRegistry r, int rpmA, int rpmB) {
        r.add("gateway.api-keys[0].id", () -> "client-a");
        r.add("gateway.api-keys[0].sha256", () -> ApiKeyRegistry.sha256Hex(KEY_A.getBytes(StandardCharsets.UTF_8)));
        r.add("gateway.api-keys[0].requests-per-minute", () -> rpmA);
        r.add("gateway.api-keys[1].id", () -> "client-b");
        r.add("gateway.api-keys[1].sha256", () -> ApiKeyRegistry.sha256Hex(KEY_B.getBytes(StandardCharsets.UTF_8)));
        r.add("gateway.api-keys[1].requests-per-minute", () -> rpmB);
    }

    /** A valid 224x224 RGB PNG; different seeds give different bytes. */
    static byte[] png(int seed) {
        return png(224, 224, BufferedImage.TYPE_INT_RGB, seed);
    }

    static byte[] png(int w, int h, int type, int seed) {
        try {
            BufferedImage img = new BufferedImage(w, h, type);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 3 + y * 5 + seed * 977) * 0x010203 | 0xff000000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static HttpResponse<String> post(int port, String key, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/predict"))
            .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (key != null) b.header("X-API-Key", key);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
