#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

ENV_FILE="${ENV_FILE:-${AWS_REPO_ROOT}/.env}"
AWS_REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="${CLUSTER_NAME:-streaming-eks-delay-demo}"
NAMESPACE="${NAMESPACE:-streaming-demo}"
KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.34}"
EKS_NODE_TYPE="${EKS_NODE_TYPE:-m6i.xlarge}"
EKS_NODE_COUNT="${EKS_NODE_COUNT:-2}"
ROUTER_INSTANCE_TYPE="${ROUTER_INSTANCE_TYPE:-m6i.large}"
ROUTER_KEY_NAME="${ROUTER_KEY_NAME:-appd-tme-key}"
ROUTER_KEY_PATH="${ROUTER_KEY_PATH:-/Users/alecchamberlain/Desktop/Splunk TME/AppD TME/aws/appd-tme-key.pem}"
ROUTER_SSH_CIDR="${ROUTER_SSH_CIDR:-}"
EKS_PUBLIC_ACCESS_CIDRS="${EKS_PUBLIC_ACCESS_CIDRS:-}"
VPC_CIDR="${VPC_CIDR:-10.42.0.0/16}"
PUBLIC_SUBNET_A_CIDR="${PUBLIC_SUBNET_A_CIDR:-10.42.0.0/24}"
PUBLIC_SUBNET_B_CIDR="${PUBLIC_SUBNET_B_CIDR:-10.42.1.0/24}"
PRIVATE_SUBNET_A_CIDR="${PRIVATE_SUBNET_A_CIDR:-10.42.10.0/24}"
PRIVATE_SUBNET_B_CIDR="${PRIVATE_SUBNET_B_CIDR:-10.42.11.0/24}"
STATE_FILE="${STATE_FILE:-${AWS_REPO_ROOT}/.generated/aws/${CLUSTER_NAME}.env}"
KUBECONFIG_FILE="${KUBECONFIG_FILE:-${AWS_REPO_ROOT}/.generated/aws/${CLUSTER_NAME}.kubeconfig}"
AWS_LBC_POLICY_NAME="${AWS_LBC_POLICY_NAME:-${CLUSTER_NAME}-aws-load-balancer-controller}"
AWS_LBC_POLICY_URL="${AWS_LBC_POLICY_URL:-https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json}"
AWS_LBC_CHART_VERSION="${AWS_LBC_CHART_VERSION:-}"
INTEGRATION_MODE="${INTEGRATION_MODE:-full}"
RESUME_FROM_STATE="${RESUME_FROM_STATE:-false}"
SPLUNK_DEPLOYMENT_ENVIRONMENT="${SPLUNK_DEPLOYMENT_ENVIRONMENT-}"
STREAMING_CLUSTER_LABEL="${STREAMING_CLUSTER_LABEL-}"
STREAMING_PUBLIC_RTSP_URL="${STREAMING_PUBLIC_RTSP_URL-}"
TEMP_DIR=""
EKS_PUBLIC_ACCESS_CIDRS_CLI_OVERRIDE=""
INTEGRATION_MODE_CLI_OVERRIDE=""
KUBECONFIG_FILE_CLI_OVERRIDE=""

usage() {
  cat <<'EOF'
Usage: deploy-eks-delay-demo.sh [options]

Create a dedicated VPC, router EC2, private-node EKS cluster, internal app
load balancers, in-cluster ThousandEyes Enterprise Agent, and full demo wiring.

Options:
  --env-file <path>                 Repo-style env file to load. Default: .env
  --region <region>                 Default: us-east-1
  --cluster-name <name>             Default: streaming-eks-delay-demo
  --namespace <namespace>           Default: streaming-demo
  --kubernetes-version <version>    Default: 1.34
  --node-type <instance-type>       Default: m6i.xlarge
  --node-count <count>              Default: 2
  --router-instance-type <type>     Default: m6i.large
  --router-key-name <name>          Default: appd-tme-key
  --router-key-path <path>          Default: local appd-tme-key.pem path
  --router-ssh-cidr <cidr>          Optional SSH ingress to the router
  --eks-public-access-cidrs <list>  Comma-separated CIDRs allowed to reach the
                                    EKS public API. Default: current public
                                    IPv4 /32
  --integration-mode <full|infra-only>
                                    Default: full
  --resume-from-state               Reuse the existing generated state file and
                                    continue after the network/router phase
  --state-file <path>               Generated state file path
  --kubeconfig <path>               Generated kubeconfig path
  --help

Required environment:
  SPLUNK_REALM
  SPLUNK_ACCESS_TOKEN
  THOUSANDEYES_BEARER_TOKEN
  THOUSANDEYES_ACCOUNT_GROUP_ID
  TEAGENT_ACCOUNT_TOKEN
  TE_APP_SOURCE_AGENT_IDS
  TE_TARGET_AGENT_ID

Optional environment:
  AWS_PROFILE
  EKS_PUBLIC_ACCESS_CIDRS
  TE_UDP_TARGET_AGENT_ID
  SPLUNK_RUM_ACCESS_TOKEN
  SPLUNK_RUM_APP_NAME
  SPLUNK_DEPLOYMENT_ENVIRONMENT
  STREAMING_CLUSTER_LABEL
  STREAMING_PUBLIC_RTSP_URL
  DEMO_AUTH_PASSWORD
  DEMO_AUTH_SECRET

In infra-only mode the script skips the Splunk OTel install, ThousandEyes agent
and tests, and dashboard sync, so the Splunk and ThousandEyes variables are not
required.
EOF
}

cleanup() {
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf "${TEMP_DIR}"
  fi
}

trap cleanup EXIT

load_repo_env_file "${ENV_FILE}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      ENV_FILE="${2:?missing value for --env-file}"
      load_repo_env_file "${ENV_FILE}"
      shift 2
      ;;
    --region)
      AWS_REGION="${2:?missing value for --region}"
      shift 2
      ;;
    --cluster-name)
      CLUSTER_NAME="${2:?missing value for --cluster-name}"
      shift 2
      ;;
    --namespace)
      NAMESPACE="${2:?missing value for --namespace}"
      shift 2
      ;;
    --kubernetes-version)
      KUBERNETES_VERSION="${2:?missing value for --kubernetes-version}"
      shift 2
      ;;
    --node-type)
      EKS_NODE_TYPE="${2:?missing value for --node-type}"
      shift 2
      ;;
    --node-count)
      EKS_NODE_COUNT="${2:?missing value for --node-count}"
      shift 2
      ;;
    --router-instance-type)
      ROUTER_INSTANCE_TYPE="${2:?missing value for --router-instance-type}"
      shift 2
      ;;
    --router-key-name)
      ROUTER_KEY_NAME="${2:?missing value for --router-key-name}"
      shift 2
      ;;
    --router-key-path)
      ROUTER_KEY_PATH="${2:?missing value for --router-key-path}"
      shift 2
      ;;
    --router-ssh-cidr)
      ROUTER_SSH_CIDR="${2:?missing value for --router-ssh-cidr}"
      shift 2
      ;;
    --eks-public-access-cidrs)
      EKS_PUBLIC_ACCESS_CIDRS="${2:?missing value for --eks-public-access-cidrs}"
      EKS_PUBLIC_ACCESS_CIDRS_CLI_OVERRIDE="${EKS_PUBLIC_ACCESS_CIDRS}"
      shift 2
      ;;
    --integration-mode)
      INTEGRATION_MODE="${2:?missing value for --integration-mode}"
      INTEGRATION_MODE_CLI_OVERRIDE="${INTEGRATION_MODE}"
      shift 2
      ;;
    --resume-from-state)
      RESUME_FROM_STATE="true"
      shift
      ;;
    --state-file)
      STATE_FILE="${2:?missing value for --state-file}"
      shift 2
      ;;
    --kubeconfig)
      KUBECONFIG_FILE="${2:?missing value for --kubeconfig}"
      KUBECONFIG_FILE_CLI_OVERRIDE="${KUBECONFIG_FILE}"
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

