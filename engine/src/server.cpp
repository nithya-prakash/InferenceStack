#include "server.hpp"

#include <nlohmann/json.hpp>

#include "errors.hpp"
#include "image.hpp"

using nlohmann::json;

namespace {
void send_error(httplib::Response& res, int status, const std::string& code, const std::string& msg) {
    res.status = status;
    res.set_content(json{{"error", {{"code", code}, {"message", msg}}}}.dump(), "application/json");
}

void handle_predict(const Model& model, const httplib::Request& req, httplib::Response& res) {
    try {
        if (req.get_header_value("Content-Type").rfind("image/png", 0) != 0)
            throw RequestError(415, "unsupported_media_type", "Content-Type must be image/png");
        const auto tensor = decode_png(req.body);
        const Prediction p = model.run(tensor);
        res.set_content(json{{"anomaly_score", p.score},
                             {"is_anomalous", p.score > model.meta().threshold},
                             {"threshold", model.meta().threshold},
                             {"model_version", model.meta().version},
                             {"inference_ms", p.inference_ms}}.dump(),
                        "application/json");
    } catch (const RequestError& e) {
        send_error(res, e.status, e.code, e.what());
    } catch (const std::exception& e) {  // Ort::Exception derives from std::exception
        send_error(res, 500, "inference_failed", e.what());
    }
}
}  // namespace

void register_routes(httplib::Server& svr, const Model& model) {
    svr.set_payload_max_length(kMaxBodyBytes);  // larger bodies are rejected with 413
    svr.set_tcp_nodelay(true);                  // avoid Nagle + delayed-ACK stalls (~40 ms) on small responses
    svr.Get("/health", [&model](const httplib::Request&, httplib::Response& res) {
        res.set_content(json{{"status", "ready"}, {"model_version", model.meta().version}}.dump(), "application/json");
    });
    svr.Post("/predict", [&model](const httplib::Request& req, httplib::Response& res) { handle_predict(model, req, res); });
}
