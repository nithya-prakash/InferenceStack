#pragma once
#include <memory>
#include <string>
#include <vector>

#include <onnxruntime_cxx_api.h>

struct ModelMeta {
    std::string version;
    float threshold = 0.f;
};

struct Prediction {
    float score;
    double inference_ms;  // ONNX Runtime Run() only: excludes HTTP parsing and PNG decoding
};

// Owns the ONNX Runtime session. Loaded once; run() is safe to call from many threads
// (Ort::Session::Run is thread-safe, and each call uses its own buffers).
class Model {
public:
    // Throws std::runtime_error if the model or metadata is missing, corrupt, or violates the contract.
    Model(const std::string& onnx_path, const std::string& meta_path, int intra_op_threads = 1);
    Prediction run(const std::vector<float>& chw) const;
    const ModelMeta& meta() const { return meta_; }

private:
    Ort::Env env_;
    std::unique_ptr<Ort::Session> session_;
    ModelMeta meta_;
};