TEMP_DIR="$(mktemp -d)"
mkdir -p "$(dirname "${STATE_FILE}")" "$(dirname "${KUBECONFIG_FILE}")"

if [[ "${RESUME_FROM_STATE}" == "true" ]]; then
  load_state_file "${STATE_FILE}"
  if [[ -n "${EKS_PUBLIC_ACCESS_CIDRS_CLI_OVERRIDE}" ]]; then
    EKS_PUBLIC_ACCESS_CIDRS="${EKS_PUBLIC_ACCESS_CIDRS_CLI_OVERRIDE}"
  fi
  if [[ -n "${INTEGRATION_MODE_CLI_OVERRIDE}" ]]; then
    INTEGRATION_MODE="${INTEGRATION_MODE_CLI_OVERRIDE}"
  fi
  if [[ -n "${KUBECONFIG_FILE_CLI_OVERRIDE}" ]]; then
    KUBECONFIG_FILE="${KUBECONFIG_FILE_CLI_OVERRIDE}"
  fi
else
  [[ ! -f "${STATE_FILE}" ]] || aws_fail "state file already exists: ${STATE_FILE}. Destroy the environment first or choose a new cluster name."
fi

require_command aws
require_command eksctl
require_command kubectl
require_command helm
require_command curl
require_command ssh-keygen
require_command python3

case "${INTEGRATION_MODE}" in
  full|infra-only) ;;
  *)
    aws_fail "integration mode must be one of: full, infra-only"
    ;;
esac

discover_public_ipv4() {
  local public_ip=""

  public_ip="$(
    curl -fsSL --retry 3 --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]'
  )"

  [[ "${public_ip}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || aws_fail "failed to detect current public IPv4 address"
  printf '%s\n' "${public_ip}"
}

ensure_eks_public_access_cidrs() {
  if [[ -n "${EKS_PUBLIC_ACCESS_CIDRS}" ]]; then
    return 0
  fi

  EKS_PUBLIC_ACCESS_CIDRS="$(discover_public_ipv4)/32"
  aws_log "Defaulting EKS public API access CIDRs to ${EKS_PUBLIC_ACCESS_CIDRS}"
}

if [[ "${INTEGRATION_MODE}" == "full" ]]; then
  require_value "SPLUNK_REALM" "${SPLUNK_REALM:-}"
  require_value "SPLUNK_ACCESS_TOKEN" "${SPLUNK_ACCESS_TOKEN:-}"
  require_value "THOUSANDEYES_BEARER_TOKEN" "${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}"
  require_value "THOUSANDEYES_ACCOUNT_GROUP_ID" "${THOUSANDEYES_ACCOUNT_GROUP_ID:-}"
  require_value "TEAGENT_ACCOUNT_TOKEN" "${TEAGENT_ACCOUNT_TOKEN:-}"
  require_value "TE_APP_SOURCE_AGENT_IDS" "${TE_APP_SOURCE_AGENT_IDS:-}"
  require_value "TE_TARGET_AGENT_ID" "${TE_TARGET_AGENT_ID:-}"
fi

ensure_eks_public_access_cidrs

if [[ -z "${SPLUNK_DEPLOYMENT_ENVIRONMENT}" ]]; then
  SPLUNK_DEPLOYMENT_ENVIRONMENT="network-streaming-app-delay-demo"
fi

if [[ -z "${STREAMING_CLUSTER_LABEL}" ]]; then
  STREAMING_CLUSTER_LABEL="${CLUSTER_NAME}"
fi

aws_cmd() {
  aws_region_cli "$@"
}

aws_global_cmd() {
  aws_cli "$@"
}

tag_resource() {
  local resource_id="$1"
  shift
  aws_cmd ec2 create-tags --resources "${resource_id}" --tags "$@"
}

account_id="$(
  aws_global_cmd sts get-caller-identity \
    --query Account \
    --output text
)"

ensure_key_pair() {
  local existing=""
  local public_key=""

  existing="$(
    aws_cmd ec2 describe-key-pairs \
      --key-names "${ROUTER_KEY_NAME}" \
      --query 'KeyPairs[0].KeyName' \
      --output text 2>/dev/null || true
  )"

  if [[ "${existing}" == "${ROUTER_KEY_NAME}" ]]; then
    aws_log "Reusing existing EC2 key pair ${ROUTER_KEY_NAME}"
    return 0
  fi

  [[ -f "${ROUTER_KEY_PATH}" ]] || aws_fail "router key path not found: ${ROUTER_KEY_PATH}"
  public_key="$(ssh-keygen -y -f "${ROUTER_KEY_PATH}")"
  aws_cmd ec2 import-key-pair --key-name "${ROUTER_KEY_NAME}" --public-key-material "${public_key}" >/dev/null
  aws_log "Imported EC2 key pair ${ROUTER_KEY_NAME}"
}

