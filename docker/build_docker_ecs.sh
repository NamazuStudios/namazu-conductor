#!/bin/bash
# Builds the nginx_it Docker image and pushes it to the ECR repository created
# by the integration-test-deployer CloudFormation stack.
#
# Usage:
#   ./build_docker_ecs.sh
#
# Environment variables:
#   AWS_REGION         - AWS region (default: us-east-1)
#   AWS_PROFILE        - AWS CLI profile (default: namazu-internal)
#   CF_DEPLOYER_STACK  - Deployer stack name (default: conductor-integration-test-deployer)
#   TAG                - Image tag (default: latest)

set -euo pipefail

TAG=${TAG:-latest}
AWS_REGION=${AWS_REGION:-us-east-1}
AWS_PROFILE=${AWS_PROFILE:-namazu-internal}
CF_DEPLOYER_STACK=${CF_DEPLOYER_STACK:-conductor-integration-test-deployer}

IMAGE='conductor-integration-test'

# Resolve the ECR repository URI from the deployer stack outputs.
REPOSITORY_URI=$(aws --region "${AWS_REGION}" --profile "${AWS_PROFILE}" \
  cloudformation describe-stacks \
  --stack-name "${CF_DEPLOYER_STACK}" \
  --query 'Stacks[0].Outputs[?OutputKey==`EcrRepositoryUri`].OutputValue' \
  --output text)

if [ -z "${REPOSITORY_URI}" ]; then
  echo "ERROR: Could not resolve EcrRepositoryUri from stack '${CF_DEPLOYER_STACK}'" >&2
  exit 1
fi

echo "ECR repository: ${REPOSITORY_URI}"

# Use a temporary Docker config that routes ECR auth through amazon-ecr-credential-helper
# (docker-credential-ecr-login must be installed and on PATH).
DOCKER_CONFIG=$(mktemp -d)
export DOCKER_CONFIG
trap 'rm -rf "${DOCKER_CONFIG}"' EXIT

cat > "${DOCKER_CONFIG}/config.json" <<EOF
{
  "credHelpers": {
    "${REPOSITORY_URI%%/*}": "ecr-login"
  }
}
EOF

# Build, tag, and push.
docker build -t "${IMAGE}:${TAG}" nginx_it
docker tag "${IMAGE}:${TAG}" "${REPOSITORY_URI}:${TAG}"
docker push "${REPOSITORY_URI}:${TAG}"

echo ""
echo "Pushed: ${REPOSITORY_URI}:${TAG}"
echo "Set CFN_IMAGE_NAME=${IMAGE}:${TAG} when running mvn verify -pl ecs"