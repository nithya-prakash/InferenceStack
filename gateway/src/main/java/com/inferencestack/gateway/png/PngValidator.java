package com.inferencestack.gateway.png;

import com.inferencestack.gateway.web.ApiException;
import java.nio.ByteBuffer;

/**
 * Cheap header-level check of the model contract (224x224, 8-bit RGB PNG) so obviously bad requests never
 * reach the engine. It does not decode pixels; the engine does the full decode and can still reject.
 */
public final class PngValidator {
    public static final int SIZE = 224;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int IHDR_END = 8 + 8 + 13;  // signature + chunk header + IHDR data

    private PngValidator() {}

    public static void validate(String contentType, byte[] body) {
        if (contentType == null || !contentType.toLowerCase().startsWith("image/png"))
            throw new ApiException(415, "unsupported_media_type", "Content-Type must be image/png");
        if (body.length == 0) throw new ApiException(400, "empty_body", "request body is empty");
        if (body.length < IHDR_END || !startsWithSignature(body) || !"IHDR".equals(new String(body, 12, 4)))
            throw new ApiException(400, "not_png", "body is not a PNG");
        ByteBuffer b = ByteBuffer.wrap(body);  // big-endian, as PNG
        int w = b.getInt(16), h = b.getInt(20);
        if (w != SIZE || h != SIZE)
            throw new ApiException(422, "wrong_dimensions", "image must be 224x224, got " + w + "x" + h);
        if (body[24] != 8) throw new ApiException(422, "unsupported_bit_depth", "only 8-bit PNG is supported");
        if (body[25] != 2) throw new ApiException(422, "unsupported_channels", "image must be RGB without alpha");
    }

    private static boolean startsWithSignature(byte[] body) {
        for (int i = 0; i < SIGNATURE.length; i++) if (body[i] != SIGNATURE[i]) return false;
        return true;
    }
}