discover_azs() {
  SELECTED_AZS=()

  while IFS= read -r az; do
    [[ -n "${az}" ]] || continue
    SELECTED_AZS+=("${az}")
  done < <(
    aws_cmd ec2 describe-availability-zones \
      --filters Name=state,Values=available Name=zone-type,Values=availability-zone \
      --query 'AvailabilityZones[0:2].ZoneName' \
      --output text | tr '\t' '\n'
  )

  (( ${#SELECTED_AZS[@]} >= 2 )) || aws_fail "need at least two availability zones in ${AWS_REGION}"
  AZ_A="${SELECTED_AZS[0]}"
  AZ_B="${SELECTED_AZS[1]}"
}

write_router_user_data() {
  local output_path="$1"

  cat > "${output_path}" <<'EOF'
#!/bin/bash
set -euxo pipefail

dnf install -y haproxy iptables iproute-tc

cat >/usr/local/bin/streaming-router-bootstrap.sh <<'SCRIPT'
#!/bin/bash
set -euo pipefail

public_dev=""
private_dev=""

for _ in $(seq 1 60); do
  public_dev="$(ip route show default | awk '/default/ {print $5; exit}')"
  private_dev="$(ip -o link show | awk -F': ' '{print $2}' | grep -v '^lo$' | grep -vx "${public_dev}" | head -n1 || true)"
  if [[ -n "${public_dev}" && -n "${private_dev}" ]]; then
    break
  fi
  sleep 5
done

[[ -n "${public_dev}" ]] || exit 1
[[ -n "${private_dev}" ]] || exit 1

printf 'net.ipv4.ip_forward = 1\n' >/etc/sysctl.d/99-streaming-router.conf
sysctl -p /etc/sysctl.d/99-streaming-router.conf >/dev/null

iptables -t nat -C POSTROUTING -o "${public_dev}" -j MASQUERADE 2>/dev/null || \
  iptables -t nat -A POSTROUTING -o "${public_dev}" -j MASQUERADE
iptables -C FORWARD -i "${private_dev}" -o "${public_dev}" -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -i "${private_dev}" -o "${public_dev}" -j ACCEPT
iptables -C FORWARD -i "${public_dev}" -o "${private_dev}" -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -i "${public_dev}" -o "${private_dev}" -m state --state ESTABLISHED,RELATED -j ACCEPT
SCRIPT
chmod +x /usr/local/bin/streaming-router-bootstrap.sh

cat >/etc/systemd/system/streaming-router-bootstrap.service <<'UNIT'
[Unit]
Description=Configure NAT on the streaming demo router
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/streaming-router-bootstrap.sh
RemainAfterExit=yes
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

cat >/etc/haproxy/haproxy.cfg <<'CFG'
global
  daemon
  log /dev/log local0

defaults
  log global
  mode http
  timeout connect 5s
  timeout client 60s
  timeout server 60s

frontend http_in
  bind :80
  default_backend frontend_unconfigured

backend frontend_unconfigured
  http-request return status 503 content-type text/plain lf-string "router backend not configured\n"

frontend rtsp_in
  mode tcp
  bind :8554
  default_backend rtsp_unconfigured

backend rtsp_unconfigured
  mode tcp
  tcp-request content reject
CFG

systemctl daemon-reload
systemctl enable haproxy
systemctl enable streaming-router-bootstrap.service
systemctl restart haproxy
systemctl start streaming-router-bootstrap.service || true
EOF
}

create_network() {
  local router_user_data_file="$1"
  local ami_id=""
  local router_public_eni_id=""
  local run_instances_output=""
  local run_instances_status=0

  VPC_ID="$(
    aws_cmd ec2 create-vpc \
      --cidr-block "${VPC_CIDR}" \
      --query 'Vpc.VpcId' \
      --output text
  )"
  tag_resource "${VPC_ID}" "Key=Name,Value=${CLUSTER_NAME}-vpc" "Key=Project,Value=${CLUSTER_NAME}"
  aws_cmd ec2 modify-vpc-attribute --vpc-id "${VPC_ID}" --enable-dns-hostnames '{"Value":true}'
  aws_cmd ec2 modify-vpc-attribute --vpc-id "${VPC_ID}" --enable-dns-support '{"Value":true}'

  INTERNET_GATEWAY_ID="$(
    aws_cmd ec2 create-internet-gateway \
      --query 'InternetGateway.InternetGatewayId' \
      --output text
  )"
  tag_resource "${INTERNET_GATEWAY_ID}" "Key=Name,Value=${CLUSTER_NAME}-igw" "Key=Project,Value=${CLUSTER_NAME}"
  aws_cmd ec2 attach-internet-gateway --vpc-id "${VPC_ID}" --internet-gateway-id "${INTERNET_GATEWAY_ID}"

  PUBLIC_ROUTE_TABLE_ID="$(
    aws_cmd ec2 create-route-table \
      --vpc-id "${VPC_ID}" \
      --query 'RouteTable.RouteTableId' \
      --output text
  )"
  tag_resource "${PUBLIC_ROUTE_TABLE_ID}" "Key=Name,Value=${CLUSTER_NAME}-public-rt" "Key=Project,Value=${CLUSTER_NAME}"
  aws_cmd ec2 create-route --route-table-id "${PUBLIC_ROUTE_TABLE_ID}" --destination-cidr-block 0.0.0.0/0 --gateway-id "${INTERNET_GATEWAY_ID}" >/dev/null

  PRIVATE_ROUTE_TABLE_ID="$(
    aws_cmd ec2 create-route-table \
      --vpc-id "${VPC_ID}" \
      --query 'RouteTable.RouteTableId' \
      --output text
  )"
  tag_resource "${PRIVATE_ROUTE_TABLE_ID}" "Key=Name,Value=${CLUSTER_NAME}-private-rt" "Key=Project,Value=${CLUSTER_NAME}"

  PUBLIC_SUBNET_A_ID="$(
    aws_cmd ec2 create-subnet \
      --vpc-id "${VPC_ID}" \
      --availability-zone "${AZ_A}" \
      --cidr-block "${PUBLIC_SUBNET_A_CIDR}" \
      --query 'Subnet.SubnetId' \
      --output text
  )"
  PUBLIC_SUBNET_B_ID="$(
    aws_cmd ec2 create-subnet \
      --vpc-id "${VPC_ID}" \
      --availability-zone "${AZ_B}" \
      --cidr-block "${PUBLIC_SUBNET_B_CIDR}" \
      --query 'Subnet.SubnetId' \
      --output text
  )"
  PRIVATE_SUBNET_A_ID="$(
    aws_cmd ec2 create-subnet \
      --vpc-id "${VPC_ID}" \
      --availability-zone "${AZ_A}" \
      --cidr-block "${PRIVATE_SUBNET_A_CIDR}" \
      --query 'Subnet.SubnetId' \
      --output text
  )"
  PRIVATE_SUBNET_B_ID="$(
    aws_cmd ec2 create-subnet \
      --vpc-id "${VPC_ID}" \
      --availability-zone "${AZ_B}" \
      --cidr-block "${PRIVATE_SUBNET_B_CIDR}" \
      --query 'Subnet.SubnetId' \
      --output text
  )"

  tag_resource "${PUBLIC_SUBNET_A_ID}" \
    "Key=Name,Value=${CLUSTER_NAME}-public-a" \
    "Key=Project,Value=${CLUSTER_NAME}" \
    "Key=kubernetes.io/cluster/${CLUSTER_NAME},Value=shared" \
    "Key=kubernetes.io/role/elb,Value=1"
  tag_resource "${PUBLIC_SUBNET_B_ID}" \
    "Key=Name,Value=${CLUSTER_NAME}-public-b" \
    "Key=Project,Value=${CLUSTER_NAME}" \
    "Key=kubernetes.io/cluster/${CLUSTER_NAME},Value=shared" \
    "Key=kubernetes.io/role/elb,Value=1"
  tag_resource "${PRIVATE_SUBNET_A_ID}" \
    "Key=Name,Value=${CLUSTER_NAME}-private-a" \
    "Key=Project,Value=${CLUSTER_NAME}" \
    "Key=kubernetes.io/cluster/${CLUSTER_NAME},Value=shared" \
    "Key=kubernetes.io/role/internal-elb,Value=1"
  tag_resource "${PRIVATE_SUBNET_B_ID}" \
    "Key=Name,Value=${CLUSTER_NAME}-private-b" \
    "Key=Project,Value=${CLUSTER_NAME}" \
    "Key=kubernetes.io/cluster/${CLUSTER_NAME},Value=shared" \
    "Key=kubernetes.io/role/internal-elb,Value=1"

  aws_cmd ec2 modify-subnet-attribute --subnet-id "${PUBLIC_SUBNET_A_ID}" --map-public-ip-on-launch
  aws_cmd ec2 modify-subnet-attribute --subnet-id "${PUBLIC_SUBNET_B_ID}" --map-public-ip-on-launch

  aws_cmd ec2 associate-route-table --subnet-id "${PUBLIC_SUBNET_A_ID}" --route-table-id "${PUBLIC_ROUTE_TABLE_ID}" >/dev/null
  aws_cmd ec2 associate-route-table --subnet-id "${PUBLIC_SUBNET_B_ID}" --route-table-id "${PUBLIC_ROUTE_TABLE_ID}" >/dev/null
  aws_cmd ec2 associate-route-table --subnet-id "${PRIVATE_SUBNET_A_ID}" --route-table-id "${PRIVATE_ROUTE_TABLE_ID}" >/dev/null
  aws_cmd ec2 associate-route-table --subnet-id "${PRIVATE_SUBNET_B_ID}" --route-table-id "${PRIVATE_ROUTE_TABLE_ID}" >/dev/null

  ROUTER_SECURITY_GROUP_ID="$(
    aws_cmd ec2 create-security-group \
      --group-name "${CLUSTER_NAME}-router-sg" \
      --description "Router security group for ${CLUSTER_NAME}" \
      --vpc-id "${VPC_ID}" \
      --query 'GroupId' \
      --output text
  )"
  tag_resource "${ROUTER_SECURITY_GROUP_ID}" "Key=Name,Value=${CLUSTER_NAME}-router-sg" "Key=Project,Value=${CLUSTER_NAME}"
  aws_cmd ec2 authorize-security-group-ingress --group-id "${ROUTER_SECURITY_GROUP_ID}" --protocol tcp --port 80 --cidr 0.0.0.0/0 >/dev/null
  aws_cmd ec2 authorize-security-group-ingress --group-id "${ROUTER_SECURITY_GROUP_ID}" --protocol tcp --port 8554 --cidr 0.0.0.0/0 >/dev/null
  aws_cmd ec2 authorize-security-group-ingress --group-id "${ROUTER_SECURITY_GROUP_ID}" --ip-permissions "IpProtocol=-1,IpRanges=[{CidrIp=${VPC_CIDR}}]" >/dev/null
  if [[ -n "${ROUTER_SSH_CIDR}" ]]; then
    aws_cmd ec2 authorize-security-group-ingress --group-id "${ROUTER_SECURITY_GROUP_ID}" --protocol tcp --port 22 --cidr "${ROUTER_SSH_CIDR}" >/dev/null
  fi

  ROUTER_ROLE_NAME="${CLUSTER_NAME}-router-role"
  ROUTER_INSTANCE_PROFILE_NAME="${CLUSTER_NAME}-router-profile"

  cat > "${TEMP_DIR}/router-assume-role.json" <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

  if ! aws_global_cmd iam get-role --role-name "${ROUTER_ROLE_NAME}" >/dev/null 2>&1; then
    aws_global_cmd iam create-role --role-name "${ROUTER_ROLE_NAME}" --assume-role-policy-document "file://${TEMP_DIR}/router-assume-role.json" >/dev/null
  fi
  aws_global_cmd iam attach-role-policy --role-name "${ROUTER_ROLE_NAME}" --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore >/dev/null

  if ! aws_global_cmd iam get-instance-profile --instance-profile-name "${ROUTER_INSTANCE_PROFILE_NAME}" >/dev/null 2>&1; then
    aws_global_cmd iam create-instance-profile --instance-profile-name "${ROUTER_INSTANCE_PROFILE_NAME}" >/dev/null
    aws_global_cmd iam add-role-to-instance-profile --instance-profile-name "${ROUTER_INSTANCE_PROFILE_NAME}" --role-name "${ROUTER_ROLE_NAME}" >/dev/null
  fi

  for _ in $(seq 1 30); do
    if aws_global_cmd iam get-instance-profile \
      --instance-profile-name "${ROUTER_INSTANCE_PROFILE_NAME}" \
      --query "InstanceProfile.Roles[?RoleName=='${ROUTER_ROLE_NAME}'].RoleName | [0]" \
      --output text 2>/dev/null | grep -qx "${ROUTER_ROLE_NAME}"; then
      break
    fi
    sleep 5
  done

  ami_id="$(
    aws_cmd ssm get-parameter \
      --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64 \
      --query 'Parameter.Value' \
      --output text
  )"

  for _ in $(seq 1 12); do
    set +e
    run_instances_output="$(
      aws_cmd ec2 run-instances \
        --image-id "${ami_id}" \
        --instance-type "${ROUTER_INSTANCE_TYPE}" \
        --subnet-id "${PUBLIC_SUBNET_A_ID}" \
        --security-group-ids "${ROUTER_SECURITY_GROUP_ID}" \
        --key-name "${ROUTER_KEY_NAME}" \
        --associate-public-ip-address \
        --iam-instance-profile "Name=${ROUTER_INSTANCE_PROFILE_NAME}" \
        --user-data "file://${router_user_data_file}" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${CLUSTER_NAME}-router},{Key=Project,Value=${CLUSTER_NAME}}]" \
        --query 'Instances[0].InstanceId' \
        --output text 2>&1
    )"
    run_instances_status=$?
    set -e

    if [[ ${run_instances_status} -eq 0 ]]; then
      ROUTER_INSTANCE_ID="${run_instances_output}"
      break
    fi

    if [[ "${run_instances_output}" == *"iamInstanceProfile.name is invalid"* ]]; then
      aws_warn "router instance profile is not visible to EC2 yet; retrying in 10 seconds"
      sleep 10
      continue
    fi

    aws_fail "failed to launch router instance: ${run_instances_output}"
  done

  [[ -n "${ROUTER_INSTANCE_ID:-}" ]] || aws_fail "timed out waiting for IAM instance profile ${ROUTER_INSTANCE_PROFILE_NAME} to become usable by EC2"
  aws_cmd ec2 wait instance-running --instance-ids "${ROUTER_INSTANCE_ID}"
  aws_cmd ec2 modify-instance-attribute --instance-id "${ROUTER_INSTANCE_ID}" --source-dest-check '{"Value":false}'

  ROUTER_PRIVATE_ENI_ID="$(
    aws_cmd ec2 create-network-interface \
      --subnet-id "${PRIVATE_SUBNET_A_ID}" \
      --groups "${ROUTER_SECURITY_GROUP_ID}" \
      --query 'NetworkInterface.NetworkInterfaceId' \
      --output text
  )"
  tag_resource "${ROUTER_PRIVATE_ENI_ID}" "Key=Name,Value=${CLUSTER_NAME}-router-private-eni" "Key=Project,Value=${CLUSTER_NAME}"
  ROUTER_PRIVATE_ENI_ATTACHMENT_ID="$(
    aws_cmd ec2 attach-network-interface \
      --network-interface-id "${ROUTER_PRIVATE_ENI_ID}" \
      --instance-id "${ROUTER_INSTANCE_ID}" \
      --device-index 1 \
      --query 'AttachmentId' \
      --output text
  )"
  aws_cmd ec2 modify-network-interface-attribute --network-interface-id "${ROUTER_PRIVATE_ENI_ID}" --attachment "AttachmentId=${ROUTER_PRIVATE_ENI_ATTACHMENT_ID},DeleteOnTermination=true"
  aws_cmd ec2 create-route --route-table-id "${PRIVATE_ROUTE_TABLE_ID}" --destination-cidr-block 0.0.0.0/0 --network-interface-id "${ROUTER_PRIVATE_ENI_ID}" >/dev/null

  ROUTER_EIP_ALLOCATION_ID="$(
    aws_cmd ec2 allocate-address \
      --domain vpc \
      --query 'AllocationId' \
      --output text
  )"
  router_public_eni_id="$(
    aws_cmd ec2 describe-instances \
      --instance-ids "${ROUTER_INSTANCE_ID}" \
      --query 'Reservations[0].Instances[0].NetworkInterfaces[?Attachment.DeviceIndex==`0`].NetworkInterfaceId | [0]' \
      --output text
  )"
  require_value "router public ENI" "${router_public_eni_id}"
  aws_cmd ec2 associate-address --network-interface-id "${router_public_eni_id}" --allocation-id "${ROUTER_EIP_ALLOCATION_ID}" >/dev/null
  ROUTER_PUBLIC_IP="$(
    aws_cmd ec2 describe-addresses \
      --allocation-ids "${ROUTER_EIP_ALLOCATION_ID}" \
      --query 'Addresses[0].PublicIp' \
      --output text
  )"

  ssm_wait_for_instance "${ROUTER_INSTANCE_ID}" 900
  run_ssm_script "${ROUTER_INSTANCE_ID}" $'sudo /usr/local/bin/streaming-router-bootstrap.sh\nsudo systemctl restart haproxy' >/dev/null
}

