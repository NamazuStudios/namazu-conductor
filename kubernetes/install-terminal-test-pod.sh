#!/bin/bash
#
# Applies two sample PodTemplates to the current kubectl context, so the admin dashboard's web-based
# terminal (xterm.js) can be tested end-to-end against real pods. Run ./kubernetes/start-minikube.sh
# first.
#
# 'conductor-terminal-test' (two containers, `shell` + `sidecar`) and 'conductor-opencode-test' (one
# container, `opencode`) both just sleep so their pod stays Running on its own. The
# `namazu.conductor/terminal-job` annotation marks each profile as terminal-capable: the admin
# dashboard's "Available Jobs & Services" page shows a one-click "Start Terminal 💻" button for it,
# which launches the job with tty/command defaults implied automatically (see
# ConductorAdminJobsResource.execute()) — no manual form needed; this always targets the
# primary/first container.
#
# Each container also declares its own `namazu.conductor/default-container-exec.<name>` annotation,
# which pre-fills that container's "Attach Terminal" input on the Running Jobs page — `shell` defaults
# to `/bin/bash`, `sidecar` to `/bin/sh`, `opencode` to `opencode` itself, demonstrating that the
# default is genuinely per-container rather than shared across the whole profile.
#
# The `opencode` container installs the latest stable build of the opencode CLI
# (https://opencode.ai) at startup via its official install script — this requires the cluster to
# have outbound internet access. The installed binary's location isn't hardcoded (the installer's own
# layout isn't part of this script's contract), so the startup command searches for it afterward and
# symlinks it to /usr/local/bin so a plain `opencode` exec (no shell, no profile sourcing) resolves it.
#
# Usage:
#     ./kubernetes/install-terminal-test-pod.sh
#
# Cleanup:
#     kubectl delete podtemplate conductor-terminal-test conductor-opencode-test -n default
#
set -euo pipefail

kubectl apply -f - <<'EOF'
apiVersion: v1
kind: PodTemplate
metadata:
  name: conductor-terminal-test
  namespace: default
  labels:
    namazu.conductor/job-set: default
  annotations:
    namazu.conductor/workload-kind: pod
    namazu.conductor/terminal-job: "true"
    namazu.conductor/default-container-exec.shell: "/bin/bash"
    namazu.conductor/default-container-exec.sidecar: "/bin/sh"
    namazu.conductor/description: |
      A minimal two-container pod for testing the admin dashboard's web-based terminal.

      - `shell` (`bash:5`) — the primary container; *Start Terminal* attaches to it automatically,
        with `/bin/bash` pre-filled when attaching manually from Running Jobs.
      - `sidecar` (`alpine:3`) — only reachable via Running Jobs' per-container Attach Terminal row,
        pre-filled with `/bin/sh`.
      - Both stay alive on their own via `sleep infinity`.
template:
  spec:
    containers:
      - name: shell
        image: bash:5
        command: ["bash", "-c", "sleep infinity"]
      - name: sidecar
        image: alpine:3
        command: ["sh", "-c", "sleep infinity"]
EOF

kubectl apply -f - <<'EOF'
apiVersion: v1
kind: PodTemplate
metadata:
  name: conductor-opencode-test
  namespace: default
  labels:
    namazu.conductor/job-set: default
  annotations:
    namazu.conductor/workload-kind: pod
    namazu.conductor/terminal-job: "true"
    namazu.conductor/default-container-exec.opencode: "opencode"
    namazu.conductor/description: |
      A single-container pod running the latest stable [opencode](https://opencode.ai) CLI, for
      testing the admin dashboard's web-based terminal against a real AI coding agent session.

      - `opencode` (`debian:bookworm-slim`) — installs opencode at startup, then stays alive via
        `sleep infinity`. Debian (glibc), not Alpine (musl), since opencode's installer distributes a
        precompiled binary that isn't guaranteed to run against musl's dynamic linker.
      - Attaching a terminal (via *Start Terminal* or Running Jobs) runs `opencode` automatically.
      - Requires the cluster to have outbound internet access to reach opencode.ai.
template:
  spec:
    containers:
      - name: opencode
        image: debian:bookworm-slim
        command:
          - sh
          - -c
          - |
            apt-get update >/dev/null 2>&1
            apt-get install -y --no-install-recommends curl ca-certificates >/dev/null 2>&1
            curl -fsSL https://opencode.ai/install | bash
            OC_BIN=$(find /root /home /usr /opt -maxdepth 6 -type f -name opencode 2>/dev/null | head -n1)
            [ -n "$OC_BIN" ] && ln -sf "$OC_BIN" /usr/local/bin/opencode
            sleep infinity
EOF

echo
echo "Applied PodTemplate 'conductor-terminal-test' to namespace 'default'."
echo "In the admin dashboard's Available Jobs & Services page, click 'Start Terminal 💻' to launch it"
echo "(attaches to the 'shell' container). Once running, attach to either 'shell' or 'sidecar' from"
echo "the Running Jobs page's per-container Attach Terminal row."
echo
echo "Applied PodTemplate 'conductor-opencode-test' to namespace 'default'."
echo "Once its 'opencode' container finishes installing (check with 'kubectl logs'), attach a terminal"
echo "from Running Jobs — 'opencode' will be pre-filled and ready to run."
