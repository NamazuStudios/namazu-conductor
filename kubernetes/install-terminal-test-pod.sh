#!/bin/bash
#
# Applies a sample two-container PodTemplate to the current kubectl context, so the admin
# dashboard's web-based terminal (xterm.js) can be tested end-to-end against a real pod.
# Run ./kubernetes/start-minikube.sh first.
#
# Both containers just sleep so the pod stays Running on its own. The `namazu.conductor/terminal-job`
# annotation marks this profile as terminal-capable: the admin dashboard's "Available Jobs & Services"
# page shows a one-click "Start Terminal 💻" button for it, which launches the job with tty/command
# defaults implied automatically (see ConductorAdminJobsResource.execute()) — no manual form needed;
# this always targets the primary/first container (`shell`).
#
# Each container also declares its own `namazu.conductor/default-container-exec.<name>` annotation,
# which pre-fills that container's "Attach Terminal" input on the Running Jobs page — `shell` defaults
# to `/bin/bash -l`, `sidecar` to `/bin/sh`, demonstrating that the default is genuinely per-container
# rather than shared across the whole profile.
#
# Usage:
#     ./kubernetes/install-terminal-test-pod.sh
#
# Cleanup:
#     kubectl delete podtemplate conductor-terminal-test -n default
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

echo
echo "Applied PodTemplate 'conductor-terminal-test' to namespace 'default'."
echo "In the admin dashboard's Available Jobs & Services page, click 'Start Terminal 💻' to launch it"
echo "(attaches to the 'shell' container). Once running, attach to either 'shell' or 'sidecar' from"
echo "the Running Jobs page's per-container Attach Terminal row."
