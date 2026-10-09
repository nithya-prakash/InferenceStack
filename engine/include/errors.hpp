#pragma once
#include <stdexcept>
#include <string>

// A problem with the client's request. `status` is the HTTP status, `code` a stable machine-readable tag.
struct RequestError : std::runtime_error {
    int status;
    std::string code;
    RequestError(int status, std::string code, const std::string& message)
        : std::runtime_error(message), status(status), code(std::move(code)) {}
};
