#!/bin/bash
# Starts (creates or updates) the conductor-integration-test stack used by
# EcsOrchestrationServiceIT, so `mvn verify -pl ecs` can iterate locally without paying the
# stack create/delete cost — several minutes each way — on every single run.
#
# Usage:
#   ./start-integration-test-stack.sh
#   CFN_KEEP_STACK=true mvn verify -pl ecs -am     # (in another terminal, repeatable)
#   ./stop-integration-test-stack.sh                # when you're done for the session
#
# Leaving the stack running costs roughly $10-20/month (one always-on t3.small spot EC2 instance
# plus its public IP fee) — don't forget to stop it when you're done. See ecs/README.md.
#
# Environment variables:
#   AWS_REGION              - AWS region (default: us-east-1)
#   AWS_PROFILE             - AWS CLI profile (default: namazu-internal)
#   CFN_STACK_NAME          - stack name (default: conductor-integration-test)
#   CFN_DEPLOYER_STACK_NAME - deployer stack to resolve the ECR registry from
#                             (default: conductor-integration-test-deployer)
#   CFN_IMAGE_NAME          - image name:tag within the ECR registry
#                             (default: conductor-integration-test:latest)

set -euo pipefail

AWS_REGION=${AWS_REGION:-us-east-1}
AWS_PROFILE=${AWS_PROFILE:-namazu-internal}
CFN_STACK_NAME=${CFN_STACK_NAME:-conductor-integration-test}
CFN_DEPLOYER_STACK_NAME=${CFN_DEPLOYER_STACK_NAME:-conductor-integration-test-deployer}
CFN_IMAGE_NAME=${CFN_IMAGE_NAME:-conductor-integration-test:latest}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_FILE="${SCRIPT_DIR}/../src/test/resources/integration-test.yaml"

function cloudformation() {
  aws --region "${AWS_REGION}" --profile "${AWS_PROFILE}" cloudformation "$@"
}

ECR_REPO_URI=$(cloudformation describe-stacks --stack-name "${CFN_DEPLOYER_STACK_NAME}" \
  --query 'Stacks[0].Outputs[?OutputKey==`EcrRepositoryUri`].OutputValue' --output text)
REPOSITORY_URL="${ECR_REPO_URI%/*}"

cloudformation deploy \
  --stack-name "${CFN_STACK_NAME}" \
  --template-file "${TEMPLATE_FILE}" \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides "RepositoryUrl=${REPOSITORY_URL}" "ImageName=${CFN_IMAGE_NAME}"

echo ""
echo "Stack '${CFN_STACK_NAME}' is up. Run the integration tests against it with:"
echo "  CFN_KEEP_STACK=true mvn verify -pl ecs -am"
echo "Remember to run ./stop-integration-test-stack.sh when you're done for the session."
