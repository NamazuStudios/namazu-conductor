#!/bin/bash
# Stops (deletes) the conductor-integration-test stack started by
# ./start-integration-test-stack.sh. Run this when you're done iterating locally on the ecs
# module — the stack costs roughly $10-20/month if left running (see ecs/README.md).
#
# Usage:
#   ./stop-integration-test-stack.sh
#
# Environment variables:
#   AWS_REGION     - AWS region (default: us-east-1)
#   AWS_PROFILE    - AWS CLI profile (default: namazu-internal)
#   CFN_STACK_NAME - stack name (default: conductor-integration-test)

set -euo pipefail

AWS_REGION=${AWS_REGION:-us-east-1}
AWS_PROFILE=${AWS_PROFILE:-namazu-internal}
CFN_STACK_NAME=${CFN_STACK_NAME:-conductor-integration-test}

function cloudformation() {
  aws --region "${AWS_REGION}" --profile "${AWS_PROFILE}" cloudformation "$@"
}

cloudformation delete-stack --stack-name "${CFN_STACK_NAME}"
echo "Deleting stack '${CFN_STACK_NAME}'... waiting for completion."
cloudformation wait stack-delete-complete --stack-name "${CFN_STACK_NAME}"
echo "Stack '${CFN_STACK_NAME}' deleted."
