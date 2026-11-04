#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const manifest=require('./manifest.json');
const args=process.argv.slice(2),at=args.indexOf('--staging');
if(at<0||!args[at+1]||!path.isAbsolute(args[at+1]))throw Error('Usage: node apply-staging.js --staging /absolute/path/to/isolated-staging-copy [--check]');
const root=fs.realpathSync(args[at+1]);
if(root==='/app'||root===path.parse(root).root)throw Error('Refusing an application/root path; provide an isolated staging copy.');
for(const name of ['server.js','messageRouter.js','connect.js'])if(!fs.existsSync(path.join(root,name)))throw Error('Not a complete Nokia4Ever staging copy: missing '+name);
const normalized=bytes=>bytes.toString('utf8').replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
const hash=bytes=>crypto.createHash('sha256').update(normalized(bytes)).digest('hex');
const changes=[];
for(const entry of manifest.files){
 if(!['WhatsappClient.js','q20CallSupport.js','messageRouterV2.js','q20ContactCache.js'].includes(entry.name))throw Error('Unexpected patch filename');
 const source=path.join(__dirname,'..',entry.name),target=path.join(root,entry.name),bytes=fs.readFileSync(source);
 if(hash(bytes)!==entry.updated_sha256)throw Error('Damaged patch file: '+entry.name);
 cp.execFileSync(process.execPath,['--check',source],{stdio:'pipe'});
 const existing=fs.existsSync(target)?fs.readFileSync(target):null;
 if(existing&&fs.lstatSync(target).isSymbolicLink())throw Error('Refusing symlinked staging source: '+entry.name);
 const current=existing?hash(existing):null;
 if(current===entry.updated_sha256)continue;
 if(current!==entry.base_sha256)throw Error('Unrecognized staging base for '+entry.name+'. Review the source difference before applying; nothing was changed.');
 changes.push({entry,target,bytes,existing});
}
console.log('Validated isolated staging target: '+root);
if(args.includes('--check')){console.log('Dry run: '+changes.length+' files would change.');process.exit(0);}
if(changes.length===0){console.log('Staging patch already installed.');process.exit(0);}
const backup=path.join(root,'.q20-contact-fix-backups',new Date().toISOString().replace(/[:.]/g,'-'));
fs.mkdirSync(backup,{recursive:true});
for(const item of changes)if(item.existing)fs.writeFileSync(path.join(backup,item.entry.name),item.existing);
fs.writeFileSync(path.join(backup,'manifest.json'),JSON.stringify({root,version:manifest.version,files:changes.map(item=>({name:item.entry.name,existed:!!item.existing}))},null,2));
for(const item of changes){const temp=item.target+'.q20-stage-new';fs.writeFileSync(temp,item.bytes,{flag:'wx'});fs.renameSync(temp,item.target);}
console.log('Applied '+changes.length+' source files to staging only. Backups: '+backup);
console.log('No Docker commands, process restart, dependency installation, database migration or auth operations were performed.');
