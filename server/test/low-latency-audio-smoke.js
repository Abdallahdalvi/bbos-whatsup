'use strict';
const vm=require('node:vm'),assert=require('node:assert/strict'),install=require('../q20CallSupport').installLowLatencyAudio;
(async()=>{
 let block,stopped=0;
 class AudioContext {
  constructor(){this.state='running';this.currentTime=1;this.destination={};}
  createGain(){return{gain:{value:1},connect(){}}}
  createScriptProcessor(size){block=size;return{connect(){}}}
  createBuffer(_channels,length,rate){return{duration:length/rate,copyToChannel(){}}}
  createBufferSource(){return{connect(){},start(){},stop(){stopped++}}}
 }
 const store={trackCallPlayback(){},arrayBufferToBase64:b=>Buffer.from(b).toString('base64'),_callPlayback:[],_callMedia:{context:new AudioContext(),master:{}},_callAudioPlayback:{live:{sampleRate:16000,nextTime:3,sources:new Set([{stop(){stopped++}}])}}};
 const context=vm.createContext({window:{WWebJS:store,require:()=>({activeCall:{id:'live'}}),onCallAudioChunk(){}},AudioContext,atob:b=>Buffer.from(b,'base64').toString('binary')});
 vm.runInContext('('+install.toString()+')()',context);
 assert.equal(await store.startCallAudioCapture('live'),true);assert.equal(block,512,'capture must batch 32ms rather than 256ms');
 const queued=store.pushCallAudio('live',Buffer.alloc(64000).toString('base64'));
 assert.ok(queued<=0.241,'a stalled sender must not leave seconds of stale speech queued');assert.equal(stopped,1);
 console.log('PASS: 32ms capture blocks and bounded live playback queue with stale-audio disposal.');
})().catch(e=>{console.error(e);process.exitCode=1});

