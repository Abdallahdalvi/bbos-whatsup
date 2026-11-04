'use strict';

const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const router = express.Router();
const { db } = require('./connect');
const { getStatus, loginUser, getContacts, sendMessage, createCallInvite, uploadMedia, getHistoryImportStatus, restoreHistoryImportState, startHistoryImport, downloadMessageMedia, refreshConversationMetadata, getWhatsAppClient } = require('./WhatsappClient');
const createCallSupport = require('./q20CallSupport');
const q20Calls = createCallSupport(getWhatsAppClient());

const MEDIA_ROOT = path.join(__dirname, 'media');
const upload = multer({
    storage: multer.diskStorage({
        destination: (req, file, callback) => callback(null, MEDIA_ROOT),
        filename: (req, file, callback) => {
            const extension = path.extname(file.originalname || '').toLowerCase();
            const safeExtension = /^\.[a-z0-9]{1,10}$/.test(extension) ? extension : '.upload';
            callback(null, 'q20-upload-' + crypto.randomBytes(16).toString('hex') + safeExtension);
        }
    }),
    limits: { fileSize: 512 * 1024 * 1024 }
});
let lastChatMetadataRefresh = 0;
let chatMetadataRefresh = null;
let documentMetadataQueue = Promise.resolve();
const documentMetadataAttempts = new Map();
function repairDocumentMetadata(messages, client) {
    if (!client || typeof client.getMessageById !== 'function') return;
    for (const item of messages.filter(item => item.chat_type === 'document' && !item.media_original_filename && item.whatsapp_message_id).slice(-8)) {
        if (Date.now() - (documentMetadataAttempts.get(item._id) || 0) < 300000) continue;
        documentMetadataAttempts.set(item._id, Date.now());
        if (documentMetadataAttempts.size > 256) documentMetadataAttempts.delete(documentMetadataAttempts.keys().next().value);
        documentMetadataQueue = documentMetadataQueue.catch(() => {}).then(async () => {
            const live = await client.getMessageById(item.whatsapp_message_id);
            const data = live && (live._data || live);
            const name = String(live && (live.filename || live.title) || data && (data.filename || data.title) || '');
            if (!name) return;
            const rich = require('./q20RichMessages').richMessage(live);
            await db().run('UPDATE messages SET media_original_filename=?,rich_json=? WHERE _id=?', [path.basename(name.replace(/\\/g, '/')), JSON.stringify(rich), item._id]);
        }).catch(error => console.warn('Q20 document metadata lookup:', error.message || error));
    }
}

function requireToken(req, res, next) {
    const expected = require('./q20Config').apiToken();
    const supplied = String(req.get('Authorization') || '').replace(/^Bearer\s+/i, '');
    if (!expected) return res.status(503).json({ error: 'Q20_API_TOKEN is not configured on the server' });
    const a = Buffer.from(expected);
    const b = Buffer.from(supplied);
    if (!supplied || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
        return res.status(401).json({ error: 'Unauthorized' });
    }
    next();
}

function getAccount() {
    const status = getStatus('');
    if (!status.isAuthenticated) return { status, jid: '' };
    return { status, jid: String(status.user) + '@c.us' };
}

function limitValue(value) {
    const parsed = parseInt(value, 10);
    return Math.max(1, Math.min(200, isFinite(parsed) ? parsed : 100));
}

function isUsableDisplayName(value, jid) {
    const name = String(value || '').trim();
    if (!name || name === jid || /@[^\s]+$/.test(name)
        || /^\+?[0-9][0-9\s().-]{5,}$/.test(name)
        || /^(whatsapp contact|whatsapp group|unknown contact)$/i.test(name)) return false;
    return true;
}

function fallbackChatName(jid, phoneJid) {
    if (/@newsletter$/i.test(jid)) return 'Channel';
    if (/@g\.us$/i.test(jid)) return 'WhatsApp group';
    const phone = String(phoneJid || '').replace(/@c\.us$/i, '');
    if (phone && !/@/.test(phone)) return 'WhatsApp contact';
    return 'WhatsApp contact';
}

