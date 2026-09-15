#!/bin/bash
#
# Applies a sample PodTemplate running a bash container to the current kubectl context, so the
# admin dashboard's web-based terminal (xterm.js) can be tested end-to-end against a real pod.
# Run ./kubernetes/start-minikube.sh first.
#
# The container just sleeps so the pod stays Running on its own. Launch it from the admin
# dashboard with "Run as terminal job" checked (tty) to get an interactive shell over the
# terminal panel — Conductor overrides the container's command/tty at launch time, so the
# template's own default command doesn't need to be a shell.
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
template:
  spec:
    containers:
      - name: shell
        image: bash:5
        command: ["bash", "-c", "sleep infinity"]
EOF

echo
echo "Applied PodTemplate 'conductor-terminal-test' to namespace 'default'."
echo "In the admin dashboard, launch it with 'Run as terminal job' checked, then open its terminal."