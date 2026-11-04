'use strict';

const { db } = require('./connect');
const fs = require('fs');
const path = require('path');
const { richMessage } = require('./q20RichMessages');

function createHistorySupport(client) {
    let running = false;
    let restored = false;
    const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
    async function bounded(promise, label, timeout = 25000) { let timer; try { return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(label+' timed out; retry will resume')),timeout)})]); } finally {clearTimeout(timer)} }
    let state = {
        running: false, completed: false, phase: 'idle',
        conversations_total: 0, conversations_done: 0,
        messages_seen: 0, messages_imported: 0, errors: 0,
        started_at: null, completed_at: null,
        current_chat_jid: '', completed_chat_jids: []
    };

    async function loadState() {
        try {
            const row = await db().get("SELECT value FROM sync_state WHERE key='whatsapp_history_import'");
            if (row && row.value) { state = JSON.parse(row.value); state.running = running; }
            restored = true;
        } catch (_) { }
    }

    async function saveState() {
        try {
            await db().run("INSERT OR REPLACE INTO sync_state(key,value) VALUES('whatsapp_history_import',?)", [JSON.stringify(state)]);
        } catch (error) { console.error('Q20 history status save failed:', error.message); }
    }

    function getHistoryImportStatus() { return Object.assign({}, state); }
    async function restoreHistoryImportState() { if(!restored && !running) await loadState(); return getHistoryImportStatus(); }

    async function fetchInitialBatch(chatJid, offset, limit) {
        return bounded(client.pupPage.evaluate(async (id, start, count) => {
            const chat = await window.WWebJS.getChat(id, { getAsModel: false });
            const messages = chat.msgs.getModelsArray().filter(message => !message.isNotification);
            return {
                total: messages.length,
                messages: messages.slice(start, start + count).map(message => window.WWebJS.getMessageModel(message))
            };
        }, chatJid, offset, limit), "Reading loaded history");
    }

    async function fetchEarlierBatch(chatJid) {
        return bounded(client.pupPage.evaluate(async id => {
            const chat = await window.WWebJS.getChat(id, { getAsModel: false });
            // Keep one loader per chat even when a slow browser request outlives a timeout.
            const pending = window.__q20HistoryPages || (window.__q20HistoryPages = {});
            if (!pending[id]) pending[id] = Promise.resolve(window.require('WAWebChatLoadMessages').loadEarlierMsgs({ chat }));
            try {
                const loaded = await pending[id];
                return (loaded || []).filter(message => !message.isNotification)
                    .map(message => window.WWebJS.getMessageModel(message));
            } finally { delete pending[id]; }
        }, chatJid), 'Loading older history');
    }

    async function requestPhoneHistory(chatJid) {
        if (typeof client.syncHistory !== 'function') return false;
        const key = 'q20_phone_history:' + chatJid;
        const previous = await db().get('SELECT value FROM sync_state WHERE key=?',[key]);
        const requestedAt = previous ? Number(previous.value) : 0;
        if (Date.now() - requestedAt < 6 * 60 * 60 * 1000) return false;
        const requested = await bounded(client.syncHistory(chatJid), 'Requesting phone history');
        if (requested) {
            await db().run('INSERT OR REPLACE INTO sync_state(key,value) VALUES(?,?)',[key,String(Date.now())]);
            state.phone_requests = (state.phone_requests || 0) + 1;
        }
        return !!requested;
    }

    async function downloadMessageMedia(whatsappMessageId) {
        if (!client || !client.info) throw new Error('WhatsApp session is not connected');
        const row = await db().get('SELECT _id,chat_type,media_original_filename FROM messages WHERE whatsapp_message_id=?', [whatsappMessageId]);
        if (!row) throw new Error('Message is not present in the server database');
        const message = await client.getMessageById(whatsappMessageId);
        if (!message || !message.hasMedia) throw new Error('WhatsApp Web no longer exposes this media');
        const media = await message.downloadMedia();
        if (!media || !media.data) throw new Error('WhatsApp Web returned no media data');

        const mimetype = String(media.mimetype || 'application/octet-stream').split(';')[0].toLowerCase();
        const extensions = {
            'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp',
            'image/gif': '.gif', 'video/mp4': '.mp4', 'audio/ogg': '.ogg',
            'audio/mpeg': '.mp3', 'audio/3gpp': '.3gp', 'audio/amr': '.amr',
            'application/pdf': '.pdf', 'text/plain': '.txt', 'text/csv': '.csv',
            'application/msword': '.doc',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document': '.docx',
            'application/vnd.ms-excel': '.xls',
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': '.xlsx',
            'application/vnd.ms-powerpoint': '.ppt',
            'application/vnd.openxmlformats-officedocument.presentationml.presentation': '.pptx',
            'application/zip': '.zip'
        };
        const originalExtension = path.extname(String(media.filename || message._data && message._data.filename || '')).toLowerCase();
        const extension = extensions[mimetype] || (/^\.[a-z0-9]{1,10}$/.test(originalExtension) ? originalExtension : '.bin');
        const bytes = Buffer.from(media.data, 'base64');
        const mediaDir = path.join(__dirname, 'media');
        fs.mkdirSync(mediaDir, { recursive: true });
        const filename = String(row._id) + extension;
        const outputPath = path.join(mediaDir, filename);
        fs.writeFileSync(outputPath, bytes);

        let conversion = 'ready';
        if (mimetype === 'video/mp4') {
            const target = path.join(mediaDir, String(row._id) + '.3gp');
            try {
                const ffmpeg = require('fluent-ffmpeg');
                await new Promise((resolve, reject) => ffmpeg(outputPath)
                    .videoFilters('scale=352:288:force_original_aspect_ratio=decrease,pad=352:288:(ow-iw)/2:(oh-ih)/2,setsar=1')
                    .outputOptions(['-acodec aac', '-strict experimental', '-ac 1', '-ar 8000', '-ab 24k'])
                    .output(target).on('end', resolve).on('error', reject).run());
                conversion = 'ready';
            } catch (error) {
                conversion = '3gp_conversion_failed';
                console.warn('Q20 video conversion failed; MP4 remains available:', error.message || error);
            }
        }
        await db().run(`UPDATE messages SET media_filename=?,media_original_filename=?,media_mimetype=?,media_filesize=?,media_conversion_state=? WHERE _id=?`,
            [filename, String(media.filename || message._data && message._data.filename || row.media_original_filename || ''), mimetype, bytes.length, conversion, row._id]);
        return { filename, mimetype, size: bytes.length, conversion };
    }

    async function importMessages(chat, chatJid, batch, ownerJid) {
        const connection = db();
        let inserted = 0;
        for (const message of batch) {
            state.messages_seen++;
            const waId = message && message.id && message.id._serialized;
            if (!message || !waId) continue;

            const fromMe = Boolean(message.fromMe);
            const sender = String(message.from || (fromMe ? ownerJid : chatJid));
            const receiver = String(message.to || (fromMe ? chatJid : ownerJid));
            const type = String(message.type || 'chat');
            const text = String(message.body || message.pollName || '') || `${type} received`;
            const timestamp = new Date((Number(message.timestamp) || 0) * 1000)
                .toISOString().replace('T', ' ').slice(0, 19);
            const participant = String(message.author || (fromMe ? ownerJid : sender));
            const senderName = fromMe ? (client.info && client.info.pushname || 'Me')
                : String(message._data && message._data.notifyName || participant);
            const mime = String(message.mimetype || message._data && message._data.mimetype || '');
            const originalFilename = String(message.filename || message._data && message._data.filename || '');
            const size = Number(message.size || message._data && message._data.size || 0);

            const stored = await connection.run(`INSERT OR IGNORE INTO messages
                (sender,receiver,message,status,sender_name,chat_type,device_type,timestamp,
                 whatsapp_message_id,chat_jid,participant_jid,from_me,media_filename,media_original_filename,media_mimetype,media_filesize,media_conversion_state,reply_to_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, [
                sender, receiver, text, fromMe ? 1 : 0, senderName, type, 'whatsapp-web-history', timestamp,
                waId, chatJid, participant, fromMe ? 1 : 0, '', originalFilename, mime, size,
                message.hasMedia ? 'available-remotely' : '',
                message._data && message._data.quotedStanzaID || ''
            ]);
            if (!stored || stored.changes !== 1) continue;
            await connection.run('UPDATE messages SET rich_json=? WHERE whatsapp_message_id=?', [JSON.stringify(richMessage(message)), waId]);
            inserted++;

            await connection.run('INSERT OR REPLACE INTO q20_conversations(chat_jid,display_name,chat_type,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)',
                [chatJid, String(chat._q20DisplayName || chat.name || chatJid), chat.isGroup ? 'group' : 'chat']);

            await connection.run(`INSERT INTO chats
                (sender,receiver,message,status,sender_name,chat_type,device_type,timestamp)
                VALUES(?,?,?,?,?,?,?,?)`, [
                fromMe ? ownerJid : chatJid,
                fromMe ? chatJid : ownerJid,
                text,
                fromMe ? 1 : 0,
                chat.name || senderName,
                type,
                'whatsapp-web-history',
                timestamp
            ]);
        }
        state.messages_imported += inserted;
        await saveState();
    }

    async function importChatHistory(chat, chatJid, ownerJid) {
        const batchSize = 200;
        let offset = 0;
        let initialTotal = -1;
        while (initialTotal < 0 || offset < initialTotal) {
            const page = await fetchInitialBatch(chatJid, offset, batchSize);
            initialTotal = Number(page && page.total) || 0;
            const messages = page && Array.isArray(page.messages) ? page.messages : [];
            if (!messages.length) break;
            await importMessages(chat, chatJid, messages, ownerJid);
            offset += messages.length;
        }

        // whatsapp-web.js 1.34.7 exposes no message offset/cursor. Use its
        // own load-earlier primitive one page at a time instead of calling
        // fetchMessages({}) and materializing an entire large chat in Node.
        // A restarted import replays the currently loaded pages safely because
        // whatsapp_message_id has a unique partial index and inserts are IGNORE.
        // Request missing phone history before a browser loader can wait on that transfer.
        const requested = await requestPhoneHistory(chatJid);
        let lastPage = '';
        for (;;) {
            const messages = await fetchEarlierBatch(chatJid);
            if (!Array.isArray(messages) || messages.length === 0) break;
            const signature = messages.map(m => m.id && m.id._serialized || '').join('|');
            if (signature === lastPage) throw new Error('WhatsApp repeated an older-history page; retry will resume');
            lastPage = signature;
            for (let start = 0; start < messages.length; start += batchSize) {
                await importMessages(chat, chatJid, messages.slice(start, start + batchSize), ownerJid);
            }
            await delay(25); // Yield to live messaging and the working call bridge.
        }
        // syncHistory is WhatsApp's linked-device request, not a cloud archive download.

        const transfer = await bounded(client.pupPage.evaluate(async id => {
            const chat = await window.WWebJS.getChat(id, { getAsModel: false });
            return chat.endOfHistoryTransferType == null ? null : chat.endOfHistoryTransferType;
        }, chatJid), 'Reading history transfer state');
        return transfer !== 0 && !(requested && transfer == null);
    }

    function isPhoneOrJid(value, chatJid) {
        const text = String(value || '').trim();
        return !text || text === chatJid || /@[^\s]+$/.test(text)
            || /^\+?[0-9][0-9\s().-]{5,}$/.test(text)
            || /^(whatsapp contact|whatsapp group|unknown contact)$/i.test(text);
    }

    function contactDisplayName(contact, jid) {
        for (const value of [contact && contact.name, contact && contact._data && contact._data.name,contact && contact._data && contact._data.formattedName,contact && contact.pushname,
            contact && contact.shortName, contact && contact.verifiedName,
            contact && contact._data && contact._data.verifiedName, contact && contact._data && contact._data.notifyName]) {
            const name = String(value || '').trim();
            if (!isPhoneOrJid(name, jid)) return name;
        }
        return '';
    }

    async function resolveChatDisplayName(chat, chatJid, contactIndex) {
        const candidate = String(chat && chat.name || '').trim();
        const isGroup = !!(chat && chat.isGroup) || /@g\.us$/i.test(chatJid);
        const isChannel = /@newsletter$/i.test(chatJid);
        // A saved address-book name takes precedence over a profile/chat push name.
        if(!isGroup && !isChannel && contactIndex) {
            let contact=contactIndex.get(chatJid);
            if(!contact && /@lid$/.test(chatJid)) {
                const mapping=await db().get('SELECT phone_jid FROM q20_jid_mappings WHERE lid_jid=?',[chatJid]);
                if(mapping)contact=contactIndex.get(mapping.phone_jid);
            }
            const saved=String(contact&&(contact.name||contact._data&&contact._data.name)||'').trim();
            if(!isPhoneOrJid(saved,chatJid))return saved;
        }
        if (!isPhoneOrJid(candidate, chatJid)) return candidate;
        if (isChannel) return 'Channel';
        if (isGroup) {
            const subject = String(chat && chat.groupMetadata && chat.groupMetadata.subject || '').trim();
            return !isPhoneOrJid(subject, chatJid) ? subject : 'WhatsApp group';
        }
        const contactJids = [chatJid];
        if (chat && typeof chat.getContact === 'function') {
            try { const name = contactDisplayName(await chat.getContact(), chatJid); if (name) return name; } catch (_) { }
        }
        if (/@lid$/i.test(chatJid) && typeof client.getContactLidAndPhone === 'function') {
            try {
                const mappings = await client.getContactLidAndPhone([chatJid]);
                for (const mapping of (mappings || [])) {
                    if (!mapping.lid || !mapping.pn) continue;
                    const lid = String(mapping.lid);
                    const phone = String(mapping.pn);
                    await db().run('INSERT OR REPLACE INTO q20_jid_mappings(lid_jid,phone_jid,updated_at) VALUES(?,?,CURRENT_TIMESTAMP)', [lid, phone]);
                    contactJids.unshift(phone);
                }
            } catch (error) {
                console.warn('Could not resolve history chat LID:', error.message || error);
            }
        }
        for (const jid of contactJids) {
            const cached = contactIndex && contactIndex.get(jid);
            const resolved = contactDisplayName(cached, jid);
            if (resolved) return resolved;
            if (typeof client.getContactById === 'function') {
                try {
                    const contact = await client.getContactById(jid);
                    const liveName = contactDisplayName(contact, jid);
                    if (liveName) return liveName;
                } catch (_) { }
            }
        }
        return 'WhatsApp contact';
    }

    async function refreshConversationMetadata() {
        if (!client || !client.info) return 0;
        const chats = await bounded(client.getChats(), "Listing conversations", 60000);
        let contacts = [];
        try { contacts = await client.getContacts(); }
        catch (error) { console.warn('Could not load WhatsApp contacts for name resolution:', error.message || error); }
        const contactIndex = new Map();
        for (const contact of contacts || []) {
            const contactJid = String(contact && contact.id && contact.id._serialized || '');
            if (contactJid) contactIndex.set(contactJid, contact);
        }
        if(typeof client.getContactLidAndPhone==='function'){
            const lids=(chats||[]).map(chat=>String(chat&&chat.id&&chat.id._serialized||'')).filter(jid=>jid.endsWith('@lid'));
            for(let start=0;start<lids.length;start+=100)try{
                const mappings=await client.getContactLidAndPhone(lids.slice(start,start+100));
                for(const mapping of mappings||[]){
                    if(!mapping.lid||!mapping.pn)continue;
                    await db().run('INSERT OR REPLACE INTO q20_jid_mappings(lid_jid,phone_jid,updated_at) VALUES(?,?,CURRENT_TIMESTAMP)',[mapping.lid,mapping.pn]);
                    if(contactIndex.has(mapping.pn))contactIndex.set(mapping.lid,contactIndex.get(mapping.pn));
                }
            }catch(error){console.warn('Q20 bulk contact identity lookup:',error.message||error);}
        }
        let updated = 0;
        for (const chat of chats || []) {
            const chatJid = String(chat && chat.id && chat.id._serialized || '');
            if (!chatJid) continue;
            let displayName = await resolveChatDisplayName(chat, chatJid, contactIndex);
            const saved = await db().get('SELECT display_name FROM q20_conversations WHERE chat_jid=?', [chatJid]);
            if (isPhoneOrJid(displayName, chatJid) && saved
                && !isPhoneOrJid(saved.display_name, chatJid)) displayName = saved.display_name;
            await db().run('INSERT OR REPLACE INTO q20_conversations(chat_jid,display_name,chat_type,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)',
                [chatJid, displayName, chat.isGroup ? 'group' : 'chat']);
            updated++;
        }
        return updated;
    }

    async function runImport(perChatLimit) {
        const chats = await client.getChats();
        state.conversations_total = Math.max(state.conversations_total || 0, chats.length);
        state.phase = 'importing_available_whatsapp_history';
        await saveState();
        const ownerJid = `${client.info.wid.user}@c.us`;
        const completed = new Set(state.completed_chat_jids || []);
        state.waiting_chats = 0;
        state.last_error = null;
        for (const chat of chats) {
            const chatJid = String(chat.id && chat.id._serialized || '');
            if (!chatJid) { state.errors++; continue; }
            const savedName = await db().get('SELECT display_name FROM q20_conversations WHERE chat_jid=?',[chatJid]);
            const displayName = savedName && !isPhoneOrJid(savedName.display_name,chatJid) ? savedName.display_name : String(chat.name || chatJid);
            chat._q20DisplayName = displayName;
            await db().run('INSERT OR REPLACE INTO q20_conversations(chat_jid,display_name,chat_type,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)',
                [chatJid, displayName, chat.isGroup ? 'group' : 'chat']);
            if (completed.has(chatJid)) continue;
            state.current_chat_jid = chatJid;
            await saveState();
            try {
                if (perChatLimit > 0) {
                    // Explicitly limited opt-in import for operators who need a
                    // cap; normal import walks all history the session exposes.
                    const batch = await chat.fetchMessages({ limit: perChatLimit });
                    for (let start = 0; start < batch.length; start += 200) {
                        await importMessages(chat, chatJid, batch.slice(start, start + 200), ownerJid);
                    }
                } else {
                    const availableLoaded = await importChatHistory(chat, chatJid, ownerJid);
                    if (!availableLoaded) { state.waiting_chats++; await saveState(); continue; }
                }
                completed.add(chatJid);
                state.completed_chat_jids = Array.from(completed);
                state.conversations_done++;
                state.current_chat_jid = '';
                await saveState();
            } catch (error) {
                state.errors++;
                state.last_error = String(error.message || error);
                console.warn('Q20 history import skipped a chat:', chatJid, error.message || error);
                await saveState();
            }
        }
        state.running = false;
        state.completed = state.errors === 0 && state.waiting_chats === 0;
        state.phase = state.errors ? 'completed_with_errors' : state.waiting_chats ? 'waiting_for_phone' : 'available_history_imported';
        state.scope = 'History available to this linked WhatsApp Web session; older phone history is requested where supported.';
        state.completed_at = new Date().toISOString();
        await saveState();
    }

    async function startHistoryImport(perChatLimit) {
        if (running) return false;
        if (!client || !client.info) throw new Error('WhatsApp session is not connected');
        if(!restored) await loadState();
        if(running) return false;
        running = true;
        const previous = state;
        const resume = !previous.completed && (
            (previous.completed_chat_jids || []).length > 0 ||
            !!previous.current_chat_jid ||
            previous.phase === 'completed_with_errors' ||
            previous.phase === 'failed'
        );
        state = resume ? Object.assign({}, previous, {
            running: true, phase: 'resuming', completed_at: null, errors: 0
        }) : {
            running: true, completed: false, phase: 'starting',
            conversations_total: 0, conversations_done: 0,
            messages_seen: 0, messages_imported: 0, errors: 0,
            started_at: new Date().toISOString(), completed_at: null,
            current_chat_jid: '', completed_chat_jids: []
        };
        await saveState();
        setImmediate(() => runImport(Number(perChatLimit) || 0)
            .catch(async error => {
                state.running = false; state.phase = 'failed'; state.errors++;
                state.error = error.message || String(error);
                await saveState();
            })
            .finally(() => { running = false; }));
        return true;
    }

    return { startHistoryImport, getHistoryImportStatus, restoreHistoryImportState, downloadMessageMedia, refreshConversationMetadata };
}

module.exports = createHistorySupport;
