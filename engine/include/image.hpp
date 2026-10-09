#pragma once
#include <string>
#include <vector>

constexpr int kImageSize = 224;
constexpr size_t kTensorFloats = 3 * kImageSize * kImageSize;

// Validates and decodes a 224x224 8-bit RGB PNG into a CHW float32 tensor with raw 0..255 values.
// No resizing, scaling or normalization: the ONNX graph owns those. Throws RequestError.
std::vector<float> decode_png(const std::string& bytes);
