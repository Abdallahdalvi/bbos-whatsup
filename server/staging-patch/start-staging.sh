#!/usr/bin/env bash
set -euo pipefail
source_container="${1:-nokia4ever}"
stage_container=nokia4ever-staging-018
source_commit=b9531f84d014095d8f7703170e5e985c6488031b
for tool in docker git node; do command -v "$tool" >/dev/null || { echo "Required command missing: $tool" >&2; exit 1; }; done
docker inspect "$source_container" >/dev/null
if docker inspect "$stage_container" >/dev/null 2>&1; then
  echo "Staging container $stage_container already exists. Nothing was changed." >&2
  exit 1
fi
if command -v ss >/dev/null && ss -ltnH 'sport = :8035' | grep -q .; then
  echo 'Port 8035 is already in use. Nothing was changed.' >&2
  exit 1
fi
stage_root=$(mktemp -d /opt/q20-staging-018.XXXXXX)
chmod 700 "$stage_root"
echo "Preparing isolated staging at $stage_root"
git clone --quiet --no-checkout https://github.com/Abdallahdalvi/bbos-whatsup.git "$stage_root/source"
git -C "$stage_root/source" checkout --quiet --detach "$source_commit"
app="$stage_root/source/server"
image=$(docker inspect --format '{{.Image}}' "$source_container")
echo 'Copying installed libraries and Chrome only; fresh auth, database and media directories will be used.'
docker cp "$source_container:/app/node_modules" "$app/node_modules"
docker cp "$source_container:/app/.q20-browser-cache" "$app/.q20-browser-cache"
docker cp "$source_container:/app/q20-browser-config.json" "$app/q20-browser-config.json"
docker cp "$source_container:/app/q20-private-config.json" "$app/q20-private-config.json"
chmod 600 "$app/q20-private-config.json"
# Preserve the effective API token even when the live environment overrides its
# config file. The value is written directly to the private staging file.
docker inspect --format '{{json .Config.Env}}' "$source_container" | node -e '
const fs=require("fs"),path=require("path");let input="";
process.stdin.on("data",chunk=>input+=chunk);process.stdin.on("end",()=>{
 const root=process.argv[1],file=path.join(root,"q20-private-config.json");
 const config=JSON.parse(fs.readFileSync(file,"utf8"));
 const match=JSON.parse(input).find(value=>value.startsWith("Q20_API_TOKEN="));
 if(match&&match.slice(14).trim())config.Q20_API_TOKEN=match.slice(14).trim();
 if(!String(config.Q20_API_TOKEN||"").trim())throw Error("No configured server API token; staging was not started");
 const browser=JSON.parse(fs.readFileSync(path.join(root,"q20-browser-config.json"),"utf8"));
 const exe=String(browser.executablePath||"");
 if(!exe.startsWith("/app/.q20-browser-cache/")||!fs.existsSync(path.join(root,exe.slice(5))))throw Error("Browser location differs from the expected installed cache; staging was not started");
 fs.writeFileSync(file,JSON.stringify(config),{mode:0o600});
});' "$app"
mkdir -p "$app/.wwebjs_auth" "$app/.wwebjs_cache" "$app/data" "$app/media"
docker run --detach --name "$stage_container" --user 0 \
  --label com.q20.environment=staging \
  --publish 8035:80 --workdir /app --entrypoint node \
  --env PORT=80 --env Q20_API_TOKEN= \
  --mount "type=bind,src=$app,dst=/app" \
  --mount "type=bind,src=$app/node_modules,dst=/app/node_modules" \
  --mount "type=bind,src=$app/.wwebjs_auth,dst=/app/.wwebjs_auth" \
  --mount "type=bind,src=$app/.wwebjs_cache,dst=/app/.wwebjs_cache" \
  --mount "type=bind,src=$app/data,dst=/app/data" \
  --mount "type=bind,src=$app/media,dst=/app/media" \
  --mount "type=bind,src=$app/.q20-browser-cache,dst=/app/.q20-browser-cache" \
  "$image" server.js
echo "Staging started: http://192.168.0.107:8035"
echo "Live container $source_container was only read; it was not restarted or modified."
echo 'Staging has a fresh WhatsApp session. Link a separate test account using its QR.'
echo "Read startup/QR logs: sudo docker logs -f --tail 100 $stage_container"
echo 'Use a separate test client with staging; the existing phone app keeps the live account history.'
