'use strict';

const { PassThrough } = require('stream');

// Adapted from the pinned whatsapp-web.js call-audio helpers (Apache-2.0).
// Live phone audio needs shorter blocks than the upstream recording/TTS defaults.
function installLowLatencyAudio() {
    const store = window.WWebJS;
    if (!store || store.__q20LowLatencyAudio) return;
    store.startCallAudioCapture = async callId => {
        const collection = window.require('WAWebCallCollection');
        const call = collection.activeCall || collection.lastActiveCall;
        if (!call || call.id !== callId || store._endedCallId === callId) return false;
        store.trackCallPlayback();
        store._callAudioCaptures = store._callAudioCaptures || {};
        if (store._callAudioCaptures[callId]) return true;
        const context = new AudioContext({ sampleRate: 16000, latencyHint: 'interactive' });
        if (context.state === 'suspended') await context.resume();
        const mixer = context.createGain();
        let processor;
        try { processor = context.createScriptProcessor(512, 1, 1); }
        catch (_) { processor = context.createScriptProcessor(1024, 1, 1); }
        const mute = context.createGain(); mute.gain.value = 0;
        mixer.connect(processor); processor.connect(mute); mute.connect(context.destination);
        processor.onaudioprocess = event => {
            const input = event.inputBuffer.getChannelData(0), pcm = new Int16Array(input.length);
            for (let i = 0; i < input.length; i++) { const sample = Math.max(-1, Math.min(1, input[i])); pcm[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff; }
            window.onCallAudioChunk(callId, store.arrayBufferToBase64(pcm.buffer));
        };
        const capture = { context, mixer, processor, sources: [] };
        store._callAudioCaptures[callId] = capture;
        for (const node of store._callPlayback || []) store.mirrorCallPlayback(capture, node);
        return true;
    };
    store.pushCallAudio = (streamId, base64) => {
        const playback = (store._callAudioPlayback || {})[streamId];
        if (!playback) return false;
        const collection = window.require('WAWebCallCollection');
        if (!collection.activeCall && !collection.lastActiveCall) { store.endCallAudioStream(streamId, true); return false; }
        const { context, master } = store._callMedia;
        const binary = atob(base64), maxSamples = Math.round(playback.sampleRate * 0.2);
        const length = Math.min(Math.floor(binary.length / 2), maxSamples), offset = Math.floor(binary.length / 2) - length;
        if (!length) return 0;
        const samples = new Float32Array(length);
        for (let i = 0; i < length; i++) { const at = (offset + i) * 2, value = binary.charCodeAt(at) | binary.charCodeAt(at + 1) << 8; samples[i] = (value >= 0x8000 ? value - 0x10000 : value) / 0x8000; }
        const now = context.currentTime;
        if (playback.nextTime - now > 0.24) {
            for (const source of playback.sources) { try { source.stop(); } catch (_) { } }
            playback.sources.clear(); playback.nextTime = 0;
        }
        if (playback.nextTime < now) playback.nextTime = now + 0.04;
        const buffer = context.createBuffer(1, length, playback.sampleRate); buffer.copyToChannel(samples, 0);
        const source = context.createBufferSource(); source.buffer = buffer; source.connect(master);
        source.start(playback.nextTime); playback.nextTime += buffer.duration;
        playback.sources.add(source); source.onended = () => playback.sources.delete(source);
        return playback.nextTime - now;
    };
    store.__q20LowLatencyAudio = true;
}

// Runs inside the existing WhatsApp page. Capture native early-return reasons;
// the upstream startCall helper currently discards startWAWebVoipCall(false).
function installCallTrace() {
    if (window.__q20CallTraceInstalled) return true;
    const trace = window.__q20CallTrace = [];
    const record = (stage, detail) => {
        trace.push({ at: new Date().toISOString(), stage, detail });
        if (trace.length > 32) trace.shift();
    };
    const summarize = value => {
        if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
        if (typeof value === 'string') return value.slice(0, 300);
        const summary = {};
        for (const key of ['status', 'reason', 'reloadReason', 'initErrorName', 'name', 'message', 'permission']) {
            if (typeof value[key] === 'string' || typeof value[key] === 'boolean') summary[key] = value[key];
        }
        for (const key of ['notReady', 'stop']) if (value[key] != null) summary[key] = summarize(value[key]);
        return summary;
    };
    const hooks = [
        ['WAWebVoipStartCall', 'startWAWebVoipCall'],
        ['WAWebVoipCallBlockedModals', 'showCallBlockedModalIfNeeded'],
        ['WAWebEnsureVoipInited', 'ensureVoipInitialized'],
        ['WAWebVoipOutgoingCallPreSetup', 'runOutgoingCallPreSetup'],
        ['WAWebVoipOutgoingCallPreSetup', 'finishOutgoingCallPreSetup'],
        ['WAWebVoipStartCallPermissions', 'checkStartCallPermissions'],
        ['WAWebVoipOutgoingCallQpl', 'endVoipOutgoingCallQplNotReady'],
        ['WAWebVoipOutgoingCallQpl', 'endVoipOutgoingCallQplFail'],
        ['WAWebVoipOutgoingCallQpl', 'endVoipOutgoingCallQplCancel']
    ];
    const restore = [];
    for (const [name, method] of hooks) {
        let module;
        try { module = window.require(name); } catch (_) { continue; }
        if (!module || typeof module[method] !== 'function') continue;
        const original = module[method];
        const wrapped = function (...args) {
            if (method === 'startWAWebVoipCall') trace.length = 0;
            record(method + ':enter', method.startsWith('endVoip') ? summarize(args[1]) : null);
            try {
                const result = original.apply(this, args);
                if (result && typeof result.then === 'function') {
                    return result.then(value => { record(method + ':return', summarize(value)); return value; },
                        error => { record(method + ':error', String(error && error.message || error)); throw error; });
                }
                record(method + ':return', summarize(result));
                return result;
            } catch (error) { record(method + ':error', String(error && error.message || error)); throw error; }
        };
        module[method] = wrapped;
        restore.push(() => { if (module[method] === wrapped) module[method] = original; });
    }
    window.__q20RestoreCallTrace = () => { for (const restoreHook of restore) restoreHook(); window.__q20CallTraceInstalled = false; };
    window.__q20CallTraceInstalled = true;
    return true;
}

/** Bridges a phone's 16 kHz mono PCM stream to the linked WhatsApp Web call. */
function createCallSupport(client, timings = {}) {
    let activeCall = null;
    let activeSocket = null;
    let microphone = null;
    let callEnding = false;
    let incomingPending = false;
    let starting = false;
    let orphanTimer = null;
    let remoteAudioStream = null;
    let lastError = null;
    let pendingStart = null;
    const startTimeoutMs = timings.startTimeoutMs || 25000;
    const startPollMs = timings.startPollMs || 150;
    function recordError(stage, error) {
        lastError = { at: new Date().toISOString(), stage, message: String(error && error.message || error || 'Unknown call failure') };
        console.error('Q20 call failure [' + stage + ']:', lastError.message);
    }
    function armOrphanTimer(id, milliseconds) {
        if (orphanTimer) clearTimeout(orphanTimer);
        orphanTimer = setTimeout(() => {
            endVoiceCall(id, 'Call session timed out').catch(error => console.warn('Q20 call timeout:', error.message));
        }, milliseconds);
        if (orphanTimer.unref) orphanTimer.unref();
    }

    if (client && typeof client.on === 'function') {
        client.on('call', call => {
            if (!call || call.fromMe) return;
            if (activeCall && String(activeCall.id) === String(call.id)) return;
            if (activeCall) {
                Promise.resolve(call.reject()).catch(error => console.warn('Could not reject overlapping WhatsApp call:', error.message || error));
                return;
            }
            activeCall = call;
            incomingPending = true;
            armOrphanTimer(String(call.id), 90000);
        });
    }

    function getStatus() {
        return activeCall ? { active: true, call_id: String(activeCall.id || ''), peer: activeCall.from && activeCall.from._serialized || activeCall.from || '', video: !!activeCall.isVideo, incoming: incomingPending, last_error: lastError }
            : { active: false, starting, last_error: lastError };
    }

    function readCallConfirmation(requestId) {
        const sentence = 'This business uses a secure service from Meta to manage this call.';
        const dialogs = Array.from(document.querySelectorAll('[role="dialog"], [data-animate-modal-popup]'));
        for (const dialog of dialogs) {
            const text = dialog.innerText || '';
            if (!text.includes('About this call') || !text.includes(sentence)) continue;
            const button = Array.from(dialog.querySelectorAll('button, [role="button"]')).find(button => (button.innerText || '').trim() === 'Continue');
            if (!button) continue;
            button.setAttribute('data-q20-call-confirm', requestId);
            return { title: 'About this call', message: text.replace(/\n(?:Learn more\n)?Continue\s*$/, '').replace(/^About this call\s*/, ''), button: 'Continue' };
        }
        return null;
    }

    async function cancelPendingStart(attempt) {
        if (!attempt || attempt.cancelled) return;
        attempt.cancelled = true;
        clearTimeout(attempt.expiry);
        if (pendingStart === attempt) { pendingStart = null; starting = false; }
        if (!attempt.nativeStarted) return;
        if (client.pupPage && typeof client.pupPage.evaluate === 'function') {
            await client.pupPage.evaluate(() => {
                window.require('WAWebVoipCancelOutgoingCall').cancelPendingOutgoingCall();
                const dialogs = Array.from(document.querySelectorAll('[role="dialog"], [data-animate-modal-popup]'));
                if (dialogs.some(dialog => (dialog.innerText || '').includes('This business uses a secure service from Meta to manage this call.'))) {
                    const manager = window.require('WAWebModalManager');
                    if (typeof manager.closeModalManager === 'function') manager.closeModalManager();
                }
            }).catch(() => {});
        }
    }

    async function awaitOutgoingReady(attempt) {
        const deadline = Date.now() + startTimeoutMs;
        while (!attempt.cancelled && Date.now() < deadline) {
            if (attempt.error) throw attempt.error;
            let call = attempt.call;
            if (!call && typeof client.getActiveCall === 'function') {
                const current = await client.getActiveCall();
                if (current && current.fromMe === true && current.id !== attempt.previousId) call = current;
            }
            if (attempt.cancelled) throw new Error('Call cancelled');
            if (call && call.id) {
                clearTimeout(attempt.expiry);
                pendingStart = null; starting = false;
                activeCall = call; callEnding = false; incomingPending = false;
                armOrphanTimer(String(call.id), 20000);
                return { call_id: String(call.id), peer: attempt.jid, state: 'ringing', audio: 'pcm_s16le_mono_16000' };
            }
            if (client.pupPage && typeof client.pupPage.evaluate === 'function') {
                const confirmation = await client.pupPage.evaluate(readCallConfirmation, attempt.id);
                if (attempt.cancelled) throw new Error('Call cancelled');
                if (confirmation && confirmation.title && !attempt.confirmed) {
                    clearTimeout(attempt.expiry);
                    attempt.expiry = setTimeout(() => cancelPendingStart(attempt).catch(() => {}), 90000);
                    if (attempt.expiry.unref) attempt.expiry.unref();
                    return { request_id: attempt.id, state: 'confirmation_required', confirmation };
                }
            }
            if (attempt.settled) {
                if (attempt.call && attempt.call.id) continue;
                throw new Error('WhatsApp did not start an outgoing call. Check the call diagnostics for the native reason.');
            }
            await new Promise(resolve => setTimeout(resolve, startPollMs));
        }
        throw new Error(attempt.cancelled ? 'Call cancelled' : 'WhatsApp did not start the call within 25 seconds. Please try again.');
    }

    async function confirmVoiceCall(requestId) {
        const attempt = pendingStart;
        if (!attempt || attempt.cancelled || attempt.id !== String(requestId)) throw new Error('The call confirmation expired. Start the call again.');
        try {
            const confirmation = await client.pupPage.evaluate(readCallConfirmation, attempt.id);
            if (!confirmation) throw new Error('The WhatsApp call confirmation is no longer available.');
            await client.pupPage.click('[data-q20-call-confirm="' + attempt.id + '"]');
            attempt.confirmed = true;
            return await awaitOutgoingReady(attempt);
        } catch (error) { await cancelPendingStart(attempt); recordError('outgoing_confirmation', error); throw error; }
    }

    async function startVoiceCall(chatJid) {
        if (!client || !client.info) throw new Error('WhatsApp session is not connected');
        if (typeof client.call !== 'function') {
            const error = new Error('The running server has the standard WhatsApp library. Install the pinned Q20 call-enabled dependency and restart the container.');
            error.code = 'CALL_DEPENDENCY_MISSING';
            throw error;
        }
        if (activeCall || starting) throw new Error('Another WhatsApp call is already active');
        const jid = String(chatJid || '').trim();
        if (!/@(?:c\.us|lid)$/i.test(jid)) throw new Error('Voice calls require a private WhatsApp contact');
        starting = true;
        const attempt = pendingStart = { id: require('crypto').randomBytes(16).toString('hex'), jid, cancelled: false, settled: false };
        // A bare phone number lets WhatsApp query its current canonical phone/LID identity.
        const target = jid.endsWith('@c.us') ? jid.slice(0, -5) : jid;
        try {
            if (client.pupPage && typeof client.pupPage.evaluate === 'function') await client.pupPage.evaluate(installCallTrace);
            if (client.pupPage && typeof client.pupPage.evaluate === 'function') await client.pupPage.evaluate(installLowLatencyAudio);
            if (attempt.cancelled) throw new Error('Call cancelled');
            const previous = typeof client.getActiveCall === 'function' ? await client.getActiveCall() : null;
            if (previous && previous.id) throw new Error('Another WhatsApp call is already active');
            attempt.previousId = previous && previous.id;
            attempt.nativeStarted = true;
            Promise.resolve(client.call(target, { video: false, waitForAnswer: false, injectAudio: true })).then(call => {
                attempt.call = call; attempt.settled = true;
                if (attempt.cancelled && call && call.id && call.fromMe === true) Promise.resolve(call.end()).catch(() => {});
            }, error => { attempt.error = error; attempt.settled = true; });
            return await awaitOutgoingReady(attempt);
        }
        catch (error) { await cancelPendingStart(attempt); recordError('outgoing_signaling', error); throw error; }
    }

    async function rejectVoiceCall(callId) {
        if (!activeCall || String(activeCall.id) !== String(callId || '') || !incomingPending) return false;
        const call = activeCall;
        if (orphanTimer) clearTimeout(orphanTimer);
        incomingPending = false;
        activeCall = null;
        await call.reject();
        return true;
    }

    async function endVoiceCall(callId, reason) {
        if (!callId && pendingStart) { await cancelPendingStart(pendingStart); return true; }
        if (!activeCall || (callId && String(callId) !== String(activeCall.id))) return false;
        if (callEnding) return true;
        callEnding = true;
        if (orphanTimer) clearTimeout(orphanTimer);
        if (remoteAudioStream) { remoteAudioStream.destroy(); remoteAudioStream = null; }
        const call = activeCall;
        if (activeSocket && activeSocket.readyState === 1) {
            try { activeSocket.send(JSON.stringify({ type: 'ended', reason: reason || 'Call ended' })); } catch (_) { }
        }
        if (microphone) { microphone.end(); microphone = null; }
        try { await call.end(); } finally {
            activeCall = null;
            incomingPending = false;
            if (activeSocket && activeSocket.readyState === 1) activeSocket.close(1000, 'Call ended');
            activeSocket = null;
            callEnding = false;
        }
        return true;
    }

    function attachSocket(callId, socket) {
        if (!activeCall || String(activeCall.id) !== String(callId)) {
            socket.close(4404, 'Call not found');
            return;
        }
        if (activeSocket && activeSocket.readyState === 1) {
            socket.close(4409, 'Call already has an audio connection');
            return;
        }
        activeSocket = socket;
        if (orphanTimer) clearTimeout(orphanTimer);
        microphone = new PassThrough({ highWaterMark: 6400 });
        let closed = false;
        const finish = async reason => {
            if (closed) return;
            closed = true;
            if (microphone) { microphone.end(); microphone = null; }
            if (activeCall && String(activeCall.id) === String(callId)) {
                try { await endVoiceCall(callId, reason || 'Audio connection closed'); } catch (error) {
                    console.error('Q20 call cleanup failed:', error.message || error);
                }
            }
        };
        socket.on('message', (data, isBinary) => {
            if (closed || activeSocket !== socket) return;
            if (isBinary) {
                if (microphone && !microphone.destroyed && data.length <= 8192 && microphone.writableLength < 6400) {
                    microphone.write(data);
                }
                return;
            }
            let message;
            try { message = JSON.parse(data.toString()); } catch (_) { return; }
            if (message.type === 'hangup') finish('Ended from Q20 Messenger');
            else if (message.type === 'resume') socket.resume();
        });
        microphone.on('drain', () => { if (socket.readyState === 1) socket.resume(); });
        socket.on('close', () => finish('Q20 audio connection lost'));
        socket.on('error', error => console.warn('Q20 call socket error:', error.message || error));

        (async () => {
            const call = activeCall;
            if (socket.readyState !== 1) return finish('Call connection closed');
            socket.send(JSON.stringify({ type: 'ringing', call_id: String(call.id), sample_rate: 16000, channels: 1, encoding: 'pcm_s16le' }));
            if (incomingPending) {
                try {
                    if (client.pupPage && typeof client.pupPage.evaluate === 'function') await client.pupPage.evaluate(installLowLatencyAudio);
                    await call.accept({ video: false, injectAudio: true });
                    incomingPending = false;
                } catch (error) {
                    recordError('incoming_answer', error);
                    socket.send(JSON.stringify({ type: 'failed', message: error.message || 'Could not answer incoming call' }));
                    return finish('Could not answer incoming call');
                }
            }
            const deadline = Date.now() + 90000;
            const registeredAt = Date.now();
            let connected = false;
            while (!closed && Date.now() < deadline) {
                if (socket.readyState !== 1) return finish('Call connection closed while ringing');
                try {
                    if (typeof client.getActiveCall === 'function') {
                        const current = await client.getActiveCall();
                        if ((!current || String(current.id) !== String(call.id)) && Date.now() - registeredAt > 5000) {
                            socket.send(JSON.stringify({ type: 'failed', message: 'The call ended before it was answered' }));
                            return finish('Call ended before answer');
                        }
                    }
                    connected = await call.isConnected();
                } catch (_) { connected = false; }
                if (connected) break;
                await new Promise(resolve => setTimeout(resolve, 700));
            }
            if (closed) return;
            if (!connected) {
                recordError('answer_timeout', new Error('No answer after 90 seconds'));
                socket.send(JSON.stringify({ type: 'failed', message: 'No answer after 90 seconds' }));
                return finish('No answer');
            }
            socket.send(JSON.stringify({ type: 'connected', sample_rate: 16000, channels: 1, encoding: 'pcm_s16le' }));
            try {
                const remoteAudio = await call.getAudioStream();
                remoteAudioStream = remoteAudio;
                remoteAudio.on('data', chunk => {
                    if (socket.readyState !== 1 || socket.bufferedAmount > 6400) return;
                    socket.send(chunk, { binary: true }, error => { if (error) finish('Audio send failed'); });
                });
                remoteAudio.on('error', error => {
                    if (socket.readyState === 1) socket.send(JSON.stringify({ type: 'failed', message: error.message || 'Remote audio failed' }));
                    finish('Remote audio failed');
                });
                remoteAudio.on('end', () => finish('Remote party ended the call'));
                await call.playAudioStream(microphone, { sampleRate: 16000 });
            } catch (error) {
                recordError('audio_bridge', error);
                if (!closed && socket.readyState === 1) socket.send(JSON.stringify({ type: 'failed', message: error.message || 'Audio bridge failed' }));
                finish('Audio bridge failed');
            }
        })().catch(error => {
            recordError('call_session', error);
            if (socket.readyState === 1) socket.send(JSON.stringify({ type: 'failed', message: error.message || 'Call failed' }));
            finish('Call failed');
        });
    }

    async function diagnostics() {
        const result = { status: getStatus(), connected: !!client.info, client_call: typeof client.call === 'function' };
        if (client.pupBrowser && typeof client.pupBrowser.version === 'function') {
            try { result.chromium_version = await client.pupBrowser.version(); } catch (_) { }
        }
        if (!client.pupPage || typeof client.pupPage.evaluate !== 'function') return result;
        try {
            result.browser = await client.pupPage.evaluate(async () => {
                const modules = {};
                for (const name of ['WAWebCallCollection', 'WAWebVoipStackInterface', 'WAWebVoipStartCall', 'WAWebQueryExistsJob']) {
                    try { modules[name] = !!window.require(name); } catch (error) { modules[name] = String(error.message || error); }
                }
                let gating = {}, devices = [];
                let initialization = {};
                try {
                    const emitter = window.require('WAWebVoipInitEventEmitter').VoipInitEventEmitter;
                    for (const key of ['getIsVoipInited', 'getDidVoipInitError', 'getIsVoipInitAttemptInFlight', 'getIsVoipStackUnresponsive']) {
                        if (typeof emitter[key] === 'function') initialization[key] = emitter[key]();
                    }
                } catch (_) { }
                try { const g = window.require('WAWebVoipGatingUtils'); gating = { calling_enabled: g.isCallingEnabled(), unsupported_reason: g.getUnsupportedBrowserReason(), download_enabled: g.isVoipDownloadEnabled() }; } catch (_) { }
                try { devices = (await navigator.mediaDevices.enumerateDevices()).map(device => device.kind); } catch (_) { }
                return { modules, gating, initialization, device_kinds: devices, call_trace: window.__q20CallTrace || [], web_version: window.Debug && window.Debug.VERSION,
                    user_agent: navigator.userAgent,
                    audio_context: !!(window.AudioContext || window.webkitAudioContext),
                    injected_start: !!(window.WWebJS && window.WWebJS.startCall),
                    injected_accept: !!(window.WWebJS && window.WWebJS.acceptCall) };
            });
        } catch (error) { result.browser_error = error.message; }
        return result;
    }

    return { startVoiceCall, confirmVoiceCall, endVoiceCall, rejectVoiceCall, attachSocket, getStatus, diagnostics,
        reportClientError: message => {
            if (lastError && Date.now() - Date.parse(lastError.at) < 120000 && String(message).includes(lastError.message)) lastError.client_message = String(message);
            else recordError('phone_audio_or_transport', message);
        } };
}

module.exports = createCallSupport;
module.exports.installCallTrace = installCallTrace;
module.exports.installLowLatencyAudio = installLowLatencyAudio;
