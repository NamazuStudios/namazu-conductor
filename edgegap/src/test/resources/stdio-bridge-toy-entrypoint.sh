#!/bin/sh
# Toy "entrypoint" for StdioBridgeClientIT: echoes each stdin line to stdout and stderr, exits with
# a distinct code on "quit" so the test can assert against a known, deterministic transcript.
echo "stdout-startup-banner"
echo "stderr-startup-banner" >&2
while IFS= read -r line; do
  echo "echo:$line"
  echo "stderr-echo:$line" >&2
  if [ "$line" = "quit" ]; then
    exit 7
  fi
done