create_cluster() {
  local -a eksctl_args

  eksctl_args=(
    create
    cluster
    --name
    "${CLUSTER_NAME}"
    --region
    "${AWS_REGION}"
    --version
    "${KUBERNETES_VERSION}"
    --vpc-private-subnets
    "${PRIVATE_SUBNET_A_ID},${PRIVATE_SUBNET_B_ID}"
    --vpc-public-subnets
    "${PUBLIC_SUBNET_A_ID},${PUBLIC_SUBNET_B_ID}"
    --vpc-nat-mode
    Disable
    --with-oidc
    --managed
    --nodegroup-name
    primary
    --node-type
    "${EKS_NODE_TYPE}"
    --nodes
    "${EKS_NODE_COUNT}"
    --nodes-min
    "${EKS_NODE_COUNT}"
    --nodes-max
    "${EKS_NODE_COUNT}"
    --node-private-networking
    --enable-ssm
    --kubeconfig
    "${KUBECONFIG_FILE}"
    --set-kubeconfig-context=false
  )

  if [[ -n "${AWS_PROFILE:-}" ]]; then
    eksctl_args+=(--profile "${AWS_PROFILE}")
  fi

  eksctl "${eksctl_args[@]}"
}

cluster_status() {
  aws_cmd eks describe-cluster \
    --name "${CLUSTER_NAME}" \
    --query 'cluster.status' \
    --output text 2>/dev/null || true
}

list_nodegroups() {
  aws_cmd eks list-nodegroups \
    --cluster-name "${CLUSTER_NAME}" \
    --query 'nodegroups' \
    --output text 2>/dev/null || true
}

