'use strict';
const assert=require('node:assert/strict');
const create=require('../q20CallSupport');
(async()=>{
 let resolveNative, clicked=0, ended=0;
 const call={id:'confirmed-call',fromMe:true,from:'2000@c.us',end:async()=>{ended++}};
 const client={info:{},on(){},getActiveCall:async()=>null,call:()=>new Promise(resolve=>{resolveNative=resolve}),
  pupPage:{evaluate:async fn=>fn.name==='readCallConfirmation'?{title:'About this call',message:'Business calling information',button:'Continue'}:undefined,
   click:async()=>{clicked++;resolveNative(call)}}};
 const support=create(client,{startTimeoutMs:500,startPollMs:5});
 const first=await support.startVoiceCall('2000@c.us');
 assert.equal(first.state,'confirmation_required');assert.equal(clicked,0,'must wait for phone confirmation');
 await assert.rejects(support.confirmVoiceCall('wrong-request'),/expired/);
 const confirmed=await support.confirmVoiceCall(first.request_id);
 assert.equal(confirmed.call_id,call.id);assert.equal(clicked,1);await support.endVoiceCall(call.id);
 const second=await support.startVoiceCall('2000@c.us');
 assert.equal(await support.endVoiceCall(''),true,'hang-up cancels pending confirmation');
 resolveNative(call);await new Promise(r=>setTimeout(r,5));assert.equal(ended,2,'a late native result is ended after cancellation');
 const never={info:{},on(){},call:()=>new Promise(()=>{}),getActiveCall:async()=>null,pupPage:{evaluate:async()=>null}};
 const timed=create(never,{startTimeoutMs:30,startPollMs:5});
 await assert.rejects(timed.startVoiceCall('2000@c.us'),/within 25 seconds/);assert.equal(timed.getStatus().starting,false);
 let started=false;
 const registering={info:{},on(){},call:()=>{started=true;return new Promise(()=>{})},getActiveCall:async()=>started?call:null};
 const early=create(registering,{startTimeoutMs:100,startPollMs:5});
 assert.equal((await early.startVoiceCall('2000@c.us')).call_id,call.id,'registered outgoing call must not wait on a blocked native UI promise');
 await early.endVoiceCall(call.id);
 console.log('PASS: phone-controlled business confirmation, wrong/expired request rejection, cancellation/late cleanup, bounded setup, early registered call handoff.');
})().catch(e=>{console.error(e);process.exitCode=1});

