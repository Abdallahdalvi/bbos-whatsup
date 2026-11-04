#!/usr/bin/env bash
set -euo pipefail
container="${1:?Usage: bash Q20Messenger-update-server.sh YOUR_STAGING_CONTAINER}"
# This release is authorized for staging only. A live container must not pass.
environment=$(docker inspect --format '{{ index .Config.Labels "com.q20.environment" }}' "$container")
if [ "$environment" != staging ]; then
  echo 'Refusing update: the container must be an isolated staging instance labelled com.q20.environment=staging.' >&2
  exit 1
fi
update_dir=$(mktemp -d /tmp/q20-v018-staging.XXXXXX)
mkdir -p "$update_dir/payload" "$update_dir/backup"
base64 -d <<'Q20_PAYLOAD_BASE64' | tar -xz -C "$update_dir/payload"
__Q20_PAYLOAD__
Q20_PAYLOAD_BASE64
files=(WhatsappClient.js q20ContactCache.js q20CallSupport.js messageRouterV2.js)
for name in "${files[@]}"; do
  test -f "$update_dir/payload/$name"
  docker exec -i -w /app "$container" node --check < "$update_dir/payload/$name"
  docker cp "$container:/app/$name" "$update_dir/backup/$name" >/dev/null 2>&1 || true
done
for name in "${files[@]}"; do docker cp "$update_dir/payload/$name" "$container:/app/$name"; done
docker restart "$container" >/dev/null
echo "Backend 0.18 installed on labelled staging container $container. Source backup: $update_dir/backup"
echo 'Run staging contacts and real-call acceptance checks. This does not certify live calling.'
