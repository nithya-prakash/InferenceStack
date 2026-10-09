#include "image.hpp"

#include <cstring>
#include <memory>

#include "errors.hpp"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#include "stb_image.h"

namespace {
const unsigned char kPngSignature[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
struct StbiDeleter {
    void operator()(unsigned char* p) const { stbi_image_free(p); }
};
}  // namespace

std::vector<float> decode_png(const std::string& bytes) {
    if (bytes.empty()) throw RequestError(400, "empty_body", "request body is empty");
    if (bytes.size() < 8 || std::memcmp(bytes.data(), kPngSignature, 8) != 0)
        throw RequestError(400, "not_png", "body is not a PNG (bad signature)");

    const auto* data = reinterpret_cast<const unsigned char*>(bytes.data());
    const int len = static_cast<int>(bytes.size());
    int w = 0, h = 0, comp = 0;
    // Read the header first so a huge declared size is rejected before any pixel allocation.
    if (!stbi_info_from_memory(data, len, &w, &h, &comp))
        throw RequestError(400, "invalid_png", "cannot parse PNG header");
    if (stbi_is_16_bit_from_memory(data, len))
        throw RequestError(422, "unsupported_bit_depth", "only 8-bit PNG is supported");
    if (w != kImageSize || h != kImageSize)
        throw RequestError(422, "wrong_dimensions", "image must be 224x224, got " + std::to_string(w) + "x" + std::to_string(h));
    if (comp != 3) throw RequestError(422, "unsupported_channels", "image must be RGB (3 channels), got " + std::to_string(comp));

    std::unique_ptr<unsigned char, StbiDeleter> px(stbi_load_from_memory(data, len, &w, &h, &comp, 3));
    if (!px) throw RequestError(400, "invalid_png", "PNG data is corrupt or truncated");

    std::vector<float> chw(kTensorFloats);  // HWC uint8 -> CHW float32
    const size_t plane = static_cast<size_t>(kImageSize) * kImageSize;
    for (size_t i = 0; i < plane; ++i)
        for (size_t c = 0; c < 3; ++c) chw[c * plane + i] = static_cast<float>(px.get()[i * 3 + c]);
    return chw;
}
