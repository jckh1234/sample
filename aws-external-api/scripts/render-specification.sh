#!/usr/bin/env bash
# Replace <<...>> tokens in specification.yaml. CI does the same replacement
# before the OpenAPI file is imported. The rendered file is local build output.
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "usage: AWS_REGION=... AWS_ACCOUNT_ID=... [ROUTER_FUNCTION_NAME=...] [LAMBDA_AUTHORIZER_NAME=...] $0" >&2
  exit 0
fi

: "${AWS_REGION:?set AWS_REGION}"
: "${AWS_ACCOUNT_ID:?set AWS_ACCOUNT_ID}"
ROUTER_FUNCTION_NAME="${ROUTER_FUNCTION_NAME:-external-api-proxy}"
LAMBDA_AUTHORIZER_NAME="${LAMBDA_AUTHORIZER_NAME:-external-api-okta-authorizer}"

if [[ ! "$AWS_REGION" =~ ^[a-z0-9-]+$ ]]; then
  echo "AWS_REGION must contain only lowercase letters, digits, and hyphens" >&2
  exit 1
fi
if [[ ! "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]]; then
  echo "AWS_ACCOUNT_ID must be a 12-digit account id" >&2
  exit 1
fi
if [[ ! "$ROUTER_FUNCTION_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]]; then
  echo "ROUTER_FUNCTION_NAME is not a Lambda function name" >&2
  exit 1
fi
if [[ ! "$LAMBDA_AUTHORIZER_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]]; then
  echo "LAMBDA_AUTHORIZER_NAME is not a Lambda function name" >&2
  exit 1
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$root/build"
sed \
  -e "s/<<AWS_REGION>>/${AWS_REGION}/g" \
  -e "s/<<AWS_ACCOUNT_ID>>/${AWS_ACCOUNT_ID}/g" \
  -e "s/<<ROUTER_FUNCTION_NAME>>/${ROUTER_FUNCTION_NAME}/g" \
  -e "s/<<LAMBDA_AUTHORIZER_NAME>>/${LAMBDA_AUTHORIZER_NAME}/g" \
  "$root/specification.yaml" > "$root/build/specification.yaml"

if grep -q '<<' "$root/build/specification.yaml"; then
  echo "unresolved <<...>> placeholders remain in build/specification.yaml" >&2
  exit 1
fi

echo "wrote $root/build/specification.yaml"
