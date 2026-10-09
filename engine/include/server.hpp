#pragma once
#include <httplib.h>

#include "model.hpp"

constexpr size_t kMaxBodyBytes = 1 << 20;  // 1 MiB; a 224x224 RGB PNG is far smaller

// Registers GET /health and POST /predict. `model` must outlive the server.
void register_routes(httplib::Server& svr, const Model& model);
