#include <cstdlib>
#include <iostream>
#include <string>

#include "server.hpp"

namespace {
std::string env_or(const char* name, const std::string& fallback) {
    const char* v = std::getenv(name);
    return v && *v ? v : fallback;
}
}  // namespace

// Used by Docker HEALTHCHECK: exits 0 only if the local engine answers GET /health with 200.
int healthcheck(int port) {
    httplib::Client client("127.0.0.1", port);
    client.set_connection_timeout(2);
    auto res = client.Get("/health");
    return res && res->status == 200 ? 0 : 1;
}

int main(int argc, char** argv) {
    const std::string dir = env_or("ENGINE_MODEL_DIR", "model/artifacts");
    const int port = std::stoi(env_or("ENGINE_PORT", "8081"));
    const int threads = std::stoi(env_or("ENGINE_INTRA_OP_THREADS", "1"));
    if (argc > 1 && std::string(argv[1]) == "--healthcheck") return healthcheck(port);
    try {
        // Fail fast: if the model cannot load, the process exits non-zero and never reports "ready".
        Model model(dir + "/padim_bottle.onnx", dir + "/model_meta.json", threads);
        httplib::Server svr;
        register_routes(svr, model);
        std::cout << "model " << model.meta().version << " loaded; listening on :" << port << std::endl;
        if (!svr.listen("0.0.0.0", port)) {
            std::cerr << "cannot listen on port " << port << "\n";
            return 1;
        }
    } catch (const std::exception& e) {
        std::cerr << "startup failed: " << e.what() << "\n";
        return 1;
    }
    return 0;
}
