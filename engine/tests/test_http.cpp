#include <gtest/gtest.h>

#include <thread>

#include <nlohmann/json.hpp>

#include "server.hpp"
#include "test_util.hpp"

namespace {
class HttpTest : public ::testing::Test {
protected:
    void SetUp() override {
        register_routes(svr_, shared_model());
        port_ = svr_.bind_to_any_port("127.0.0.1");
        ASSERT_GT(port_, 0);
        th_ = std::thread([this] { svr_.listen_after_bind(); });
        svr_.wait_until_ready();
        cli_ = std::make_unique<httplib::Client>("127.0.0.1", port_);
    }
    void TearDown() override { svr_.stop(); th_.join(); }
    httplib::Result post(const std::string& body, const std::string& type = "image/png") { return cli_->Post("/predict", body, type); }

    httplib::Server svr_;
    int port_ = 0;
    std::thread th_;
    std::unique_ptr<httplib::Client> cli_;
};
}  // namespace

TEST_F(HttpTest, Health) {
    auto r = cli_->Get("/health");
    ASSERT_TRUE(r);
    EXPECT_EQ(r->status, 200);
    const auto j = nlohmann::json::parse(r->body);
    EXPECT_EQ(j["status"], "ready");
    EXPECT_EQ(j["model_version"], "padim-bottle-v1");
}

TEST_F(HttpTest, PredictMatchesReference) {
    const auto ref = reference();
    for (const auto& c : ref["cases"]) {
        auto r = post(read_file(model_dir() + "/test_data/" + c["file"].get<std::string>()));
        ASSERT_TRUE(r);
        ASSERT_EQ(r->status, 200) << r->body;
        const auto j = nlohmann::json::parse(r->body);
        const double expected = c["score"];
        EXPECT_NEAR(j["anomaly_score"].get<double>(), expected, ref["atol"].get<double>() + ref["rtol"].get<double>() * std::abs(expected));
        EXPECT_EQ(j["is_anomalous"].get<bool>(), j["anomaly_score"].get<double>() > j["threshold"].get<double>());
        EXPECT_EQ(j["model_version"], "padim-bottle-v1");
        EXPECT_TRUE(j["inference_ms"].is_number());
    }
}

TEST_F(HttpTest, ErrorsHaveStatusAndJsonBody) {
    struct Case { std::string body, type; int status; std::string code; };
    const std::vector<Case> cases = {
        {"", "image/png", 400, "empty_body"},
        {"garbage", "image/png", 400, "not_png"},
        {make_png(224, 224, 3), "application/json", 415, "unsupported_media_type"},
        {make_png(64, 64, 3), "image/png", 422, "wrong_dimensions"},
        {make_png(224, 224, 4), "image/png", 422, "unsupported_channels"},
    };
    for (const auto& c : cases) {
        auto r = post(c.body, c.type);
        ASSERT_TRUE(r) << c.code;
        EXPECT_EQ(r->status, c.status) << c.code;
        EXPECT_EQ(nlohmann::json::parse(r->body)["error"]["code"], c.code);
    }
}

TEST_F(HttpTest, OversizedBodyRejected) {
    auto r = post(std::string(kMaxBodyBytes + 1024, 'x'));
    ASSERT_TRUE(r);
    EXPECT_EQ(r->status, 413);
}

TEST_F(HttpTest, ConcurrentRequests) {
    const std::string png = make_png(224, 224, 3);
    std::vector<std::thread> ts;
    std::vector<int> ok(6, 0);
    for (int k = 0; k < 6; ++k)
        ts.emplace_back([&, k] {
            httplib::Client c("127.0.0.1", port_);
            for (int i = 0; i < 5; ++i) { auto r = c.Post("/predict", png, "image/png"); if (r && r->status == 200) ++ok[k]; }
        });
    for (auto& t : ts) t.join();
    for (int n : ok) EXPECT_EQ(n, 5);
}

TEST_F(HttpTest, UnknownRouteIs404) { EXPECT_EQ(cli_->Get("/nope")->status, 404); }
