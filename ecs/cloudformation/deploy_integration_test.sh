#!/bin/bash

AWS_REGION=${AWS_REGION:-us-east-1}
AWS_PROFILE=${AWS_PROFILE:-namazu-internal}
CF_STACK_NAME=${CF_STACK_NAME:-conductor-integration-test-deployer}

function cloudformation() {
  aws --region ${AWS_REGION} --profile ${AWS_PROFILE} cloudformation "$@"
}

cloudformation deploy --stack-name ${CF_STACK_NAME} --template-file integration-test-deployer.yaml --capabilities CAPABILITY_NAMED_IAM
