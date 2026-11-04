'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const EventEmitter=require('node:events');
const {createContactCache,readCompactContacts}=require('../q20ContactCache');
const createCalls=require('../q20CallSupport');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const contact={id:{_serialized:'2000@c.us'},name:'Saved name',isWAContact:true};

test('concurrent contact pages use one browser read and a fresh cache',async()=>{
 let loads=0;const cache=createContactCache({load:async()=>{loads++;return [contact]},fallback:async()=>[],warn(){}});
 const pages=await Promise.all(Array.from({length:30},()=>cache.get()));
 assert.equal(loads,1);assert.ok(pages.every(page=>page[0].name==='Saved name'));await cache.get();assert.equal(loads,1);assert.equal(cache.status().stale,false);
});
test('stale names return immediately while one underlying browser read remains stuck',async()=>{
 let time=1000,loads=0,resolve;const stuck=new Promise(done=>{resolve=done});
 const cache=createContactCache({load:()=>++loads===1?Promise.resolve([contact]):stuck,fallback:async()=>[],ttlMs:10,now:()=>time,warn(){}});
 await cache.get();time+=20;await cache.get();await tick();
 for(let i=0;i<100;i++)assert.equal((await cache.get())[0].name,'Saved name');
 assert.equal(loads,2);assert.equal(cache.status().refreshing,true);resolve([contact]);await tick();
});
test('cold timeout retains its browser request and subsequent requests fail fast during backoff',async()=>{
 let loads=0,resolve;const cache=createContactCache({load:()=>{loads++;return new Promise(done=>{resolve=done})},fallback:async()=>[],timeoutMs:20,retryMs:1000,warn(){}});
 await assert.rejects(cache.get(),error=>error.status===503);
 await Promise.all(Array.from({length:20},()=>assert.rejects(cache.get(),error=>error.status===503)));
 assert.equal(loads,1);assert.equal(cache.status().refreshing,true);resolve([contact]);await tick();assert.equal((await cache.get())[0].name,'Saved name');
});
test('persisted names survive failure and background refresh obeys retry backoff',async()=>{
 let time=1000,loads=0,warnings=0;const cache=createContactCache({load:async()=>{loads++;throw Error('Protocol timeout')},fallback:async()=>[contact],retryMs:100,now:()=>time,warn(){warnings++}});
 assert.equal((await cache.get())[0].name,'Saved name');await tick();
 for(let i=0;i<10;i++)await cache.get();assert.equal(loads,1);assert.equal(warnings,1);assert.equal(cache.status().source,'stored');assert.equal(cache.status().stale,true);
 time+=101;await cache.get();await tick();assert.equal(loads,2);
});
test('compact snapshot reads names without serialize or BusinessProfile.find',()=>{
 const business={id:{_serialized:'3000@c.us'},name:'Business',isBusiness:true,isWAContact:true,serialize(){throw Error('Full serialization called')}};
 const window={require(name){if(name==='WAWebContactCollection')return {ContactCollection:{getModelsArray:()=>[business]}};throw Error('Unexpected module: '+name)}};
 const result=vm.runInNewContext('('+readCompactContacts.toString()+')()',{window});
 assert.equal(result[0].id._serialized,'3000@c.us');assert.equal(result[0].name,'Business');assert.equal(result[0].serialize,undefined);
});
test('stuck diagnostic requests have a deadline and share the original browser call',async()=>{
 let versions=0,release;const client=new EventEmitter();client.info={};client.pupBrowser={version(){versions++;return new Promise(resolve=>{release=resolve})}};client.pupPage={evaluate:async()=>({web_version:'fixture'})};
 const calls=createCalls(client,{diagnosticsTimeoutMs:20});
 const first=await calls.diagnostics();assert.equal(first.browser_pending,true);assert.match(first.browser_error,/exceeded/);
 const second=await calls.diagnostics();assert.equal(second.browser_pending,true);assert.equal(versions,1);
 release('Fixture Chrome');await tick();const recovered=await calls.diagnostics();assert.equal(recovered.browser.web_version,'fixture');assert.equal(recovered.browser_error,undefined);
});
test('legacy getContacts keeps its paged response while bypassing Client.getContacts',async()=>{
 const models=[contact,{id:{_serialized:'3000@c.us'},name:'Other',isWAContact:true}];
 class Client extends EventEmitter{constructor(){super();this.info={};this.pupPage={evaluate:async fn=>vm.runInNewContext('('+fn.toString()+')()',{window:{require:()=>({ContactCollection:{getModelsArray:()=>models}})}})}}initialize(){return Promise.resolve()}getContacts(){throw Error('Full Client.getContacts must not run')}}
 const code=fs.readFileSync(path.join(__dirname,'../WhatsappClient.js'),'utf8');const exports={};
 const context={module:{exports},exports,__dirname:path.join(__dirname,'..'),console:{log(){},warn(){},error(){}},process,Buffer,setTimeout,clearTimeout,require(name){
  if(name==='whatsapp-web.js')return {Client,LocalAuth:class{},MessageMedia:class{}};
  if(name==='qrcode-terminal'||name==='qr-image')return {};
  if(name==='google-libphonenumber')return {PhoneNumberUtil:{getInstance:()=>({})},PhoneNumberFormat:{}};
  if(name==='fluent-ffmpeg')return ()=>{};
  if(name==='./connect.js')return {db:()=>({all:async()=>[]})};
  if(name==='./q20HistorySupport')return ()=>({});
  if(name==='./q20ContactCache')return require('../q20ContactCache');
  return require(name);
 }};
 vm.runInNewContext(code,context,{filename:'WhatsappClient-fixture.js'});
 const result=await context.module.exports.getContacts('1000','saved',0,1);
 assert.equal(result.status,200);assert.equal(result.data.count,1);assert.equal(result.data.contacts.length,1);assert.equal(result.data.contacts[0].name,'Saved name');assert.ok(result.data.contact_cache);
});