primary_nodegroup_exists() {
  aws_cmd eks describe-nodegroup \
    --cluster-name "${CLUSTER_NAME}" \
    --nodegroup-name primary >/dev/null 2>&1
}

nodegroup_exists() {
  local nodegroups=""

  nodegroups="$(list_nodegroups)"
  [[ -n "${nodegroups}" && "${nodegroups}" != "None" ]]
}

nodegroup_status() {
  local nodegroup_name="$1"

  aws_cmd eks describe-nodegroup \
    --cluster-name "${CLUSTER_NAME}" \
    --nodegroup-name "${nodegroup_name}" \
    --query 'nodegroup.status' \
    --output text 2>/dev/null || true
}

wait_for_nodegroup_active() {
  local nodegroup_name="$1"

  aws_cmd eks wait nodegroup-active \
    --cluster-name "${CLUSTER_NAME}" \
    --nodegroup-name "${nodegroup_name}"
}

list_active_nodegroups() {
  local nodegroups=""
  local nodegroup_name=""
  local status=""

  nodegroups="$(list_nodegroups)"
  [[ -n "${nodegroups}" && "${nodegroups}" != "None" ]] || return 0

  for nodegroup_name in ${nodegroups}; do
    status="$(nodegroup_status "${nodegroup_name}")"
    if [[ "${status}" == "ACTIVE" ]]; then
      printf '%s\n' "${nodegroup_name}"
    fi
  done
}

summarize_nodegroups() {
  local nodegroups=""
  local nodegroup_name=""
  local status=""
  local summary=""

  nodegroups="$(list_nodegroups)"
  [[ -n "${nodegroups}" && "${nodegroups}" != "None" ]] || return 0

  for nodegroup_name in ${nodegroups}; do
    status="$(nodegroup_status "${nodegroup_name}")"
    if [[ -n "${summary}" ]]; then
      summary+=","
    fi
    summary+="${nodegroup_name}:${status:-unknown}"
  done

  printf '%s\n' "${summary}"
}

ensure_nodegroup_usable() {
  local nodegroup_name="$1"
  local status=""

  status="$(nodegroup_status "${nodegroup_name}")"
  case "${status}" in
    ACTIVE)
      return 0
      ;;
    CREATING|UPDATING)
      aws_log "Waiting for managed nodegroup ${nodegroup_name} to become ACTIVE (current status: ${status})"
      wait_for_nodegroup_active "${nodegroup_name}"
      return 0
      ;;
    *)
      aws_fail "Managed nodegroup ${nodegroup_name} exists but is not usable (status: ${status:-unknown})"
      ;;
  esac
}

create_nodegroup() {
  local -a eksctl_args

  eksctl_args=(
    create
    nodegroup
    --cluster
    "${CLUSTER_NAME}"
    --region
    "${AWS_REGION}"
    --managed
    --name
    primary
    --node-type
    "${EKS_NODE_TYPE}"
    --nodes
    "${EKS_NODE_COUNT}"
    --nodes-min
    "${EKS_NODE_COUNT}"
    --nodes-max
    "${EKS_NODE_COUNT}"
    --node-private-networking
    --enable-ssm
  )

  if [[ -n "${AWS_PROFILE:-}" ]]; then
    eksctl_args+=(--profile "${AWS_PROFILE}")
  fi

  eksctl "${eksctl_args[@]}"
}

enforce_cluster_api_access_policy() {
  local current_private_access=""
  local current_public_access=""
  local current_private_access_lc=""
  local current_public_access_lc=""
  local current_public_access_cidrs=""
  local update_id=""

  current_public_access="$(
    aws_cmd eks describe-cluster \
      --name "${CLUSTER_NAME}" \
      --query 'cluster.resourcesVpcConfig.endpointPublicAccess' \
      --output text
  )"
  current_private_access="$(
    aws_cmd eks describe-cluster \
      --name "${CLUSTER_NAME}" \
      --query 'cluster.resourcesVpcConfig.endpointPrivateAccess' \
      --output text
  )"
  current_public_access_cidrs="$(
    aws_cmd eks describe-cluster \
      --name "${CLUSTER_NAME}" \
      --query 'cluster.resourcesVpcConfig.publicAccessCidrs' \
      --output text | tr '\t' ',' | tr -d '[:space:]'
  )"
  current_public_access_lc="$(printf '%s' "${current_public_access}" | tr '[:upper:]' '[:lower:]')"
  current_private_access_lc="$(printf '%s' "${current_private_access}" | tr '[:upper:]' '[:lower:]')"

  if [[ "${current_public_access_lc}" == "true" && "${current_private_access_lc}" == "true" && "${current_public_access_cidrs}" == "${EKS_PUBLIC_ACCESS_CIDRS}" ]]; then
    aws_log "EKS API access already restricted to ${EKS_PUBLIC_ACCESS_CIDRS} with private access enabled"
    return 0
  fi

  aws_log "Updating EKS API access to private+public with public CIDRs ${EKS_PUBLIC_ACCESS_CIDRS}"
  update_id="$(
    aws_cmd eks update-cluster-config \
      --name "${CLUSTER_NAME}" \
      --resources-vpc-config "endpointPublicAccess=true,endpointPrivateAccess=true,publicAccessCidrs=${EKS_PUBLIC_ACCESS_CIDRS}" \
      --query 'update.id' \
      --output text
  )"
  require_value "EKS update id" "${update_id}"
  aws_cmd eks wait cluster-active --name "${CLUSTER_NAME}"
}

kubectl_cmd() {
  KUBECONFIG="${KUBECONFIG_FILE}" kubectl "$@"
}

helm_cmd() {
  KUBECONFIG="${KUBECONFIG_FILE}" helm "$@"
}

ensure_aws_load_balancer_controller() {
  local policy_arn=""
  local -a helm_args
  local -a eksctl_args

  policy_arn="$(
    aws_global_cmd iam list-policies \
      --scope Local \
      --query "Policies[?PolicyName=='${AWS_LBC_POLICY_NAME}'].Arn | [0]" \
      --output text
  )"

  if [[ -z "${policy_arn}" || "${policy_arn}" == "None" ]]; then
    curl -fsSL "${AWS_LBC_POLICY_URL}" -o "${TEMP_DIR}/aws-load-balancer-controller-iam-policy.json"
    policy_arn="$(
      aws_global_cmd iam create-policy \
        --policy-name "${AWS_LBC_POLICY_NAME}" \
        --policy-document "file://${TEMP_DIR}/aws-load-balancer-controller-iam-policy.json" \
        --query 'Policy.Arn' \
        --output text
    )"
  fi
  AWS_LBC_POLICY_ARN="${policy_arn}"

  eksctl_args=(
    create
    iamserviceaccount
    --cluster
    "${CLUSTER_NAME}"
    --region
    "${AWS_REGION}"
    --namespace
    kube-system
    --name
    aws-load-balancer-controller
    --attach-policy-arn
    "${AWS_LBC_POLICY_ARN}"
    --override-existing-serviceaccounts
    --approve
  )

  if [[ -n "${AWS_PROFILE:-}" ]]; then
    eksctl_args+=(--profile "${AWS_PROFILE}")
  fi

  eksctl "${eksctl_args[@]}"

  helm_cmd repo add eks https://aws.github.io/eks-charts --force-update >/dev/null
  helm_cmd repo update eks >/dev/null

  helm_args=(
    upgrade
    --install
    aws-load-balancer-controller
    eks/aws-load-balancer-controller
    --namespace
    kube-system
    --set
    "clusterName=${CLUSTER_NAME}"
    --set
    "serviceAccount.create=false"
    --set
    "serviceAccount.name=aws-load-balancer-controller"
    --set
    "region=${AWS_REGION}"
    --set
    "vpcId=${VPC_ID}"
    --wait
  )

  if [[ -n "${AWS_LBC_CHART_VERSION}" ]]; then
    helm_args+=(--version "${AWS_LBC_CHART_VERSION}")
  fi

  helm_cmd "${helm_args[@]}" >/dev/null
  kubectl_cmd -n kube-system rollout status deployment/aws-load-balancer-controller --timeout=300s >/dev/null
}