function normalizedMessageSql() {
    return `SELECT m.*,
        CASE
          WHEN COALESCE(m.chat_jid,'') <> '' THEN m.chat_jid
          WHEN m.sender LIKE '%@g.us' THEN m.sender
          WHEN m.receiver LIKE '%@g.us' THEN m.receiver
          WHEN m.sender = ? THEN m.receiver
          ELSE m.sender
        END AS api_chat_jid,
        CASE WHEN m.sender = ? OR m.from_me = 1 THEN 1 ELSE 0 END AS api_from_me,
        COALESCE(NULLIF(m.media_mimetype,''), '') AS api_mimetype,
        COALESCE(CAST(strftime('%s',m.timestamp) AS INTEGER) * 1000,0) AS api_timestamp_ms,
        COALESCE(NULLIF(NULLIF(NULLIF(c.display_name,'WhatsApp contact'),'Unknown contact'),''),
            CASE WHEN m.from_me=0 THEN m.sender_name ELSE '' END,'') AS api_chat_name,
        c.display_name AS resolved_name,
        (SELECT phone_jid FROM q20_jid_mappings lm WHERE lm.lid_jid = CASE
          WHEN COALESCE(m.chat_jid,'') <> '' THEN m.chat_jid
          WHEN m.sender LIKE '%@g.us' THEN m.sender
          WHEN m.receiver LIKE '%@g.us' THEN m.receiver
          WHEN m.sender = ? THEN m.receiver
          ELSE m.sender END LIMIT 1) AS api_phone_jid
        FROM messages m
        LEFT JOIN q20_conversations c ON c.chat_jid = CASE
          WHEN COALESCE(m.chat_jid,'') <> '' THEN m.chat_jid
          WHEN m.sender LIKE '%@g.us' THEN m.sender
          WHEN m.receiver LIKE '%@g.us' THEN m.receiver
          WHEN m.sender = ? THEN m.receiver
          ELSE m.sender
        END`;
}

function messageJson(row) {
    let rich = {};
    try { rich = JSON.parse(row.rich_json || '{}'); } catch (_) { }
    let filename = row.media_filename || '';
    const type = row.chat_type || 'chat';
    let mime=row.api_mimetype||'';
    if(type==='document' && (!filename || !mime)){
        const original=findMediaSource(row,row._id);
        if(original){filename=path.basename(original);const ext=path.extname(original).toLowerCase();if(!mime)mime=({'.pdf':'application/pdf','.txt':'text/plain','.csv':'text/csv','.doc':'application/msword','.docx':'application/vnd.openxmlformats-officedocument.wordprocessingml.document','.xlsx':'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','.zip':'application/zip'})[ext]||'application/octet-stream';}
    }
    if (!filename && (type === 'image' || type === 'video' || type === 'gif' || type === 'audio' || type === 'ptt' || type === 'document')) {
        const extension = type === 'video' || type === 'gif' ? '.mp4' : (type === 'image' ? '.jpg' : (type === 'document' ? '' : '.ogg'));
        filename = String(row._id) + extension;
    }
    let chatName = row.api_chat_name || '';
    if (!isUsableDisplayName(chatName, row.api_chat_jid)) {
        chatName = fallbackChatName(String(row.api_chat_jid || ''), row.api_phone_jid);
    }
    return {
        _id: row._id,
        rich: rich,
        whatsapp_message_id: row.whatsapp_message_id || '',
        chat_jid: row.api_chat_jid || row.chat_jid || '',
        chat_name: !row.resolved_name && Number(row.api_from_me) === 1 && row.api_chat_name === row.sender_name
            ? fallbackChatName(String(row.api_chat_jid || ''), row.api_phone_jid) : (chatName || fallbackChatName(row.api_chat_jid, row.api_phone_jid)),
        sender: row.sender || '', receiver: row.receiver || '',
        participant_jid: row.participant_jid || '',
        message: row.message || '', chat_type: type,
        timestamp_ms: row.api_timestamp_ms || 0,
        createdAt: row.timestamp || '', status: row.status,
        from_me: Number(row.api_from_me || 0) === 1,
        sender_name: row.sender_name || '',
        media_filename: filename,
        media_original_filename: row.media_original_filename || '',
        media_mimetype: mime,
        media_filesize: row.media_filesize || 0,
        media_conversion_state: row.media_conversion_state || '',
        media_download_status: filename ? 'remote' : 'none',
        reply_to_id: row.reply_to_id || '', link_url: row.link_url || ''
    };
}

