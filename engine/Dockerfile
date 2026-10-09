# Build context: repository root (needs engine/ and model/).  docker build -f engine/Dockerfile .
# Targets: test (runs GoogleTest), runtime (slim image running the engine).
FROM ubuntu:24.04 AS build
ARG TARGETARCH
ARG ORT_VERSION=1.31.0
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN case "$TARGETARCH" in amd64) A=x64;; arm64) A=aarch64;; *) echo "unsupported arch $TARGETARCH"; exit 1;; esac \
    && curl -fsSL "https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/onnxruntime-linux-${A}-${ORT_VERSION}.tgz" | tar xz -C /opt \
    && mv /opt/onnxruntime-linux-* /opt/ort
COPY engine /src/engine
RUN cmake -S /src/engine -B /build -DORT_ROOT=/opt/ort -DCMAKE_BUILD_TYPE=Release && cmake --build /build -j"$(nproc)"

FROM build AS test
COPY model/artifacts /src/model/artifacts
COPY model/test_data /src/model/test_data
ENV ENGINE_TEST_MODEL_DIR=/src/model
WORKDIR /build
CMD ["ctest", "--output-on-failure"]

FROM ubuntu:24.04 AS runtime
RUN useradd --system --no-create-home engine
COPY --from=build /opt/ort/lib/libonnxruntime.so* /usr/local/lib/
COPY --from=build /build/engine /usr/local/bin/engine
COPY model/artifacts /app/model/artifacts
RUN ldconfig
ENV ENGINE_MODEL_DIR=/app/model/artifacts ENGINE_PORT=8081
USER engine
EXPOSE 8081
CMD ["engine"]
