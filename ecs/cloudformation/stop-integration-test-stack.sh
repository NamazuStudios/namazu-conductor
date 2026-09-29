#!/bin/bash
# Stops (deletes) the conductor-integration-test stack started by
# ./start-integration-test-stack.sh. Run this when you're done iterating locally on the ecs
# module — the stack costs roughly $10-20/month if left running (see ecs/README.md).
#
# Before triggering the CloudFormation deletion, this scrubs any unmanaged security group left
# in the stack's VPC — most importantly the GuardDutyManagedSecurityGroup-* that AWS GuardDuty's
# EC2 Runtime Monitoring auto-injects whenever an EC2-launch-type task runs. CloudFormation
# doesn't own that group and can't delete it, and a VPC can't be deleted while any non-default
# security group remains in it, so without this scrub the stack's Vpc resource sits in
# DELETE_IN_PROGRESS forever and the *next* run's stack creation fails on a name collision.
# See https://github.com/NamazuStudios/namazu-conductor/issues/35. Keep in step with the
# equivalent Java logic in EcsOrchestrationServiceIT.scrubVpcDependencies.
#
# Usage:
#   ./stop-integration-test-stack.sh
#
# Environment variables:
#   AWS_REGION     - AWS region (default: us-east-1)
#   AWS_PROFILE    - AWS CLI profile (default: namazu-internal; unset/empty to rely on
#                    environment credentials, e.g. in CI)
#   CFN_STACK_NAME - stack name (default: conductor-integration-test)

set -euo pipefail

AWS_REGION=${AWS_REGION:-us-east-1}
AWS_PROFILE=${AWS_PROFILE-namazu-internal}
CFN_STACK_NAME=${CFN_STACK_NAME:-conductor-integration-test}

profile_args=()
if [ -n "${AWS_PROFILE}" ]; then
  profile_args=(--profile "${AWS_PROFILE}")
fi

function cloudformation() {
  aws --region "${AWS_REGION}" ${profile_args[@]+"${profile_args[@]}"} cloudformation "$@"
}

function ec2() {
  aws --region "${AWS_REGION}" ${profile_args[@]+"${profile_args[@]}"} ec2 "$@"
}

function delete_security_group_with_retry() {
  local group_id=$1 group_name=$2 attempt
  for attempt in 1 2 3 4 5 6; do
    if [ "${attempt}" -gt 1 ]; then sleep 10; fi
    local error
    if error=$(ec2 delete-security-group --group-id "${group_id}" 2>&1); then
      echo "Deleted security group ${group_id} (${group_name})"
      return 0
    fi
    if [[ "${error}" == *"InvalidGroup.NotFound"* ]]; then
      echo "Security group ${group_id} (${group_name}) no longer exists — treating as success"
      return 0
    fi
    echo "Attempt ${attempt} failed to delete security group ${group_id} (${group_name}): ${error}" >&2
  done
  echo "Failed to delete security group ${group_id} (${group_name}) after 6 attempts" >&2
  return 1
}

# Nothing to stop when the stack was never created (or already stopped) — lets the harness
# workflow's nightly scheduled stop run as a no-op instead of failing on a missing stack.
if ! cloudformation describe-stacks --stack-name "${CFN_STACK_NAME}" >/dev/null 2>&1; then
  echo "Stack '${CFN_STACK_NAME}' does not exist — nothing to stop."
  exit 0
fi

# The stack's VPC carries a Name tag matching the stack name (see integration-test.yaml).
VPC_IDS=$(ec2 describe-vpcs \
  --filters "Name=tag:Name,Values=${CFN_STACK_NAME}" \
  --query 'Vpcs[].VpcId' --output text || true)

for VPC_ID in ${VPC_IDS}; do
  echo "Scrubbing unmanaged security groups in VPC '${VPC_ID}'..."
  UNMANAGED_SGS=$(ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=${VPC_ID}" \
    --query "SecurityGroups[?GroupName != 'default' && !starts_with(GroupName, '${CFN_STACK_NAME}')]" \
    --output json)

  GROUP_IDS=$(echo "${UNMANAGED_SGS}" \
    | python3 -c 'import json,sys; print("\n".join(sg["GroupId"] for sg in json.load(sys.stdin)))')
  GROUP_NAMES=$(echo "${UNMANAGED_SGS}" \
    | python3 -c 'import json,sys; print("\n".join(sg["GroupName"] for sg in json.load(sys.stdin)))')

  paste <(echo "${GROUP_IDS}") <(echo "${GROUP_NAMES}") | while read -r GROUP_ID GROUP_NAME; do
    [ -n "${GROUP_ID}" ] || continue

    # An EC2 security group can't be deleted while ENIs are attached, and a just-stopped EC2
    # task's ENIs take minutes to detach as its instance terminates — wait for that first,
    # mirroring EcsOrchestrationServiceIT.waitForSecurityGroupEnisToDetach (issue #35).
    for attempt in $(seq 1 30); do
      ENI_COUNT=$(ec2 describe-network-interfaces \
        --filters "Name=group-id,Values=${GROUP_ID}" \
        --query 'length(NetworkInterfaces)' --output text)
      [ "${ENI_COUNT}" = "0" ] && break
      echo "Waiting for ${ENI_COUNT} ENI(s) to detach from security group ${GROUP_ID} (${GROUP_NAME})..."
      sleep 10
    done

    if ! delete_security_group_with_retry "${GROUP_ID}" "${GROUP_NAME}"; then
      echo "WARNING: could not remove ${GROUP_ID}; the stack's Vpc deletion may hang — see issue #35" >&2
    fi
  done
done

cloudformation delete-stack --stack-name "${CFN_STACK_NAME}"
echo "Deleting stack '${CFN_STACK_NAME}'... waiting for completion."
cloudformation wait stack-delete-complete --stack-name "${CFN_STACK_NAME}"
echo "Stack '${CFN_STACK_NAME}' deleted."