async function readMessages(accountJid, afterId, limit, snapshotMax) {
    const dbh = db();
    const query = normalizedMessageSql();
    const rows = await dbh.all(`${query}
        WHERE m._id > ? AND m._id <= ?
        ORDER BY m._id ASC LIMIT ?`, [accountJid, accountJid, accountJid, accountJid, afterId, snapshotMax, limit + 1]);
    const hasMore = rows.length > limit;
    const selected = hasMore ? rows.slice(0, limit) : rows;
    return { messages: selected.map(messageJson), hasMore };
}

router.use(requireToken);

const clientDiagnosticFile=path.join(__dirname,'data','q20-client-diagnostics.json');
router.get('/client/diagnostics',(req,res)=>{
    try{return res.json({reports:fs.existsSync(clientDiagnosticFile)?JSON.parse(fs.readFileSync(clientDiagnosticFile,'utf8')):[]});}
    catch(_){return res.json({reports:[]});}
});
router.post('/client/diagnostics',(req,res)=>{
    const body=req.body||{}, report={at:new Date().toISOString()};
    for(const key of ['event','version','sdk','model','screen','stack'])report[key]=String(body[key]||'').slice(0,key==='stack'?8000:200);
    let reports=[];try{reports=JSON.parse(fs.readFileSync(clientDiagnosticFile,'utf8'))}catch(_){}
    reports.push(report);reports=reports.slice(-10);
    try{fs.mkdirSync(path.dirname(clientDiagnosticFile),{recursive:true});fs.writeFileSync(clientDiagnosticFile,JSON.stringify(reports),{mode:0o600});return res.json({recorded:true});}
    catch(error){return res.status(500).json({error:'Could not retain client diagnostics'});}
});

function findMediaSource(row,id) {
    const type=row&&row.chat_type||'';
    const extensions=type==='video'||type==='gif'?['mp4','3gp','gif']:type==='image'||type==='sticker'?['jpg','jpeg','png','webp','gif']:type==='audio'||type==='ptt'?['ogg','mp3','3gp','amr','m4a','aac']:['pdf','docx','doc','xlsx','xls','pptx','ppt','txt','csv','zip','bin'];
    const names=[];
    if(type==='video'||type==='gif')names.push(id+'.mp4');
    if(row&&row.media_filename&&path.basename(row.media_filename)===row.media_filename)names.push(row.media_filename);
    for(const ext of extensions)names.push(id+'.'+ext);
    return names.map(name=>path.join(MEDIA_ROOT,name)).find(file=>fs.existsSync(file)&&fs.statSync(file).isFile()&&fs.statSync(file).size>0);
}

router.get('/health', (req, res) => {
    const account = getAccount();
    const client = getWhatsAppClient();
    res.json({ ok: true, connected: account.status.isAuthenticated, server_version: '0.18.0',
        capabilities: { direct_voice_calls: typeof client.call === 'function',
            call_status: typeof client.getActiveCall === 'function', media_download: true, q20_video_copy: true } });
});

