'use strict';
const assert = require('node:assert/strict');
const EventEmitter = require('node:events');
const PassThrough = require('node:stream').PassThrough;
const createCallSupport = require('../q20CallSupport');

async function main() {
  const client = new EventEmitter();
  client.info = { wid: { user: '1000' } };
  const farEnd = new PassThrough();
  let microphoneBytes = null;
  const call = {
    id: 'fixture-call', from: '2000@c.us', isVideo: false,
    isConnected: async () => true,
    getAudioStream: async () => farEnd,
    playAudioStream: async input => new Promise(resolve => input.once('data', chunk => { microphoneBytes = Buffer.from(chunk); resolve(); })),
    end: async () => true
  };
  client.call = async jid => { assert.equal(jid, '2000'); return call; };
  const support = createCallSupport(client);
  const started = await support.startVoiceCall('2000@c.us');
  assert.equal(started.state, 'ringing');

  class FakeSocket extends EventEmitter {
    constructor() { super(); this.readyState = 1; this.bufferedAmount = 0; this.frames = []; }
    send(frame, options, callback) { this.frames.push(Buffer.isBuffer(frame) ? Buffer.from(frame) : String(frame)); if (typeof options === 'function') options(); else if (callback) callback(); }
    close() { if (this.readyState !== 3) { this.readyState = 3; this.emit('close'); } }
    pause() {}
    resume() {}
  }
  const socket = new FakeSocket();
  support.attachSocket(started.call_id, socket);
  for (let i = 0; i < 50 && !socket.frames.some(frame => typeof frame === 'string' && frame.indexOf('"connected"') >= 0); i++)
    await new Promise(resolve => setTimeout(resolve, 10));
  assert.ok(socket.frames.some(frame => typeof frame === 'string' && frame.indexOf('"connected"') >= 0), 'server marks answered call connected');

  const uplink = Buffer.from([1, 0, 2, 0, 3, 0]);
  socket.emit('message', uplink, true);
  for (let i = 0; i < 50 && !microphoneBytes; i++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.deepEqual(microphoneBytes, uplink, 'phone PCM is passed into WhatsApp call playback');

  const downlink = Buffer.from([8, 0, 9, 0]);
  farEnd.write(downlink);
  for (let i = 0; i < 50 && !socket.frames.some(frame => Buffer.isBuffer(frame)); i++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.ok(socket.frames.some(frame => Buffer.isBuffer(frame) && frame.equals(downlink)), 'WhatsApp PCM is streamed to the phone');
  await support.endVoiceCall(started.call_id, 'test complete');

  const incomingClient = new EventEmitter();
  incomingClient.info = { wid: { user: '1000' } };
  let incomingAccepted = false;
  let incomingRejected = false;
  const incomingCall = {
    id: 'fixture-incoming', from: '3000@c.us', fromMe: false, isVideo: false,
    isConnected: async () => incomingAccepted,
    accept: async () => { incomingAccepted = true; return true; },
    reject: async () => { incomingRejected = true; return true; },
    getAudioStream: async () => new PassThrough(),
    playAudioStream: async input => new Promise(resolve => input.on('end', resolve)),
    end: async () => true
  };
  incomingClient.getActiveCall = async () => incomingCall;
  const incomingSupport = createCallSupport(incomingClient);
  incomingClient.emit('call', incomingCall);
  incomingClient.emit('call', incomingCall);
  assert.equal(incomingRejected, false, 'duplicate events must not reject the same incoming call');
  assert.equal(incomingSupport.getStatus().incoming, true);
  const incomingSocket = new FakeSocket();
  incomingSupport.attachSocket(incomingCall.id, incomingSocket);
  for (let i = 0; i < 200 && !incomingSocket.frames.some(frame => typeof frame === 'string' && frame.indexOf('"connected"') >= 0); i++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(incomingAccepted, true, 'answering an incoming WhatsApp call activates the call');
  assert.ok(incomingSocket.frames.some(frame => typeof frame === 'string' && frame.indexOf('"connected"') >= 0));
  await incomingSupport.endVoiceCall(incomingCall.id, 'test complete');
  console.log('PASS: outgoing/incoming call state, accept, phone-to-WhatsApp PCM, WhatsApp-to-phone PCM, and hangup bridge.');
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; });

