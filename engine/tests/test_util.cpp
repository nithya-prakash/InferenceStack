#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "test_util.hpp"

#include <cstdlib>
#include <fstream>
#include <sstream>

#include "stb_image_write.h"

std::string model_dir() {
    const char* d = std::getenv("ENGINE_TEST_MODEL_DIR");
    return d && *d ? d : "../../model";
}

std::string read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

std::string make_png(int w, int h, int channels) {
    std::vector<unsigned char> px(static_cast<size_t>(w) * h * channels);
    for (size_t i = 0; i < px.size(); ++i) px[i] = static_cast<unsigned char>(i * 7 % 256);
    std::string out;
    stbi_write_png_to_func([](void* ctx, void* data, int n) { static_cast<std::string*>(ctx)->append(static_cast<char*>(data), n); },
                           &out, w, h, channels, px.data(), w * channels);
    return out;
}

const Model& shared_model() {
    static const Model m(model_dir() + "/artifacts/padim_bottle.onnx", model_dir() + "/artifacts/model_meta.json");
    return m;
}

nlohmann::json reference() { return nlohmann::json::parse(read_file(model_dir() + "/test_data/reference.json")); }
