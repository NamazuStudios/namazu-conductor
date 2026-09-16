#!/bin/bash
#
# Applies a sample PodTemplate running a bash container to the current kubectl context, so the
# admin dashboard's web-based terminal (xterm.js) can be tested end-to-end against a real pod.
# Run ./kubernetes/start-minikube.sh first.
#
# The container just sleeps so the pod stays Running on its own. The `namazu.conductor/terminal-job`
# annotation marks this profile as terminal-capable: the admin dashboard's "Available Jobs & Services"
# page shows a one-click "Start Terminal 💻" button for it, which launches the job with tty/command
# defaults implied automatically (see ConductorAdminJobsResource.execute()) — no manual form needed.
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
    namazu.conductor/description: |
      A minimal **bash** container for testing the admin dashboard's web-based terminal.

      - Image: `bash:5`
      - Stays alive on its own via `sleep infinity`
      - Launching via *Start Terminal* attaches an interactive shell automatically
template:
  spec:
    containers:
      - name: shell
        image: bash:5
        command: ["bash", "-c", "sleep infinity"]
EOF

echo
echo "Applied PodTemplate 'conductor-terminal-test' to namespace 'default'."
echo "In the admin dashboard's Available Jobs & Services page, click 'Start Terminal 💻' to launch it."