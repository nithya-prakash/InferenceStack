package com.inferencestack.gateway.auth;

import com.inferencestack.gateway.config.GatewayProperties;
import com.inferencestack.gateway.config.GatewayProperties.ApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Holds the configured clients. Only SHA-256 digests of keys are configured, so no plaintext key exists in
 * config. SHA-256 (not bcrypt) is appropriate because keys are long random strings, not human passwords.
 */
@Component
public class ApiKeyRegistry {
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");
    private final List<Entry> entries;

    private record Entry(ApiKey key, byte[] digest) {}

    public ApiKeyRegistry(GatewayProperties props) {
        if (props.apiKeys().isEmpty()) throw new IllegalStateException("no API keys configured (gateway.api-keys)");
        Set<String> ids = new HashSet<>();
        this.entries = props.apiKeys().stream().map(k -> {
            if (k.id() == null || k.id().isBlank() || !ids.add(k.id()))
                throw new IllegalStateException("API key ids must be non-blank and unique");
            if (k.sha256() == null || !HEX64.matcher(k.sha256()).matches())
                throw new IllegalStateException("API key '" + k.id() + "': sha256 must be 64 hex characters");
            if (k.requestsPerMinute() <= 0)
                throw new IllegalStateException("API key '" + k.id() + "': requests-per-minute must be > 0");
            return new Entry(k, HexFormat.of().parseHex(k.sha256()));
        }).toList();
    }

    public Optional<ApiKey> authenticate(String presented) {
        if (presented == null || presented.isEmpty()) return Optional.empty();
        byte[] digest = sha256(presented.getBytes(StandardCharsets.UTF_8));
        ApiKey match = null;
        for (Entry e : entries) {  // no early exit: same work whether or not a key matches
            if (MessageDigest.isEqual(digest, e.digest())) match = e.key();
        }
        return Optional.ofNullable(match);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) { return HexFormat.of().formatHex(sha256(data)); }
}
