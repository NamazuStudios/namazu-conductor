# Builds namazu-stdio-bridge as a single, libc-portable binary so it can be copied into arbitrary
# downstream images (glibc- or musl-based) via the multi-stage `COPY --from=` pattern documented in
# README.md. PyInstaller alone only produces a binary dynamically linked against the builder's own
# libc; staticx bundles that libc into the binary itself so it runs unmodified on any target.
FROM python:3.12-slim AS builder

RUN apt-get update && apt-get install -y --no-install-recommends patchelf binutils && rm -rf /var/lib/apt/lists/*

WORKDIR /build

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt pyinstaller staticx

COPY src ./src
RUN pyinstaller --onefile --name namazu-stdio-bridge --paths src src/namazu_stdio_bridge/__main__.py
RUN staticx dist/namazu-stdio-bridge dist/namazu-stdio-bridge-static

FROM alpine:3.20
COPY --from=builder /build/dist/namazu-stdio-bridge-static /usr/local/bin/namazu-stdio-bridge
ENTRYPOINT ["/usr/local/bin/namazu-stdio-bridge"]
