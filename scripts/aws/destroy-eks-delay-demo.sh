#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

AWS_REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="${CLUSTER_NAME:-streaming-eks-delay-demo}"
STATE_FILE="${STATE_FILE:-${AWS_REPO_ROOT}/.generated/aws/${CLUSTER_NAME}.env}"

usage() {
  cat <<'EOF'
Usage: destroy-eks-delay-demo.sh [--state-file <path>] [--help]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --state-file)
      STATE_FILE="${2:?missing value for --state-file}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      aws_fail "unknown option: $1"
      ;;
  esac
done

require_command aws
require_command eksctl

load_state_file "${STATE_FILE}"

aws_cmd() {
  aws_region_cli "$@"
}

aws_global_cmd() {
  aws_cli "$@"
}

cluster_exists() {
  aws_cmd eks describe-cluster --name "${CLUSTER_NAME}" >/dev/null 2>&1
}

if cluster_exists; then
  declare -a eksctl_args

  aws_log "Deleting AWS Load Balancer Controller IAM service account"
  eksctl_args=(
    delete
    iamserviceaccount
    --cluster
    "${CLUSTER_NAME}"
    --region
    "${AWS_REGION}"
    --namespace
    kube-system
    --name
    aws-load-balancer-controller
  )
  if [[ -n "${AWS_PROFILE:-}" ]]; then
    eksctl_args+=(--profile "${AWS_PROFILE}")
  fi
  eksctl "${eksctl_args[@]}" >/dev/null 2>&1 || true

  aws_log "Deleting EKS cluster ${CLUSTER_NAME}"
  eksctl_args=(
    delete
    cluster
    --name
    "${CLUSTER_NAME}"
    --region
    "${AWS_REGION}"
  )
  if [[ -n "${AWS_PROFILE:-}" ]]; then
    eksctl_args+=(--profile "${AWS_PROFILE}")
  fi
  eksctl "${eksctl_args[@]}"
fi

if [[ -n "${ROUTER_EIP_ALLOCATION_ID:-}" ]]; then
  association_id="$(
    aws_cmd ec2 describe-addresses \
      --allocation-ids "${ROUTER_EIP_ALLOCATION_ID}" \
      --query 'Addresses[0].AssociationId' \
      --output text 2>/dev/null || true
  )"
  if [[ -n "${association_id}" && "${association_id}" != "None" ]]; then
    aws_cmd ec2 disassociate-address --association-id "${association_id}" >/dev/null || true
  fi
  aws_cmd ec2 release-address --allocation-id "${ROUTER_EIP_ALLOCATION_ID}" >/dev/null || true
fi

if [[ -n "${ROUTER_INSTANCE_ID:-}" ]]; then
  aws_log "Terminating router instance ${ROUTER_INSTANCE_ID}"
  aws_cmd ec2 terminate-instances --instance-ids "${ROUTER_INSTANCE_ID}" >/dev/null || true
  aws_cmd ec2 wait instance-terminated --instance-ids "${ROUTER_INSTANCE_ID}" || true
fi

if [[ -n "${ROUTER_PRIVATE_ENI_ID:-}" ]]; then
  aws_cmd ec2 delete-network-interface --network-interface-id "${ROUTER_PRIVATE_ENI_ID}" >/dev/null || true
fi

if [[ -n "${PRIVATE_ROUTE_TABLE_ID:-}" ]]; then
  route_exists="$(
    aws_cmd ec2 describe-route-tables \
      --route-table-ids "${PRIVATE_ROUTE_TABLE_ID}" \
      --query "RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'].RouteTableId | [0]" \
      --output text 2>/dev/null || true
  )"
  if [[ -n "${route_exists}" && "${route_exists}" != "None" ]]; then
    aws_cmd ec2 delete-route --route-table-id "${PRIVATE_ROUTE_TABLE_ID}" --destination-cidr-block 0.0.0.0/0 >/dev/null || true
  fi
fi

for subnet_id in "${PUBLIC_SUBNET_A_ID:-}" "${PUBLIC_SUBNET_B_ID:-}" "${PRIVATE_SUBNET_A_ID:-}" "${PRIVATE_SUBNET_B_ID:-}"; do
  [[ -n "${subnet_id}" ]] || continue
  aws_cmd ec2 delete-subnet --subnet-id "${subnet_id}" >/dev/null || true
done

for route_table_id in "${PUBLIC_ROUTE_TABLE_ID:-}" "${PRIVATE_ROUTE_TABLE_ID:-}"; do
  [[ -n "${route_table_id}" ]] || continue
  aws_cmd ec2 delete-route-table --route-table-id "${route_table_id}" >/dev/null || true
done

if [[ -n "${INTERNET_GATEWAY_ID:-}" && -n "${VPC_ID:-}" ]]; then
  aws_cmd ec2 detach-internet-gateway --internet-gateway-id "${INTERNET_GATEWAY_ID}" --vpc-id "${VPC_ID}" >/dev/null || true
  aws_cmd ec2 delete-internet-gateway --internet-gateway-id "${INTERNET_GATEWAY_ID}" >/dev/null || true
fi

if [[ -n "${ROUTER_SECURITY_GROUP_ID:-}" ]]; then
  aws_cmd ec2 delete-security-group --group-id "${ROUTER_SECURITY_GROUP_ID}" >/dev/null || true
fi

if [[ -n "${VPC_ID:-}" ]]; then
  aws_cmd ec2 delete-vpc --vpc-id "${VPC_ID}" >/dev/null || true
fi

if [[ -n "${ROUTER_INSTANCE_PROFILE_NAME:-}" ]]; then
  aws_global_cmd iam remove-role-from-instance-profile --instance-profile-name "${ROUTER_INSTANCE_PROFILE_NAME}" --role-name "${ROUTER_ROLE_NAME}" >/dev/null 2>&1 || true
  aws_global_cmd iam delete-instance-profile --instance-profile-name "${ROUTER_INSTANCE_PROFILE_NAME}" >/dev/null 2>&1 || true
fi

if [[ -n "${ROUTER_ROLE_NAME:-}" ]]; then
  aws_global_cmd iam detach-role-policy --role-name "${ROUTER_ROLE_NAME}" --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore >/dev/null 2>&1 || true
  aws_global_cmd iam delete-role --role-name "${ROUTER_ROLE_NAME}" >/dev/null 2>&1 || true
fi

if [[ -n "${AWS_LBC_POLICY_ARN:-}" ]]; then
  aws_global_cmd iam delete-policy --policy-arn "${AWS_LBC_POLICY_ARN}" >/dev/null 2>&1 || true
fi

rm -f "${STATE_FILE}"
aws_log "Destroyed environment and removed state file ${STATE_FILE}"
