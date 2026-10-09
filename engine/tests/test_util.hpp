#pragma once
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "model.hpp"

std::string model_dir();                       // ENGINE_TEST_MODEL_DIR or ../../model (repo's model/ folder)
std::string read_file(const std::string& path);
std::string make_png(int w, int h, int channels);  // deterministic gradient PNG
const Model& shared_model();                   // loaded once for all tests
nlohmann::json reference();                    // model/test_data/reference.json
