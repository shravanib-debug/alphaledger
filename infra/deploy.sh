#!/usr/bin/env bash
# Deploys released images:  infra/deploy.sh <image-tag>   (the commit SHA). Rolls back if the stack is not healthy.
set -euo pipefail

cd "$(dirname "$0")"
tag="${1:?usage: deploy.sh <image-tag>}"
state=.deployed-tag
previous="$(cat "$state" 2>/dev/null || true)"
compose() { IMAGE_TAG="$1" docker compose -f docker-compose.yml -f docker-compose.prod.yml "${@:2}"; }

test -f .env || { echo "infra/.env is missing; see docs/deployment.md" >&2; exit 1; }

compose "$tag" pull --ignore-buildable --quiet
if compose "$tag" up -d --no-build --remove-orphans --wait --wait-timeout 420; then
    echo "$tag" > "$state"
    echo "Deployed $tag"
    exit 0
fi

echo "Deployment of $tag is unhealthy" >&2
compose "$tag" ps >&2
if [ -n "$previous" ]; then
    echo "Rolling back to $previous" >&2
    compose "$previous" up -d --no-build --remove-orphans --wait --wait-timeout 420
fi
exit 1