router.get('/account', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ connected: false });
        const result = await loginUser(account.status.user);
        return res.status(result.status).json({ connected: true, account: result.data });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/chats', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        if (Date.now() - lastChatMetadataRefresh > 5 * 60 * 1000 && !chatMetadataRefresh) {
            chatMetadataRefresh = Promise.resolve(refreshConversationMetadata())
                .then(() => { lastChatMetadataRefresh = Date.now(); })
                .catch(error => console.warn('Q20 chat-name refresh failed:', error.message || error))
                .finally(() => { chatMetadataRefresh = null; });
        }
        // Serve cached names immediately while the native metadata refresh finishes.
        const query = normalizedMessageSql();
        const rows = await db().all(`WITH normalized AS (${query}), ranked AS
            (SELECT n.*,ROW_NUMBER() OVER(PARTITION BY api_chat_jid ORDER BY api_timestamp_ms DESC,_id DESC) AS chat_rank FROM normalized n)
            SELECT * FROM ranked WHERE chat_rank=1 ORDER BY api_timestamp_ms DESC,_id DESC`, [account.jid, account.jid, account.jid, account.jid]);
        const chats = rows.map(row => {
            const jid = String(row.api_chat_jid || '');
            const isGroup = jid.endsWith('@g.us');
            const rawName = String(row.api_chat_name || '');
            const hasName = isUsableDisplayName(rawName, jid)
                && !(!row.resolved_name && Number(row.api_from_me) === 1 && rawName === row.sender_name);
            let name = hasName ? rawName : fallbackChatName(jid, row.api_phone_jid);
            return {
                chat_jid: jid,
                name: name,
                is_group: isGroup,
                is_newsletter: /@newsletter$/i.test(jid),
                phone_jid: row.api_phone_jid || '',
                last_message: row.message || '',
                last_type: row.chat_type || 'chat',
                timestamp_ms: row.api_timestamp_ms || 0,
                status: row.status
            };
        });
        const known = await db().all('SELECT chat_jid,display_name,chat_type FROM q20_conversations');
        const seen = new Set(chats.map(chat=>chat.chat_jid));
        for(const item of known) {
            const jid=item.chat_jid;
            if(!jid || seen.has(jid))continue;
            chats.push({chat_jid:jid,name:item.display_name||fallbackChatName(jid,''),is_group:jid.endsWith('@g.us'),is_newsletter:jid.endsWith('@newsletter'),phone_jid:'',last_message:'',last_type:'chat',timestamp_ms:0,status:0});
        }
        const groupOnly = req.query.type === 'groups';
        const result = groupOnly ? chats.filter(chat => chat.is_group) : chats;
        return res.json({ chats: result, total: result.length });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/contacts', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const limit = limitValue(req.query.limit);
        const page = Math.max(0, parseInt(req.query.page || '0', 10) || 0);
        const result = await getContacts(account.status.user, String(req.query.search || ''), page, limit);
        if (result.status !== 200) return res.status(result.status).json(result.data);
        const data = result.data || {};
        const count = Number(data.count || 0);
        const contacts = data.contacts || [];
        for (const contact of contacts) {
            if (contact && contact.id && isUsableDisplayName(contact.name, contact.id)) {
                await db().run('INSERT OR REPLACE INTO q20_conversations(chat_jid,display_name,chat_type,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)',
                    [String(contact.id), String(contact.name), 'chat']);
                await db().run(`UPDATE q20_conversations SET display_name=? WHERE chat_jid IN
                    (SELECT lid_jid FROM q20_jid_mappings WHERE phone_jid=?)`, [contact.name, contact.id]);
            }
        }
        return res.json({ contacts, count, page, has_more: ((page + 1) * limit) < count, contact_cache: data.contact_cache });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/sync/bootstrap', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const dbh = db();
        const totals = await dbh.get('SELECT COUNT(*) AS total, COALESCE(MAX(_id),0) AS max_id FROM messages');
        const snapshots = await dbh.get('SELECT COUNT(DISTINCT COALESCE(NULLIF(chat_jid,\'\'), CASE WHEN sender LIKE \'%@g.us\' THEN sender WHEN receiver LIKE \'%@g.us\' THEN receiver WHEN sender=? THEN receiver ELSE sender END)) AS total FROM messages', [account.jid]);
        const snapshotMax = Math.max(0, parseInt(req.query.snapshot_max_id || totals.max_id, 10) || 0);
        const afterId = Math.max(0, parseInt(req.query.after_id || '0', 10) || 0);
        const limit = limitValue(req.query.limit);
        const page = await readMessages(account.jid, afterId, limit, snapshotMax);
        const nextId = page.messages.length ? page.messages[page.messages.length - 1]._id : afterId;
        return res.json({
            account: { user: account.status.user, pushname: account.status.pushname, platform: account.status.platform },
            snapshot_max_id: snapshotMax, total_messages: totals.total,
            total_chats: snapshots.total, messages: page.messages,
            next_after_id: nextId, has_more: page.hasMore,
            history_limit: 'All rows stored in the server SQLite messages table; no WhatsApp cloud archive is implied.'
        });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/sync/updates', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const afterId = Math.max(0, parseInt(req.query.after_id || '0', 10) || 0);
        const totals = await db().get('SELECT COALESCE(MAX(_id),0) AS max_id FROM messages');
        const page = await readMessages(account.jid, afterId, limitValue(req.query.limit), totals.max_id);
        const nextId = page.messages.length ? page.messages[page.messages.length - 1]._id : afterId;
        return res.json({ messages: page.messages, next_after_id: nextId, has_more: page.hasMore });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/chats/:chatJid/messages', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const chatJid = decodeURIComponent(req.params.chatJid);
        const aliases = [chatJid];
        if (chatJid.endsWith('@lid')) {
            const mapping = await db().get('SELECT phone_jid FROM q20_jid_mappings WHERE lid_jid=?', [chatJid]);
            if (mapping && mapping.phone_jid) aliases.push(mapping.phone_jid);
        }
        else if(chatJid.endsWith('@c.us')) {
            const mappings=await db().all('SELECT lid_jid FROM q20_jid_mappings WHERE phone_jid=?',[chatJid]);
            for(const mapping of mappings)if(!aliases.includes(mapping.lid_jid))aliases.push(mapping.lid_jid);
        }
        const placeholders = aliases.map(() => '?').join(',');
        const beforeId = Math.max(0, parseInt(req.query.before_id || '9223372036854775807', 10) || 0);
        const limit = limitValue(req.query.limit);
        const chronological = req.query.before_timestamp_ms !== undefined;
        const beforeTime = Math.max(0, Number(req.query.before_timestamp_ms) || Number.MAX_SAFE_INTEGER);
        const cursorSql = chronological ? '(api_timestamp_ms < ? OR (api_timestamp_ms = ? AND m._id < ?))' : 'm._id < ?';
        const cursorArgs = chronological ? [beforeTime,beforeTime,beforeId] : [beforeId];
        const orderSql = chronological ? 'api_timestamp_ms DESC,m._id DESC' : 'm._id DESC';
        const rows = await db().all(`${normalizedMessageSql()}
            WHERE ${cursorSql} AND (
                (m.sender IN (${placeholders}) AND m.receiver=?) OR
                (m.receiver IN (${placeholders}) AND m.sender=?) OR
                m.chat_jid IN (${placeholders})
            ) ORDER BY ${orderSql} LIMIT ?`,
            [account.jid, account.jid, account.jid, account.jid, ...cursorArgs, ...aliases, account.jid, ...aliases, account.jid, ...aliases, limit + 1]);
        const hasMore = rows.length > limit;
        const messages = (hasMore ? rows.slice(0, limit) : rows).map(messageJson).reverse();
        // Repair structured content of legacy rows only when opening that message page.
        const wa = getWhatsAppClient();
        // Metadata lookup runs after this response; opening history never waits on it.
        repairDocumentMetadata(messages, wa);
        for (const item of messages.filter(item => /poll|button|interactive|list/.test(item.chat_type) && !item.rich.poll && !item.rich.actions).slice(-12)) {
            if (!item.whatsapp_message_id || typeof wa.getMessageById !== 'function') continue;
            try {
                const live = await wa.getMessageById(item.whatsapp_message_id);
                if (live) {
                    item.rich = require('./q20RichMessages').richMessage(live);
                    item.message = live.body || live.pollName || item.message;
                    await db().run('UPDATE messages SET rich_json=?,message=? WHERE _id=?', [JSON.stringify(item.rich), item.message, item._id]);
                }
            } catch (_) { }
        }
        return res.json({ messages, has_more: hasMore, next_before_id: messages.length ? messages[0]._id : null, next_before_timestamp_ms: messages.length ? messages[0].timestamp_ms : null });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.post('/polls/:messageId/vote', async (req, res) => {
    try {
        const row = await db().get('SELECT whatsapp_message_id FROM messages WHERE _id=?', [req.params.messageId]);
        if (!row || !row.whatsapp_message_id) return res.status(404).json({ error: 'Poll message not found' });
        const message = await getWhatsAppClient().getMessageById(row.whatsapp_message_id);
        if (!message || !message.pollOptions || typeof message.vote !== 'function') return res.status(409).json({ error: 'This poll is no longer available in WhatsApp Web' });
        const options = req.body.options;
        const available = message.pollOptions.map(option => String(option.name || option));
        if (!Array.isArray(options) || options.some(option => !available.includes(option))
            || (!message.allowMultipleAnswers && options.length > 1)) return res.status(400).json({ error: 'Invalid poll selection' });
        await message.vote(options);
        return res.json({ accepted: true, selected_options: options });
    } catch (error) { return res.status(502).json({ error: error.message || String(error) }); }
});

