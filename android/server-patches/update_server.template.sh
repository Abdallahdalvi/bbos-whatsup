#!/usr/bin/env bash
set -euo pipefail
container="${1:-nokia4ever}"
docker inspect "$container" >/dev/null
update_dir=$(mktemp -d /tmp/q20-v013-update.XXXXXX)
mkdir -p "$update_dir/payload" "$update_dir/backup"
echo "Preparing Q20 0.13 update in $update_dir"
base64 -d <<'Q20_PAYLOAD_BASE64' | tar -xz -C "$update_dir/payload"
__Q20_PAYLOAD__
Q20_PAYLOAD_BASE64
for source in "$update_dir"/payload/*; do
  name=$(basename "$source")
  docker cp "$container:/app/$name" "$update_dir/backup/$name" >/dev/null 2>&1 || true
done
docker exec -w /app "$container" node -e "const fs=require('fs');if(!fs.existsSync('/app/q20-browser-config.json')){const executablePath=process.env.PUPPETEER_EXECUTABLE_PATH||require('puppeteer').executablePath();if(fs.existsSync(executablePath))fs.writeFileSync('/app/q20-browser-config.json',JSON.stringify({executablePath}))}"
docker cp "$container:/app/q20-browser-config.json" "$update_dir/backup/q20-browser-config.json"
docker cp "$update_dir/payload/." "$container:/app/"
echo "Checking call-library implementation..."
if docker exec -w /app "$container" node -e "const C=require('whatsapp-web.js').Client,K=require('whatsapp-web.js/src/structures/Call');if(!['call','getActiveCall'].every(m=>typeof C.prototype[m]==='function')||!['accept','end','getAudioStream','playAudioStream'].every(m=>typeof K.prototype[m]==='function'))process.exit(1)"; then
  echo "Call-enabled package already verified; keeping it."
else
  docker exec -u 0 -w /app -e PUPPETEER_SKIP_DOWNLOAD=true -i "$container" sh -s <<'Q20_INSTALL_CALLS'
set -eu
backup=$(mktemp -d /tmp/q20-call-library-backup.XXXXXX)
if [ -d /app/node_modules/whatsapp-web.js ]; then mv /app/node_modules/whatsapp-web.js "$backup/whatsapp-web.js"; fi
if npm install --save-exact --omit=dev --ignore-scripts --force --no-audit --no-fund https://codeload.github.com/alpersamur3/whatsapp-web.js/tar.gz/8d46caf822ac35e805f4749e1ebeb8e5c56b4c6d ws@8.21.3 && node -e "const C=require('whatsapp-web.js').Client,K=require('whatsapp-web.js/src/structures/Call');if(!['call','getActiveCall'].every(m=>typeof C.prototype[m]==='function')||!['accept','end','getAudioStream','playAudioStream'].every(m=>typeof K.prototype[m]==='function'))process.exit(1)"; then
  echo "Call package verified; old package: $backup"
else
  if [ -e /app/node_modules/whatsapp-web.js ]; then mv /app/node_modules/whatsapp-web.js "$backup/failed-install"; fi
  if [ -d "$backup/whatsapp-web.js" ]; then mv "$backup/whatsapp-web.js" /app/node_modules/whatsapp-web.js; fi
  exit 1
fi
Q20_INSTALL_CALLS
fi
docker exec -w /app "$container" node -e "require('sqlite3');require('ws');console.log('SQLite and WebSocket dependencies loaded')"
for source in "$update_dir"/payload/*.js; do docker exec "$container" node --check "/app/$(basename "$source")"; done
if ! docker exec "$container" sh -c 'command -v ffmpeg >/dev/null'; then
  docker exec -u 0 "$container" sh -c 'if command -v apt-get >/dev/null; then apt-get update && apt-get install -y --no-install-recommends ffmpeg; elif command -v apk >/dev/null; then apk add --no-cache ffmpeg; else echo "Install FFmpeg before using converted media" >&2; exit 1; fi'
fi
echo "Installing and validating Chrome 156 (old browser is retained)..."
docker exec -u 0 -w /app -i "$container" node <<'Q20_BROWSER'
const fs=require('fs'),os=require('os'),path=require('path');
const browsers=require('@puppeteer/browsers'),puppeteer=require('puppeteer');
(async()=>{
  if(process.arch!=='x64')throw Error('This browser upgrade is for x64 CasaOS. Preserve the existing browser and provide the server architecture for a matching build.');
  const installed=await browsers.install({browser:browsers.Browser.CHROME,buildId:'156.0.8078.80',cacheDir:'/app/.q20-browser-cache'});
  const browser=await puppeteer.launch({executablePath:installed.executablePath,headless:true,userDataDir:fs.mkdtempSync(path.join(os.tmpdir(),'q20-browser-check-')),args:['--no-sandbox','--disable-setuid-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required','--use-fake-ui-for-media-stream']});
  let userAgent;
  try{
    const page=await browser.newPage();
    const audio=await page.evaluate(async()=>{const c=new AudioContext();await c.resume();const state=c.state;await c.close();return state});
    if(audio!=='running')throw Error('Browser audio context did not start');
    console.log('Validated '+await browser.version()+' and Web Audio (scratch profile only)');
    userAgent=(await browser.userAgent()).replace('HeadlessChrome/','Chrome/');
  }finally{await browser.close()}
  fs.writeFileSync('/app/q20-browser-candidate.json',JSON.stringify({executablePath:installed.executablePath,forceExecutable:true,userAgent}));
})().catch(error=>{console.error(error.stack||error);process.exitCode=1});
Q20_BROWSER
docker cp "$container:/app/q20-browser-candidate.json" "$update_dir/browser-candidate.json"
if docker exec -w /app "$container" node -e "const fs=require('fs'),a=JSON.parse(fs.readFileSync('q20-browser-config.json')),b=JSON.parse(fs.readFileSync('q20-browser-candidate.json'));if(a.executablePath!==b.executablePath||a.userAgent!==b.userAgent||!a.forceExecutable)process.exit(1)"; then
  echo "Selected browser is unchanged; restarting updated source without another profile migration."
  docker restart "$container" >/dev/null
else
# Stop before taking the profile snapshot so no browser writes occur during the copy.
echo "Stopping container for a consistent browser-profile backup..."
docker stop "$container" >/dev/null
if ! docker cp "$container:/app/.wwebjs_auth" "$update_dir/whatsapp-profile-before-browser"; then
  echo "Profile backup failed; restarting with the previous browser configuration." >&2
  docker start "$container" >/dev/null
  exit 1
fi
if ! docker cp "$update_dir/browser-candidate.json" "$container:/app/q20-browser-config.json"; then
  docker start "$container" >/dev/null
  exit 1
fi
docker start "$container" >/dev/null
fi
stage=/opt/nokia4ever/q20-patch-20261103-120327/staging
if [ -d "$stage" ]; then
  mkdir -p "$update_dir/host-backup"
  for source in "$update_dir"/payload/*; do
    name=$(basename "$source")
    if [ -f "$stage/$name" ]; then cp -p "$stage/$name" "$update_dir/host-backup/$name"; fi
    cp "$source" "$stage/$name"
  done
fi
echo "Waiting for HTTP startup and WhatsApp reconnection..."
sleep 3
docker exec -w /app -i "$container" node <<'Q20_VERIFY'
const http=require('http');
const token=require('./q20Config').apiToken();
function get(route){return new Promise((resolve,reject)=>{const req=http.get({hostname:'127.0.0.1',port:process.env.PORT||80,path:route,headers:{Authorization:'Bearer '+token}},res=>{let body='';res.on('data',chunk=>body+=chunk);res.on('end',()=>{try{resolve({status:res.statusCode,body:JSON.parse(body)})}catch(error){reject(error)}})});req.on('error',reject);req.setTimeout(10000,()=>req.destroy(new Error('HTTP timeout')))})}
(async()=>{
  let health;
  for(let i=0;i<30;i++){
    try{health=await get('/api/v2/health');if(health.status===200&&health.body.connected)break}catch(_){}
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  console.log('Health: '+JSON.stringify(health));
  console.log('Call runtime: '+JSON.stringify(await get('/api/v2/calls/diagnostics')));
  if(!health||health.status!==200)process.exitCode=1;
})().catch(error=>{console.error(error.message);process.exitCode=1});
Q20_VERIFY
echo "Q20 0.13 installed. Source/browser config backups: $update_dir/backup"
if [ -d "$update_dir/whatsapp-profile-before-browser" ]; then echo "Previous WhatsApp browser profile snapshot: $update_dir/whatsapp-profile-before-browser"; fi
echo "Existing auth and database directories were not replaced; the browser reuses the linked session."
echo "Future image rebuilds must include the matching source, dependencies and browser configuration."