wait_for_service_hostname() {
  local service_name="$1"
  local elapsed=0
  local hostname=""

  while (( elapsed < 600 )); do
    hostname="$(kubectl_cmd -n "${NAMESPACE}" get service "${service_name}" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
    if [[ -n "${hostname}" ]]; then
      printf '%s\n' "${hostname}"
      return 0
    fi

    sleep 10
    elapsed=$((elapsed + 10))
  done

  aws_fail "timed out waiting for load balancer hostname for service ${service_name}"
}

wait_for_router_backend_tcp() {
  local backend_name="$1"
  local host="$2"
  local port="$3"
  local remote_script=""

  remote_script="$(cat <<EOF
set -euo pipefail
elapsed=0
while (( elapsed < 600 )); do
  if bash -lc 'exec 3<>/dev/tcp/${host}/${port}; exec 3>&-; exec 3<&-'; then
    exit 0
  fi
  sleep 10
  elapsed=\$((elapsed + 10))
done
echo "timed out waiting for ${backend_name} backend ${host}:${port} to accept TCP connections" >&2
exit 1
EOF
)"

  run_ssm_script "${ROUTER_INSTANCE_ID}" "${remote_script}" 660 >/dev/null
}

wait_for_router_backends() {
  local frontend_host="$1"
  local rtsp_host="$2"

  aws_log "Waiting for the router to reach the internal frontend and RTSP load balancers"
  wait_for_router_backend_tcp frontend "${frontend_host}" 80
  wait_for_router_backend_tcp rtsp "${rtsp_host}" 8554
}

configure_router_backends() {
  local frontend_host="$1"
  local rtsp_host="$2"
  local remote_script=""

  remote_script="$(cat <<EOF
set -euo pipefail
cat >/etc/haproxy/haproxy.cfg <<'CFG'
global
  daemon
  log /dev/log local0

defaults
  log global
  mode http
  timeout connect 5s
  timeout client 60s
  timeout server 60s

resolvers vpcdns
  nameserver amazon 169.254.169.253:53
  resolve_retries 3
  timeout retry 1s
  hold valid 10s

frontend http_in
  bind :80
  default_backend frontend_backend

backend frontend_backend
  http-request set-header X-Forwarded-Proto http
  server frontend ${frontend_host}:80 check resolvers vpcdns init-addr none

frontend rtsp_in
  mode tcp
  bind :8554
  default_backend rtsp_backend

backend rtsp_backend
  mode tcp
  server rtsp ${rtsp_host}:8554 check resolvers vpcdns init-addr none
CFG
systemctl restart haproxy
EOF
)"

  run_ssm_script "${ROUTER_INSTANCE_ID}" "${remote_script}" >/dev/null
}

deploy_streaming_stack() {
  local splunk_otel_mode="skip"
  local public_rtsp_url=""

  if [[ "${INTEGRATION_MODE}" == "full" ]]; then
    splunk_otel_mode="install-if-missing"
  fi

  public_rtsp_url="${STREAMING_PUBLIC_RTSP_URL:-rtsp://${ROUTER_PUBLIC_IP}:8554/live}"
  STREAMING_PUBLIC_RTSP_URL="${public_rtsp_url}"

  KUBECONFIG="${KUBECONFIG_FILE}" ENV_FILE="${ENV_FILE}" \
    SPLUNK_DEPLOYMENT_ENVIRONMENT="${SPLUNK_DEPLOYMENT_ENVIRONMENT}" \
    STREAMING_CLUSTER_LABEL="${STREAMING_CLUSTER_LABEL}" \
    STREAMING_PUBLIC_RTSP_URL="${STREAMING_PUBLIC_RTSP_URL}" \
    bash "${AWS_REPO_ROOT}/skills/deploy-streaming-app/scripts/deploy-demo.sh" \
      --platform kubernetes \
      --namespace "${NAMESPACE}" \
      --splunk-otel-mode "${splunk_otel_mode}" \
      --splunk-otel-cluster-name "${CLUSTER_NAME}" \
      --frontend-service-type LoadBalancer \
      --frontend-load-balancer-scheme internal \
      --frontend-aws-load-balancer-type nlb \
      --rtsp-service-type LoadBalancer \
      --rtsp-load-balancer-scheme internal \
      --rtsp-aws-load-balancer-type nlb \
      --public-rtsp-url "${public_rtsp_url}"

  FRONTEND_INTERNAL_HOST="$(wait_for_service_hostname streaming-frontend)"
  RTSP_INTERNAL_HOST="$(wait_for_service_hostname media-service-demo-rtsp)"
  wait_for_router_backends "${FRONTEND_INTERNAL_HOST}" "${RTSP_INTERNAL_HOST}"
  configure_router_backends "${FRONTEND_INTERNAL_HOST}" "${RTSP_INTERNAL_HOST}"
}

deploy_loadgen() {
  KUBECONFIG="${KUBECONFIG_FILE}" ENV_FILE="${ENV_FILE}" NAMESPACE="${NAMESPACE}" LOADGEN_PROFILE=booth LOADGEN_K8S_ACTION=apply \
    zsh "${AWS_REPO_ROOT}/scripts/loadgen/deploy-k8s-broadcast-loadgen.sh"
  KUBECONFIG="${KUBECONFIG_FILE}" ENV_FILE="${ENV_FILE}" NAMESPACE="${NAMESPACE}" LOADGEN_OPERATOR_PROFILE=booth LOADGEN_OPERATOR_K8S_ACTION=apply \
    zsh "${AWS_REPO_ROOT}/scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh"
}

deploy_thousandeyes_agent() {
  local agent_env=""

  agent_env="$(
    KUBECONFIG="${KUBECONFIG_FILE}" ENV_FILE="${ENV_FILE}" \
      THOUSANDEYES_BEARER_TOKEN="${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}" \
      THOUSANDEYES_ACCOUNT_GROUP_ID="${THOUSANDEYES_ACCOUNT_GROUP_ID}" \
      TEAGENT_ACCOUNT_TOKEN="${TEAGENT_ACCOUNT_TOKEN}" \
      bash "${AWS_REPO_ROOT}/scripts/thousandeyes/deploy-enterprise-agent.sh" \
        --apply \
        --wait \
        --resolve-agent-id
  )"

  TE_MEDIA_SOURCE_AGENT_IDS="$(printf '%s\n' "${agent_env}" | awk -F= '/^TE_MEDIA_SOURCE_AGENT_IDS=/{print $2; exit}')"
  require_value "TE_MEDIA_SOURCE_AGENT_IDS" "${TE_MEDIA_SOURCE_AGENT_IDS}"
}

delay_demo_te_test_name() {
  local base_name="$1"
  printf '%s (%s)' "${base_name}" "${CLUSTER_NAME}"
}

delay_demo_test_name_override() {
  local override_var="$1"
  local base_name="$2"
  local override_value="${!override_var:-}"

  if [[ -n "${override_value}" ]]; then
    printf '%s' "${override_value}"
    return 0
  fi

  delay_demo_te_test_name "${base_name}"
}

delay_demo_rtsp_tcp_test_name() {
  delay_demo_test_name_override "DELAY_TE_RTSP_TCP_TEST_NAME" "RTSP-TCP-8554"
}

delay_demo_udp_media_test_name() {
  delay_demo_test_name_override "DELAY_TE_UDP_MEDIA_TEST_NAME" "UDP-Media-Path"
}

delay_demo_rtp_stream_test_name() {
  delay_demo_test_name_override "DELAY_TE_RTP_STREAM_TEST_NAME" "RTP-Stream-Proxy"
}

delay_demo_trace_map_test_name() {
  delay_demo_test_name_override "DELAY_TE_TRACE_MAP_TEST_NAME" "aleccham-broadcast-trace-map"
}

delay_demo_broadcast_test_name() {
  delay_demo_test_name_override "DELAY_TE_BROADCAST_TEST_NAME" "aleccham-broadcast-playback"
}

