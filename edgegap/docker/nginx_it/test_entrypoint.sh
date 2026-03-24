#!/bin/sh
set -e

OUTPUT=/usr/share/nginx/html/test_context.json

# Build args JSON array from positional parameters
args_json='['
first=1
for arg in "$@"; do
    escaped=$(printf '%s' "$arg" | sed 's/\\/\\\\/g; s/"/\\"/g')
    if [ "$first" = "1" ]; then
        args_json="${args_json}\"${escaped}\""
        first=0
    else
        args_json="${args_json},\"${escaped}\""
    fi
done
args_json="${args_json}]"

# Build env JSON object from environment variables
env_json='{'
first=1
while IFS= read -r line; do
    key="${line%%=*}"
    case "$key" in TEST*) ;; *) continue ;; esac
    value="${line#*=}"
    escaped_key=$(printf '%s' "$key" | sed 's/\\/\\\\/g; s/"/\\"/g')
    escaped_value=$(printf '%s' "$value" | sed 's/\\/\\\\/g; s/"/\\"/g')
    if [ "$first" = "1" ]; then
        env_json="${env_json}\"${escaped_key}\":\"${escaped_value}\""
        first=0
    else
        env_json="${env_json},\"${escaped_key}\":\"${escaped_value}\""
    fi
done << EOF
$(env)
EOF
env_json="${env_json}}"

printf '{"args":%s,"environment":%s}\n' "$args_json" "$env_json" > "$OUTPUT"

exec nginx -g "daemon off;"