router.post('/messages', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const chatJid = String(req.body.chat_jid || '');
        const body = String(req.body.message || '');
        if (!chatJid || !body.trim()) return res.status(400).json({ error: 'chat_jid and message are required' });
        if (!/@(?:c\.us|lid|g\.us)$/i.test(chatJid)) return res.status(400).json({ error: 'Invalid WhatsApp chat JID' });
        const result = await sendMessage(account.jid, chatJid, body);
        if (result.status < 200 || result.status >= 300) return res.status(result.status).json(result.data);
        return res.json({ accepted: true, result: result.data });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.post('/calls/link', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const chatJid = String(req.body.chat_jid || '');
        const type = String(req.body.type || 'voice');
        if (!/@(?:c\.us|lid|g\.us)$/i.test(chatJid)) return res.status(400).json({ error: 'Valid chat_jid is required' });
        const result = await createCallInvite(account.jid, chatJid, type);
        return res.json(result);
    } catch (error) {
        console.error('Q20 call invitation failed:', error && error.stack || error);
        return res.status(502).json({
            error: error && error.message || 'Could not create the call invitation',
            stage: error && error.callStage || 'call_link'
        });
    }
});

router.post('/calls/start', async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
        const result = await q20Calls.startVoiceCall(String(req.body.chat_jid || ''));
        return res.status(202).json(result);
    } catch (error) {
        const status = error.code === 'CALL_DEPENDENCY_MISSING' ? 503 : /already active/i.test(error.message || '') ? 409 : (/private WhatsApp/i.test(error.message || '') ? 400 : 502);
        return res.status(status).json({ error: String(error && error.message || error || 'Could not start the WhatsApp voice call'), code: error.code || 'CALL_START_FAILED' });
    }
});

