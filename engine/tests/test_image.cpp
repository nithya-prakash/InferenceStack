#include <gtest/gtest.h>

#include "errors.hpp"
#include "image.hpp"
#include "test_util.hpp"

namespace {
void expect_error(const std::string& body, int status, const std::string& code) {
    try {
        decode_png(body);
        FAIL() << "expected RequestError " << code;
    } catch (const RequestError& e) {
        EXPECT_EQ(e.status, status);
        EXPECT_EQ(e.code, code);
    }
}
}  // namespace

TEST(DecodePng, ValidRgbGivesChwRawPixels) {
    const auto t = decode_png(make_png(224, 224, 3));
    ASSERT_EQ(t.size(), kTensorFloats);
    // make_png: byte i = i*7%256 in HWC order. Pixel (0,1) channel 2 is byte index 5 -> 35.
    EXPECT_FLOAT_EQ(t[0 * 224 * 224 + 0], 0.f);
    EXPECT_FLOAT_EQ(t[2 * 224 * 224 + 1], 35.f);   // channel 2, pixel 1 (x=1,y=0): byte 1*3+2 = 5 -> 35
    for (float v : t) { ASSERT_GE(v, 0.f); ASSERT_LE(v, 255.f); }
}

TEST(DecodePng, EmptyBody) { expect_error("", 400, "empty_body"); }
TEST(DecodePng, NotPng) { expect_error("hello, this is not an image", 400, "not_png"); }
TEST(DecodePng, ShortGarbage) { expect_error("abc", 400, "not_png"); }
TEST(DecodePng, TruncatedPng) {
    const auto png = make_png(224, 224, 3);
    expect_error(png.substr(0, png.size() / 2), 400, "invalid_png");
}
TEST(DecodePng, HeaderOnly) { expect_error(make_png(224, 224, 3).substr(0, 20), 400, "invalid_png"); }
TEST(DecodePng, WrongDimensions) {
    expect_error(make_png(128, 128, 3), 422, "wrong_dimensions");
    expect_error(make_png(224, 225, 3), 422, "wrong_dimensions");
}
TEST(DecodePng, Grayscale) { expect_error(make_png(224, 224, 1), 422, "unsupported_channels"); }
TEST(DecodePng, Rgba) { expect_error(make_png(224, 224, 4), 422, "unsupported_channels"); }
