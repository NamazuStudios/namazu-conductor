#!/usr/bin/env bash
# dokka-maven-plugin 2.0.0 has no customStyleSheets/pluginsConfiguration support (Gradle-only
# feature), so brand colors are applied by appending overrides onto the generated style.css.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${1:-target/dokka}"

cat "${SCRIPT_DIR}/styles/custom.css" >> "${OUTPUT_DIR}/styles/style.css"
