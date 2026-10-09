#include <gtest/gtest.h>

#include <cmath>
#include <filesystem>
#include <fstream>
#include <thread>

#include "image.hpp"
#include "test_util.hpp"

TEST(ModelParity, MatchesPythonReference) {
    const auto ref = reference();
    const double rtol = ref["rtol"], atol = ref["atol"];
    ASSERT_GE(ref["cases"].size(), 5u);
    for (const auto& c : ref["cases"]) {
        const auto p = shared_model().run(decode_png(read_file(model_dir() + "/test_data/" + c["file"].get<std::string>())));
        const double expected = c["score"];
        EXPECT_TRUE(std::isfinite(p.score));
        EXPECT_LE(std::abs(p.score - expected), atol + rtol * std::abs(expected)) << c["file"] << " cpp=" << p.score << " py=" << expected;
        EXPECT_GT(p.inference_ms, 0.0);
    }
}

TEST(ModelParity, Deterministic) {
    const auto t = decode_png(make_png(224, 224, 3));
    EXPECT_EQ(shared_model().run(t).score, shared_model().run(t).score);
}

TEST(ModelConcurrency, ParallelRunsGiveSameResult) {
    const auto t = decode_png(make_png(224, 224, 3));
    const float expected = shared_model().run(t).score;
    std::vector<std::thread> ts;
    std::vector<int> bad(8, 0);
    for (int k = 0; k < 8; ++k)
        ts.emplace_back([&, k] { for (int i = 0; i < 10; ++i) if (shared_model().run(t).score != expected) ++bad[k]; });
    for (auto& th : ts) th.join();
    for (int b : bad) EXPECT_EQ(b, 0);
}

TEST(ModelContract, RejectsWrongTensorSize) {
    EXPECT_THROW(shared_model().run(std::vector<float>(10)), std::invalid_argument);
}

TEST(ModelContract, MetadataLoaded) {
    EXPECT_EQ(shared_model().meta().version, "padim-bottle-v1");
    EXPECT_GT(shared_model().meta().threshold, 0.f);
}

namespace {
std::string tmp_file(const std::string& name, const std::string& content) {
    const auto p = std::filesystem::temp_directory_path() / name;
    std::ofstream(p, std::ios::binary) << content;
    return p.string();
}
const std::string kOnnx = model_dir() + "/artifacts/padim_bottle.onnx", kMeta = model_dir() + "/artifacts/model_meta.json";
}  // namespace

TEST(ModelLoading, MissingModelFile) { EXPECT_THROW(Model("/nonexistent/m.onnx", kMeta), std::runtime_error); }
TEST(ModelLoading, CorruptModelFile) { EXPECT_THROW(Model(tmp_file("corrupt.onnx", "not an onnx model"), kMeta), std::runtime_error); }
TEST(ModelLoading, TruncatedModelFile) {
    const auto bytes = read_file(kOnnx);
    EXPECT_THROW(Model(tmp_file("trunc.onnx", bytes.substr(0, bytes.size() / 3)), kMeta), std::runtime_error);
}
TEST(ModelLoading, MissingMetadata) { EXPECT_THROW(Model(kOnnx, "/nonexistent/meta.json"), std::runtime_error); }
TEST(ModelLoading, InvalidMetadata) {
    EXPECT_THROW(Model(kOnnx, tmp_file("bad_meta.json", "{\"model_version\": 1}")), std::runtime_error);
    EXPECT_THROW(Model(kOnnx, tmp_file("bad_meta2.json", "not json")), std::runtime_error);
}