capture_delay_demo_test_ids() {
  local create_output="$1"
  local assignments=""

  assignments="$(
    DELAY_TE_RTSP_TCP_TEST_NAME="$(delay_demo_rtsp_tcp_test_name)" \
      DELAY_TE_UDP_MEDIA_TEST_NAME="$(delay_demo_udp_media_test_name)" \
      DELAY_TE_RTP_STREAM_TEST_NAME="$(delay_demo_rtp_stream_test_name)" \
      DELAY_TE_TRACE_MAP_TEST_NAME="$(delay_demo_trace_map_test_name)" \
      DELAY_TE_BROADCAST_TEST_NAME="$(delay_demo_broadcast_test_name)" \
      CREATE_OUTPUT="${create_output}" \
      python3 - <<'PY'
import json
import os
import shlex

expected = (
    ("DELAY_TE_RTSP_TCP_TEST_ID", os.environ["DELAY_TE_RTSP_TCP_TEST_NAME"]),
    ("DELAY_TE_UDP_MEDIA_TEST_ID", os.environ["DELAY_TE_UDP_MEDIA_TEST_NAME"]),
    ("DELAY_TE_RTP_STREAM_TEST_ID", os.environ["DELAY_TE_RTP_STREAM_TEST_NAME"]),
    ("DELAY_TE_TRACE_MAP_TEST_ID", os.environ["DELAY_TE_TRACE_MAP_TEST_NAME"]),
    ("DELAY_TE_BROADCAST_TEST_ID", os.environ["DELAY_TE_BROADCAST_TEST_NAME"]),
)

text = os.environ["CREATE_OUTPUT"]
decoder = json.JSONDecoder()
index = 0
found = {}

while True:
    while index < len(text) and text[index].isspace():
        index += 1
    if index >= len(text):
        break
    try:
        payload, index = decoder.raw_decode(text, index)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Unable to parse ThousandEyes create-all output near offset {exc.pos}: {exc.msg}") from exc
    if isinstance(payload, dict):
        test_name = str(payload.get("testName", "")).strip()
        test_id = payload.get("testId")
        if test_name and test_id is not None:
            found[test_name] = str(test_id)

for env_name, test_name in expected:
    test_id = found.get(test_name)
    if not test_id:
        raise SystemExit(f"Missing ThousandEyes test ID for {test_name!r} in create-all output.")
    print(f"{env_name}={shlex.quote(test_id)}")
PY
  )"

  eval "${assignments}"
}

create_thousandeyes_tests() {
  local create_output=""

  create_output="$(
    ENV_FILE="${ENV_FILE}" \
      THOUSANDEYES_BEARER_TOKEN="${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}" \
      THOUSANDEYES_ACCOUNT_GROUP_ID="${THOUSANDEYES_ACCOUNT_GROUP_ID}" \
      TE_APP_SOURCE_AGENT_IDS="${TE_APP_SOURCE_AGENT_IDS}" \
      TE_MEDIA_SOURCE_AGENT_IDS="${TE_MEDIA_SOURCE_AGENT_IDS}" \
      TE_TARGET_AGENT_ID="${TE_TARGET_AGENT_ID}" \
      TE_UDP_TARGET_AGENT_ID="${TE_UDP_TARGET_AGENT_ID:-}" \
      TE_RTSP_TCP_TEST_NAME="$(delay_demo_rtsp_tcp_test_name)" \
      TE_UDP_MEDIA_TEST_NAME="$(delay_demo_udp_media_test_name)" \
      TE_RTP_STREAM_TEST_NAME="$(delay_demo_rtp_stream_test_name)" \
      TE_TRACE_MAP_TEST_NAME="$(delay_demo_trace_map_test_name)" \
      TE_BROADCAST_TEST_NAME="$(delay_demo_broadcast_test_name)" \
      TE_RTSP_TCP_TEST_ID="${DELAY_TE_RTSP_TCP_TEST_ID:-}" \
      TE_UDP_MEDIA_TEST_ID="${DELAY_TE_UDP_MEDIA_TEST_ID:-}" \
      TE_RTP_STREAM_TEST_ID="${DELAY_TE_RTP_STREAM_TEST_ID:-}" \
      TE_TRACE_MAP_TEST_ID="${DELAY_TE_TRACE_MAP_TEST_ID:-}" \
      TE_BROADCAST_TEST_ID="${DELAY_TE_BROADCAST_TEST_ID:-}" \
      TE_RTSP_SERVER="${ROUTER_PUBLIC_IP}" \
      TE_RTSP_PORT=8554 \
      TE_DEMO_MONKEY_FRONTEND_BASE_URL="http://${ROUTER_PUBLIC_IP}" \
      TE_TRACE_MAP_TEST_URL="http://${ROUTER_PUBLIC_IP}/api/v1/demo/public/trace-map" \
      TE_BROADCAST_TEST_URL="http://${ROUTER_PUBLIC_IP}/api/v1/demo/public/broadcast/live/index.m3u8" \
      bash "${AWS_REPO_ROOT}/scripts/thousandeyes/create-rtsp-tests.sh" create-all
  )"

  printf '%s\n' "${create_output}"
  capture_delay_demo_test_ids "${create_output}"
}

sync_dashboards() {
  ENV_FILE="${ENV_FILE}" STREAMING_K8S_NAMESPACE="${NAMESPACE}" \
    TE_RTSP_TCP_TEST_NAME="$(delay_demo_rtsp_tcp_test_name)" \
    TE_UDP_MEDIA_TEST_NAME="$(delay_demo_udp_media_test_name)" \
    TE_RTP_STREAM_TEST_NAME="$(delay_demo_rtp_stream_test_name)" \
    TE_TRACE_MAP_TEST_NAME="$(delay_demo_trace_map_test_name)" \
    TE_BROADCAST_TEST_NAME="$(delay_demo_broadcast_test_name)" \
    TE_RTSP_TCP_TEST_ID="${DELAY_TE_RTSP_TCP_TEST_ID:-}" \
    TE_UDP_MEDIA_TEST_ID="${DELAY_TE_UDP_MEDIA_TEST_ID:-}" \
    TE_RTP_STREAM_TEST_ID="${DELAY_TE_RTP_STREAM_TEST_ID:-}" \
    TE_TRACE_MAP_TEST_ID="${DELAY_TE_TRACE_MAP_TEST_ID:-}" \
    TE_BROADCAST_TEST_ID="${DELAY_TE_BROADCAST_TEST_ID:-}" \
    python3 "${AWS_REPO_ROOT}/scripts/thousandeyes/create-demo-dashboards.py" \
      --namespace "${NAMESPACE}" \
      --group-name "Streaming Service App ThousandEyes Tests ${CLUSTER_NAME}" \
      --skip-te-metric-validation
}

