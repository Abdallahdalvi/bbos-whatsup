#!/usr/bin/env node
'use strict';

// Installs the reviewed Q20-integrated snapshot into a local Nokia4Ever
// staging copy. The legacy messageRouter.js is intentionally left untouched.
const fs = require('fs');
const path = require('path');

const rootArg = process.argv[2];
if (!rootArg) throw new Error('Usage: node patch_whatsappclient.js /path/to/nokia4ever-staging-copy');
const root = path.resolve(rootArg);
const payload = path.join(__dirname, 'payload');
const files = [
  ['package.json', '.before-q20-v2-patch'],
  ['package-lock.json', '.before-q20-v2-patch'],
  ['server.js', '.before-q20-v2-patch'],
  ['WhatsappClient.js', '.before-q20-patch'],
  ['q20SchemaV2.js', '.before-q20-v2-patch'],
  ['q20HistorySupport.js', '.before-q20-v2-patch'],
  ['messageRouterV2.js', '.before-q20-v2-patch'],
  ['q20CallSupport.js', '.before-q20-v2-patch'],
  ['q20RichMessages.js', '.before-q20-v2-patch'],
  ['q20MediaVariants.js', '.before-q20-v2-patch'],
  ['q20Config.js', '.before-q20-v2-patch'],
  ['q20-private-config.json', '.before-q20-v2-patch']
];

if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
  throw new Error(`Staging directory does not exist: ${root}`);
}
// Reject incomplete uploads before changing any existing source file.
for (const [name] of files) {
  if (name !== 'q20-private-config.json' && !fs.existsSync(path.join(payload, name))) {
    throw new Error(`Incomplete patch archive: missing payload/${name}. Extract the whole current ZIP first.`);
  }
}
for (const [name, backupSuffix] of files) {
  const source = path.join(payload, name);
  const target = path.join(root, name);
  if (!fs.existsSync(source)) {
    if (name === 'q20-private-config.json') continue; // Public source builds use the environment.
    throw new Error(`Patch payload is missing: ${source}`);
  }
  if (fs.existsSync(target)) {
    const backup = target + backupSuffix;
    if (!fs.existsSync(backup)) fs.copyFileSync(target, backup);
  }
  fs.copyFileSync(source, target);
}

console.log(`Installed reviewed Q20 v2 source into staging copy: ${root}`);
console.log('Legacy messageRouter.js and WhatsApp auth/session files were not modified.');
console.log('Review the backups, run npm install, and run the staging checks before deployment.');
