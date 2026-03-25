#!/bin/bash
# Deploys the integration-test-deployer stack, which creates:
#   - An ECR repository for the integration test image (conductor-integration-test)
#   - An IAM deployer user with permissions to push to ECR and deploy the
#     integration-test.yaml stack
#
# Usage:
#   ./deploy_integration_test.sh
#
# Environment variables:
#   AWS_REGION   - AWS region (default: us-east-1)
#   AWS_PROFILE  - AWS CLI profile (default: namazu-internal)
#   CF_STACK_NAME - CloudFormation stack name (default: conductor-integration-test-deployer)
#
# After deploying, retrieve the ECR URI from the stack outputs:
#   aws cloudformation describe-stacks --stack-name conductor-integration-test-deployer \
#     --query 'Stacks[0].Outputs[?OutputKey==`EcrRepositoryUri`].OutputValue' --output text
#
# Then push your custom image:
#   aws ecr get-login-password | docker login --username AWS --password-stdin <ecr-uri>
#   docker build -t <ecr-uri>:latest .
#   docker push <ecr-uri>:latest
#
# Pass the image URI to the integration-test stack via the ImageUri parameter
# (the IT test picks this up automatically when deploying the stack).

set -euo pipefail

AWS_REGION=${AWS_REGION:-us-east-1}
AWS_PROFILE=${AWS_PROFILE:-namazu-internal}
CF_STACK_NAME=${CF_STACK_NAME:-conductor-integration-test-deployer}

function cloudformation() {
  aws --region ${AWS_REGION} --profile ${AWS_PROFILE} cloudformation "$@"
}

cloudformation deploy --stack-name ${CF_STACK_NAME} --template-file integration-test-deployer.yaml --capabilities CAPABILITY_NAMED_IAM

echo ""
echo "Deployer stack deployed. ECR repository URI:"
cloudformation \
  describe-stacks \
  --stack-name ${CF_STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`EcrRepositoryUri`].OutputValue' \
  --output text