persist_state() {
  if [[ -z "${STREAMING_PUBLIC_RTSP_URL}" && -n "${ROUTER_PUBLIC_IP:-}" ]]; then
    STREAMING_PUBLIC_RTSP_URL="rtsp://${ROUTER_PUBLIC_IP}:8554/live"
  fi

  clear_state_file "${STATE_FILE}"
  write_state_value "${STATE_FILE}" AWS_REGION "${AWS_REGION}"
  write_state_value "${STATE_FILE}" AWS_PROFILE "${AWS_PROFILE:-}"
  write_state_value "${STATE_FILE}" AWS_ACCOUNT_ID "${account_id}"
  write_state_value "${STATE_FILE}" CLUSTER_NAME "${CLUSTER_NAME}"
  write_state_value "${STATE_FILE}" NAMESPACE "${NAMESPACE}"
  write_state_value "${STATE_FILE}" KUBECONFIG_FILE "${KUBECONFIG_FILE}"
  write_state_value "${STATE_FILE}" EKS_PUBLIC_ACCESS_CIDRS "${EKS_PUBLIC_ACCESS_CIDRS}"
  write_state_value "${STATE_FILE}" ROUTER_KEY_NAME "${ROUTER_KEY_NAME}"
  write_state_value "${STATE_FILE}" VPC_ID "${VPC_ID}"
  write_state_value "${STATE_FILE}" INTERNET_GATEWAY_ID "${INTERNET_GATEWAY_ID}"
  write_state_value "${STATE_FILE}" PUBLIC_ROUTE_TABLE_ID "${PUBLIC_ROUTE_TABLE_ID}"
  write_state_value "${STATE_FILE}" PRIVATE_ROUTE_TABLE_ID "${PRIVATE_ROUTE_TABLE_ID}"
  write_state_value "${STATE_FILE}" PUBLIC_SUBNET_A_ID "${PUBLIC_SUBNET_A_ID}"
  write_state_value "${STATE_FILE}" PUBLIC_SUBNET_B_ID "${PUBLIC_SUBNET_B_ID}"
  write_state_value "${STATE_FILE}" PRIVATE_SUBNET_A_ID "${PRIVATE_SUBNET_A_ID}"
  write_state_value "${STATE_FILE}" PRIVATE_SUBNET_B_ID "${PRIVATE_SUBNET_B_ID}"
  write_state_value "${STATE_FILE}" ROUTER_SECURITY_GROUP_ID "${ROUTER_SECURITY_GROUP_ID}"
  write_state_value "${STATE_FILE}" ROUTER_ROLE_NAME "${ROUTER_ROLE_NAME}"
  write_state_value "${STATE_FILE}" ROUTER_INSTANCE_PROFILE_NAME "${ROUTER_INSTANCE_PROFILE_NAME}"
  write_state_value "${STATE_FILE}" ROUTER_INSTANCE_ID "${ROUTER_INSTANCE_ID}"
  write_state_value "${STATE_FILE}" ROUTER_PRIVATE_ENI_ID "${ROUTER_PRIVATE_ENI_ID}"
  write_state_value "${STATE_FILE}" ROUTER_PRIVATE_ENI_ATTACHMENT_ID "${ROUTER_PRIVATE_ENI_ATTACHMENT_ID}"
  write_state_value "${STATE_FILE}" ROUTER_EIP_ALLOCATION_ID "${ROUTER_EIP_ALLOCATION_ID}"
  write_state_value "${STATE_FILE}" ROUTER_PUBLIC_IP "${ROUTER_PUBLIC_IP}"
  write_state_value "${STATE_FILE}" FRONTEND_INTERNAL_HOST "${FRONTEND_INTERNAL_HOST:-}"
  write_state_value "${STATE_FILE}" RTSP_INTERNAL_HOST "${RTSP_INTERNAL_HOST:-}"
  write_state_value "${STATE_FILE}" TE_MEDIA_SOURCE_AGENT_IDS "${TE_MEDIA_SOURCE_AGENT_IDS:-}"
  write_state_value "${STATE_FILE}" DELAY_TE_RTSP_TCP_TEST_NAME "$(delay_demo_rtsp_tcp_test_name)"
  write_state_value "${STATE_FILE}" DELAY_TE_UDP_MEDIA_TEST_NAME "$(delay_demo_udp_media_test_name)"
  write_state_value "${STATE_FILE}" DELAY_TE_RTP_STREAM_TEST_NAME "$(delay_demo_rtp_stream_test_name)"
  write_state_value "${STATE_FILE}" DELAY_TE_TRACE_MAP_TEST_NAME "$(delay_demo_trace_map_test_name)"
  write_state_value "${STATE_FILE}" DELAY_TE_BROADCAST_TEST_NAME "$(delay_demo_broadcast_test_name)"
  write_state_value "${STATE_FILE}" DELAY_TE_RTSP_TCP_TEST_ID "${DELAY_TE_RTSP_TCP_TEST_ID:-}"
  write_state_value "${STATE_FILE}" DELAY_TE_UDP_MEDIA_TEST_ID "${DELAY_TE_UDP_MEDIA_TEST_ID:-}"
  write_state_value "${STATE_FILE}" DELAY_TE_RTP_STREAM_TEST_ID "${DELAY_TE_RTP_STREAM_TEST_ID:-}"
  write_state_value "${STATE_FILE}" DELAY_TE_TRACE_MAP_TEST_ID "${DELAY_TE_TRACE_MAP_TEST_ID:-}"
  write_state_value "${STATE_FILE}" DELAY_TE_BROADCAST_TEST_ID "${DELAY_TE_BROADCAST_TEST_ID:-}"
  write_state_value "${STATE_FILE}" AWS_LBC_POLICY_NAME "${AWS_LBC_POLICY_NAME}"
  write_state_value "${STATE_FILE}" AWS_LBC_POLICY_ARN "${AWS_LBC_POLICY_ARN:-}"
  write_state_value "${STATE_FILE}" SPLUNK_DEPLOYMENT_ENVIRONMENT "${SPLUNK_DEPLOYMENT_ENVIRONMENT}"
  write_state_value "${STATE_FILE}" STREAMING_PUBLIC_RTSP_URL "${STREAMING_PUBLIC_RTSP_URL}"
  write_state_value "${STATE_FILE}" STREAMING_CLUSTER_LABEL "${STREAMING_CLUSTER_LABEL}"
}

if [[ "${RESUME_FROM_STATE}" == "true" ]]; then
  aws_log "Resuming from existing state file ${STATE_FILE}"
else
  ensure_key_pair
  discover_azs
  write_router_user_data "${TEMP_DIR}/router-user-data.sh"
  aws_log "Creating VPC, subnets, router EC2, and NAT path"
  create_network "${TEMP_DIR}/router-user-data.sh"
  persist_state
fi

if [[ "$(cluster_status)" == "ACTIVE" ]]; then
  aws_log "EKS cluster ${CLUSTER_NAME} is already ACTIVE; skipping control plane creation"
else
  aws_log "Creating EKS cluster ${CLUSTER_NAME}"
  create_cluster
fi
enforce_cluster_api_access_policy
persist_state

if primary_nodegroup_exists; then
  ensure_nodegroup_usable primary
  aws_log "Managed nodegroup primary already exists and is usable"
elif [[ -n "$(list_active_nodegroups)" ]]; then
  aws_log "Cluster already has ACTIVE managed nodegroups ($(list_active_nodegroups | tr '\n' ',' | sed 's/,$//')); skipping primary creation"
elif nodegroup_exists; then
  aws_fail "Cluster has managed nodegroups but none are ACTIVE: $(summarize_nodegroups). Delete or repair them before resuming."
else
  aws_log "Creating managed nodegroup primary"
  create_nodegroup
fi
persist_state

aws_log "Installing AWS Load Balancer Controller"
ensure_aws_load_balancer_controller
persist_state

aws_log "Deploying streaming-service-app with internal load balancers"
deploy_streaming_stack
persist_state

aws_log "Starting broadcast and operator load generators"
deploy_loadgen

if [[ "${INTEGRATION_MODE}" == "full" ]]; then
  aws_log "Deploying ThousandEyes Enterprise Agent inside the cluster"
  deploy_thousandeyes_agent
  persist_state

  aws_log "Creating public and media-path ThousandEyes tests"
  create_thousandeyes_tests
  persist_state

  aws_log "Syncing Splunk dashboards"
  sync_dashboards
  persist_state
else
  aws_log "Skipping Splunk and ThousandEyes integration because integration mode is infra-only"
fi

cat <<EOF
[eks-delay-demo] Environment created successfully.
[eks-delay-demo] Integration mode: ${INTEGRATION_MODE}
[eks-delay-demo] State file: ${STATE_FILE}
[eks-delay-demo] Kubeconfig: ${KUBECONFIG_FILE}
[eks-delay-demo] Router IP: ${ROUTER_PUBLIC_IP}
[eks-delay-demo] Frontend URL: http://${ROUTER_PUBLIC_IP}
[eks-delay-demo] RTSP URL: rtsp://${ROUTER_PUBLIC_IP}:8554/live
[eks-delay-demo] TE media source agent: ${TE_MEDIA_SOURCE_AGENT_IDS:-not-created}
EOF
