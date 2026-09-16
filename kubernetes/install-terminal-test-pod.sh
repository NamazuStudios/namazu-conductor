#!/bin/bash
#
# Applies two sample PodTemplates to the current kubectl context, so the admin dashboard's web-based
# terminal (xterm.js) can be tested end-to-end against real pods. Run ./kubernetes/start-minikube.sh
# first.
#
# 'bash' (two containers, `shell` + `sidecar`) and 'opencode' (three containers, one per AI coding
# agent CLI: `opencode`, `claude`, `qwen`) both just sleep so their pod stays Running on its own. The
# `namazu.conductor/terminal-job` annotation marks each profile as terminal-capable: the admin
# dashboard's "Available Jobs & Services" page shows a one-click "Start Terminal 💻" button for it,
# which launches the job with tty/command defaults implied automatically (see
# ConductorAdminJobsResource.execute()) — no manual form needed; this always targets the
# primary/first container.
#
# Each container also declares its own `namazu.conductor/default-container-exec.<name>` annotation,
# which pre-fills that container's "Attach Terminal" input on the Running Jobs page — `shell` defaults
# to `/bin/bash`, `sidecar` to `/bin/sh`, `opencode`/`claude`/`qwen` to themselves, demonstrating that
# the default is genuinely per-container rather than shared across the whole profile.
#
# `opencode` and `claude` use openEuler's official pre-built images (openeuler/opencode,
# openeuler/claude-code) with the CLI already on PATH — confirmed via `docker inspect`/`docker run`
# before writing this, since an earlier version of this script tried installing opencode itself via a
# curl|bash script at container startup and that install silently failed in practice (the pod stayed
# Running with no error surfaced anywhere except `kubectl logs`, and the CLI was simply never on PATH).
# There's no equivalent official image for qwen-code, so `qwen` still installs itself at startup via
# `npm install -g @qwen-code/qwen-code` on a node:22 base image (verified working locally) — its
# install output is intentionally not silenced, so `kubectl logs` shows what happened if it ever fails.
#
# Usage:
#     ./kubernetes/install-terminal-test-pod.sh
#
# Cleanup:
#     kubectl delete podtemplate bash opencode -n default
#
set -euo pipefail

kubectl apply -f - <<'EOF'
apiVersion: v1
kind: PodTemplate
metadata:
  name: bash
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
  name: agents
  namespace: default
  labels:
    namazu.conductor/job-set: default
  annotations:
    namazu.conductor/workload-kind: pod
    namazu.conductor/terminal-job: "true"
    namazu.conductor/default-container-exec.opencode: "opencode"
    namazu.conductor/default-container-exec.claude: "claude"
    namazu.conductor/default-container-exec.qwen: "qwen"
    namazu.conductor/description: |
      A three-container pod, one per AI coding agent CLI, for testing the admin dashboard's web-based
      terminal against a real agent session.

      - `opencode` ([opencode.ai](https://opencode.ai), `openeuler/opencode:latest`) — the primary
        container; *Start Terminal* attaches to it automatically.
      - `claude` ([Claude Code](https://code.claude.com), `openeuler/claude-code`) — only reachable via
        Running Jobs' per-container Attach Terminal row. Note: this image bakes in
        `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN` pointing at a local Ollama endpoint by default; a
        real session needs its own auth configured.
      - `qwen` ([Qwen Code](https://github.com/QwenLM/qwen-code)) — same, via Running Jobs. Installs
        itself via npm at startup (no official image exists for it); check `kubectl logs <pod> -c
        qwen` if it's not on PATH yet.
      - All three stay alive via `sleep infinity`. `qwen` requires outbound internet access to the npm
        registry at startup.
template:
  spec:
    containers:
      - name: opencode
        image: openeuler/opencode:latest
        command: ["sleep", "infinity"]
      - name: claude
        image: openeuler/claude-code:2.1.20-oe2403sp3
        command: ["sleep", "infinity"]
      - name: qwen
        image: node:22-bookworm-slim
        command:
          - sh
          - -c
          - |
            for i in 1 2 3; do
              npm install -g @qwen-code/qwen-code@latest && break
              echo "qwen-code install attempt $i failed, retrying in 5s..." >&2
              sleep 5
            done
            if command -v qwen >/dev/null 2>&1; then
              echo "qwen installed at $(command -v qwen)"
            else
              echo "qwen-code install FAILED after 3 attempts" >&2
            fi
            sleep infinity
EOF

echo
echo "Applied PodTemplate 'bash' to namespace 'default'."
echo "In the admin dashboard's Available Jobs & Services page, click 'Start Terminal 💻' to launch it"
echo "(attaches to the 'shell' container). Once running, attach to either 'shell' or 'sidecar' from"
echo "the Running Jobs page's per-container Attach Terminal row."
echo
echo "Applied PodTemplate 'opencode' to namespace 'default'."
echo "'opencode' and 'claude' are ready immediately (pre-built images). 'qwen' installs itself on"
echo "startup — check 'kubectl logs <pod> -c qwen' if it's not ready yet. Attach a terminal to any of"
echo "the three from Running Jobs — each will be pre-filled and ready to run."