router.post('/calls/confirm', async (req, res) => {
    try { return res.status(202).json(await q20Calls.confirmVoiceCall(String(req.body.request_id || ''))); }
    catch (error) { return res.status(409).json({ error: error.message || 'Could not confirm the call' }); }
});

router.post('/calls/end', async (req, res) => {
    try {
        const ended = await q20Calls.endVoiceCall(String(req.body.call_id || ''), 'Call ended from app');
        return ended ? res.json({ ended: true }) : res.status(404).json({ error: 'No matching active call' });
    } catch (error) { return res.status(502).json({ error: error.message || 'Could not end the call' }); }
});

router.post('/calls/reject', async (req, res) => {
    try {
        const rejected = await q20Calls.rejectVoiceCall(String(req.body.call_id || ''));
        return rejected ? res.json({ rejected: true }) : res.status(404).json({ error: 'No matching incoming call' });
    } catch (error) { return res.status(502).json({ error: error.message || 'Could not reject the call' }); }
});

router.get('/calls/status', (req, res) => res.json(q20Calls.getStatus()));
router.get('/calls/diagnostics', async (req, res) => {
    try { return res.json(await q20Calls.diagnostics()); }
    catch (error) { return res.status(500).json({ error: error.message }); }
});
router.post('/calls/report', (req, res) => {
    q20Calls.reportClientError(String(req.body.message || 'Unspecified device call error').slice(0, 2000));
    return res.json({ recorded: true });
});

// The WebSocket handshake is completed by server.js after token verification.
router.q20Calls = q20Calls;

