#include "model.hpp"

#include <chrono>
#include <cmath>
#include <fstream>
#include <stdexcept>

#include <nlohmann/json.hpp>

#include "image.hpp"

namespace {
ModelMeta read_meta(const std::string& path) {
    std::ifstream f(path);
    if (!f) throw std::runtime_error("cannot open model metadata: " + path);
    try {
        auto j = nlohmann::json::parse(f);
        return {j.at("model_version").get<std::string>(), j.at("threshold").get<float>()};
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("invalid model metadata: ") + e.what());
    }
}
}  // namespace

Model::Model(const std::string& onnx_path, const std::string& meta_path, int intra_op_threads)
    : env_(ORT_LOGGING_LEVEL_WARNING, "engine"), meta_(read_meta(meta_path)) {
    env_.DisableTelemetryEvents();  // no data leaves the process
    try {
        Ort::SessionOptions opts;
        opts.SetIntraOpNumThreads(intra_op_threads);
        session_ = std::make_unique<Ort::Session>(env_, onnx_path.c_str(), opts);

        Ort::AllocatorWithDefaultOptions alloc;
        if (session_->GetInputCount() != 1 || std::string(session_->GetInputNameAllocated(0, alloc).get()) != "image")
            throw std::runtime_error("model must have exactly one input named 'image'");
        if (session_->GetOutputCount() < 1 || std::string(session_->GetOutputNameAllocated(0, alloc).get()) != "score")
            throw std::runtime_error("model's first output must be 'score'");
    } catch (const Ort::Exception& e) {
        throw std::runtime_error(std::string("failed to load ONNX model: ") + e.what());
    }
}

Prediction Model::run(const std::vector<float>& chw) const {
    if (chw.size() != kTensorFloats) throw std::invalid_argument("tensor has wrong size");
    const std::array<int64_t, 4> shape{1, 3, kImageSize, kImageSize};
    auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    Ort::Value input = Ort::Value::CreateTensor<float>(mem, const_cast<float*>(chw.data()), chw.size(), shape.data(), shape.size());
    const char* in_names[] = {"image"};
    const char* out_names[] = {"score"};  // heatmap is not requested

    const auto t0 = std::chrono::steady_clock::now();
    auto out = session_->Run(Ort::RunOptions{nullptr}, in_names, &input, 1, out_names, 1);
    const auto t1 = std::chrono::steady_clock::now();

    const float score = out[0].GetTensorData<float>()[0];
    if (!std::isfinite(score)) throw std::runtime_error("model produced a non-finite score");
    return {score, std::chrono::duration<double, std::milli>(t1 - t0).count()};
}
