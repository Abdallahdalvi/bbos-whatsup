'use strict';

function deadline(promise, milliseconds, label) {
    let timer;
    const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(Object.assign(new Error(label + ' exceeded ' + milliseconds + ' ms'), { status: 503 })), milliseconds); });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

// Executed in the browser: names/IDs only, without BusinessProfile.find() or
// full contact serialization. These property reads do not request profiles.
function readCompactContacts() {
    let collection;
    try { collection = window.require('WAWebContactCollection').ContactCollection; } catch (_) { }
    if (!collection) collection = window.require('WAWebCollections').Contact;
    if (!collection || typeof collection.getModelsArray !== 'function') throw new Error('WhatsApp contact collection is unavailable');
    return collection.getModelsArray().map(contact => {
        const id = contact.id && (contact.id._serialized || contact.id.toString());
        const text=value=>typeof value==='string'?value:'';
        return { id: { _serialized: String(id || '') }, isWAContact: contact.isWAContact,
            name: text(contact.name), pushname: text(contact.pushname || contact.notifyName),
            shortName: text(contact.shortName), verifiedName: text(contact.verifiedName) };
    }).filter(contact => /@(?:c\.us|lid)$/.test(contact.id._serialized));
}

function createContactCache({ load, fallback, ttlMs = 900000, timeoutMs = 4000, retryMs = 60000, now = Date.now, warn = console.warn }) {
    let value = null, savedAt = 0, retryAt = 0, pending = null, fallbackPending = null, error = null, source = 'none';
    function refresh() {
        if (pending || now() < retryAt) return pending;
        pending = Promise.resolve().then(load).then(contacts => {
            if (!Array.isArray(contacts)) throw new Error('Invalid contact snapshot');
            value = contacts; savedAt = now(); source = 'browser'; retryAt = 0; error = null;
            return contacts;
        }).catch(failure => {
            error = String(failure.message || failure); retryAt = now() + retryMs;
            warn('Q20 contact refresh deferred: ' + error);
            return null;
        }).finally(() => { pending = null; });
        return pending;
    }
    async function stored() {
        if (!fallbackPending) fallbackPending = Promise.resolve().then(fallback).finally(() => { fallbackPending = null; });
        return deadline(fallbackPending, timeoutMs, 'Stored contact lookup');
    }
    async function get() {
        if (value !== null && source === 'browser' && now() - savedAt < ttlMs) return value;
        if(value===null && now()<retryAt)throw Object.assign(new Error('WhatsApp browser contacts temporarily unavailable; retry after backoff'),{status:503});
        const began=Date.now();
        const request = refresh();
        if (value !== null) return value; // stale-while-refresh, never stall a page
        try {
            const persisted = await stored();
            // A background refresh may have completed while SQLite was reading.
            if (value !== null) return value;
            if (persisted && persisted.length) { value = persisted; source = 'stored'; return value; }
        } catch (_) { }
        if (request) {
            try { await deadline(request, Math.max(1,timeoutMs-(Date.now()-began)), 'WhatsApp contact refresh'); }
            catch (failure) { error = failure.message; retryAt=now()+retryMs; }
        }
        if (value !== null) return value;
        throw Object.assign(new Error('WhatsApp browser contacts unavailable; retry later. ' + (error || 'Refresh pending')), { status: 503 });
    }
    function status() { return { source, stale: source !== 'browser' || now() - savedAt >= ttlMs, refreshing: !!pending,
        last_success_at: savedAt || null, retry_after_ms: Math.max(0, retryAt - now()), error }; }
    return { get, status };
}

module.exports = { createContactCache, readCompactContacts, deadline };