router.post('/media', upload.single('media'), async (req, res) => {
    try {
        const account = getAccount();
        if (!account.status.isAuthenticated) {
            if (req.file && req.file.path) fs.unlink(req.file.path, () => {});
            return res.status(503).json({ error: 'WhatsApp session is not connected' });
        }
        const chatJid = String(req.body.chat_jid || '');
        if (!chatJid || !/@(?:c\.us|lid|g\.us)$/i.test(chatJid)) {
            if (req.file && req.file.path) fs.unlink(req.file.path, () => {});
            return res.status(400).json({ error: 'Valid chat_jid is required' });
        }
        if (!req.file) return res.status(400).json({ error: 'media file is required' });
        const media = { path: req.file.path, name: req.file.originalname, mimetype: req.file.mimetype, size: req.file.size };
        const options = req.body.voice_note === undefined ? undefined : { voiceNote: req.body.voice_note === 'true' };
        const result = await uploadMedia(media, account.jid, chatJid, req.body.send_as_document === 'true', options);
        return res.status(result.status).json(result.data);
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/media/:messageId', async (req, res) => {
    try {
        const id = parseInt(req.params.messageId, 10);
        if (!Number.isSafeInteger(id) || id < 1) return res.status(400).json({ error: 'Invalid message ID' });
        const row = await db().get('SELECT media_filename,media_original_filename,media_mimetype,whatsapp_message_id,chat_type FROM messages WHERE _id=?', [id]);
        const requestedVariant = req.query.variant === 'original' ? '' : String(req.query.variant || '');
        if (requestedVariant === 'q20-480' || requestedVariant === 'thumbnail') {
            if (!row) return res.status(404).json({ error: 'Message not found' });
            let original = row.media_filename || `${id}.mp4`;
            if (path.basename(original) !== original || original.includes('..')) return res.status(400).json({ error: 'Invalid media filename' });
            let source = findMediaSource(row,id);
            if (!source && row.whatsapp_message_id) {
                const downloaded = await downloadMessageMedia(row.whatsapp_message_id);
                source = path.join(MEDIA_ROOT, path.basename(downloaded.filename));
            }
            if (!source || !fs.existsSync(source)) return res.status(404).json({ error: 'Original media is unavailable' });
            const output = await require('./q20MediaVariants').mediaVariant(source, id, requestedVariant, MEDIA_ROOT);
            return res.download(output);
        }
        const allowedVariants = new Set(['', '3gp', 'mp4', 'jpg', 'jpeg', 'png', 'webp', 'gif', 'ogg', 'mp3', 'pdf', 'txt', 'csv', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'zip']);
        if (!allowedVariants.has(requestedVariant)) return res.status(400).json({ error: 'Unsupported media variant' });
        const ext = requestedVariant ? '.' + requestedVariant : '';
        let filename = requestedVariant ? `${id}${ext}` : (row && row.media_filename ? row.media_filename : `${id}${ext}`);
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(filename) || filename.includes('..')) return res.status(400).json({ error: 'Invalid media filename' });
        let file = path.join(MEDIA_ROOT, filename);
        if(!requestedVariant && !fs.existsSync(file)) {const found=findMediaSource(row,id);if(found){file=found;filename=path.basename(found);}}
        if (!fs.existsSync(file) && row && row.whatsapp_message_id) {
            try {
                const downloaded = await downloadMessageMedia(row.whatsapp_message_id);
                if (!requestedVariant || downloaded.filename === filename || fs.existsSync(file)) filename = downloaded.filename;
                file = path.join(MEDIA_ROOT, filename);
            } catch (downloadError) {
                console.warn('Q20 media retrieval failed:', downloadError.message || downloadError);
            }
        }
        if (!fs.existsSync(file)) return res.status(404).json({ error: 'Media file is not available on the server' });
        const downloadName = !requestedVariant && row && row.media_original_filename
            ? path.basename(row.media_original_filename.replace(/\\/g, '/')).replace(/[\x00-\x1f\x7f]/g, '_') : filename;
        return res.download(file, downloadName, error => {
            if (error && !res.headersSent) res.status(error.code === 'ENOENT' ? 404 : 500).json({ error: 'Media download failed' });
        });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.post('/history/import', async (req, res) => {
    const account = getAccount();
    if (!account.status.isAuthenticated) return res.status(503).json({ error: 'WhatsApp session is not connected' });
    try {
        const requestedLimit = req.body && parseInt(req.body.per_chat_limit, 10);
        const perChatLimit = isFinite(requestedLimit) && requestedLimit > 0 ? Math.min(5000, requestedLimit) : 0;
        const started = await startHistoryImport(perChatLimit);
        return res.status(started ? 202 : 200).json({ started, status: getHistoryImportStatus() });
    } catch (error) { return res.status(500).json({ error: error.message }); }
});

router.get('/history/import/status', async (req, res) => {
    try { return res.json(restoreHistoryImportState ? await restoreHistoryImportState() : getHistoryImportStatus()); }
    catch(error){return res.status(500).json({error:error.message});}
});

module.exports = router;
