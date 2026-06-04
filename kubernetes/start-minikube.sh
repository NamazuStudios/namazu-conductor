#!/bin/bash
#
# Starts minikube (and, by default, the LoadBalancer tunnel) for the Kubernetes integration test.
# The test does not provision a cluster — run this first, then in another terminal:
#
#     mvn verify -pl kubernetes -am
#
# Usage:
#     ./kubernetes/start-minikube.sh            # start minikube + run tunnel in the foreground
#     ./kubernetes/start-minikube.sh --no-tunnel  # start minikube only (NodePort tests; set KUBERNETES_IT_HTTP_CHECK=false)
#
# Env overrides: MINIKUBE_PROFILE (default "minikube"), MINIKUBE_DRIVER (default "docker").
#
set -euo pipefail

PROFILE="${MINIKUBE_PROFILE:-minikube}"
DRIVER="${MINIKUBE_DRIVER:-docker}"
TUNNEL=1
[ "${1:-}" = "--no-tunnel" ] && TUNNEL=0

if minikube status -p "$PROFILE" --format '{{.Host}}' 2>/dev/null | grep -q "Running"; then
  echo "minikube profile '$PROFILE' is already running."
else
  echo "Starting minikube profile '$PROFILE' (driver=$DRIVER)..."
  minikube start -p "$PROFILE" --driver="$DRIVER"
fi

if [ "$TUNNEL" -eq 0 ]; then
  echo "Cluster is up. Skipping tunnel (--no-tunnel)."
  echo "Run the non-LoadBalancer tests with:  KUBERNETES_IT_HTTP_CHECK=false mvn verify -pl kubernetes -am"
  exit 0
fi

echo
echo "Starting 'minikube tunnel' in the foreground — requires sudo (needed for LoadBalancer Services)."
echo "Leave this running; in another terminal run:  mvn verify -pl kubernetes -am"
echo
exec minikube tunnel -p "$PROFILE"