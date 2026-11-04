const { Client, LocalAuth, MessageMedia } = require("whatsapp-web.js")
const qrcode = require("qrcode-terminal")
const qrimage = require("qr-image"); 

const fs = require("fs");
let q20BrowserPath = process.env.PUPPETEER_EXECUTABLE_PATH || '';
let q20UserAgent = process.env.Q20_USER_AGENT || '';
{
    try {
        const savedBrowser = JSON.parse(fs.readFileSync(require('path').join(__dirname, 'q20-browser-config.json'), 'utf8'));
        if ((!q20BrowserPath || savedBrowser.forceExecutable) && savedBrowser.executablePath && fs.existsSync(savedBrowser.executablePath)) {
            q20BrowserPath = savedBrowser.executablePath;
            if (!q20UserAgent && savedBrowser.userAgent) q20UserAgent = savedBrowser.userAgent;
        }
    } catch (_) { }
}
const { PhoneNumberUtil, PhoneNumberFormat } = require("google-libphonenumber");
const phoneUtil = PhoneNumberUtil.getInstance();
const ffmpeg = require("fluent-ffmpeg");
const { db: getDb } = require("./connect.js");

const clients = {}
const authenticatedClients = {}
const qrcodes = {}
const { createContactCache, readCompactContacts } = require('./q20ContactCache');
const q20Contacts = createContactCache({
    load: () => client.pupPage.evaluate(readCompactContacts),
    fallback: async () => (await getDb().all("SELECT chat_jid,display_name FROM q20_conversations WHERE chat_type='chat' AND display_name<>'' AND (chat_jid LIKE '%@c.us' OR chat_jid LIKE '%@lid')"))
        .map(row => ({ id: { _serialized: row.chat_jid }, name: row.display_name, isWAContact: true }))
});
async function q20CachedContacts(){ return q20Contacts.get(); }


    let currentQr = null;

const client = new Client({
        userAgent: q20UserAgent || false,
        authStrategy: new LocalAuth({
            clientId: 'local'
        }),
        puppeteer: { 
            headless: true, 
            executablePath: q20BrowserPath || undefined,
            // executablePath: '/usr/bin/google-chrome',
            // executeablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
            // executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
            args: [
                '--autoplay-policy=no-user-gesture-required',
                '--use-fake-ui-for-media-stream',
                '--use-fake-device-for-media-stream',
                '--no-sandbox', 
                '--disable-setuid-sandbox', 
                '--disable-dev-shm-usage' // Uses /tmp instead of /dev/shm (fixes Docker crashes)
            ]
        },
    })

    client.initialize().catch(err => console.log(err))
    
    client.on("qr", (qr) => {
        currentQr = qr;
        // console.log(qr)
        qrcode.generate(qr, { small: true })
        // const qr_image = qrimage.image(qr, { type: "png" });
        // qr_image.pipe(fs.createWriteStream("./public/images/qr_" + id + ".png"));
        // console.log("QR code generated");

        // qrcodes[id] = qr;

    })
    client.on("ready", () => {

        // authenticatedClients[id] = id;
        currentQr = null;
        console.log("Client is ready!")
    })
    
    client.on('disconnected', async (reason) => {
        console.log('Restarting client...');
        await client.destroy();
        client.initialize();
    });

    client.on('change_state', (state) => {
        console.log('Connection state changed:', state);
        // States include: 'CONNECTED', 'CONNECTING', 'DISCONNECTED', 'PAIRING', 'PROXY_AUTH_REQUIRED', 'TIMEOUT', 'TOS_BLOCK', 'UNLAUNCHED', 'UNPAIRED', 'UNPAIRED_IDLE'
    });

    // Emitted when a new message is received from other users.
    client.on('message', async message => {

        // below will log the group id (e.g. 120363420419601014@g.us) if message received from group
        // or it will log the user id (e.g. 966123456789@c.us) if message received from user
        console.log(`Message from: ${message.from}`);
        
        // below will log the user id (e.g. 966123456789@c.us) of the sender even if it is received from group
        let waUser = null;
        try {
            waUser = await message.getContact();
        } catch (contactError) {
            console.warn('Contact lookup failed; storing message by JID:', contactError.message || contactError);
        }
        console.log('Sender: ' + (waUser && waUser.id && waUser.id.user || message.author || 'unknown'));

        let waChat = null;
        try {
            waChat = await message.getChat();
        } catch (chatError) {
            console.warn('Chat metadata lookup failed; storing JID:', chatError.message || chatError);
        }

        // console.log('\nlogging full message object for debugging: \n\n');
        // console.log(message);

        // console.log('\nlogging full chat object for debugging: \n\n');
        // console.log(waChat);

        // Below code for testing a message with mentions
        // await chat.sendMessage(`Hello @${user.id.user}`, {
        //     mentions: [user]
        // });
        let msg;

        if (message.type == 'ptt')
            msg = 'Voice received';
        else if (message.type == 'image')
            msg = 'Image received'; //image/jpeg, image/webp
        else if (message.type == 'audio')
            msg = 'Audio received'; //audio/ogg
        else if (message.type == 'video')
            msg = 'Video received'; //video/mp4
        else if (message.type == 'chat')
            msg = message.body;
        else
            msg = `${message.type} received`;

        console.log(`Message: ${msg}`);

        // don't log broadcast messages
        if (message.from != 'status@broadcast') {
            //await Chat.deleteMany({ sender: message.from });

            // if message from individual user, sender name will be his name
            // else if message from group chat, sender name will be group name

            let senderNameForChat = waChat && waChat.isGroup && waChat.name
                ? waChat.name : message._data.notifyName;
            let senderNameForMessages = message._data.notifyName;

            console.log(`senderNameForChat: [${senderNameForChat}]`);
            console.log(`senderNameForMessages: [${senderNameForMessages}]`);

            if(senderNameForMessages == undefined)
                senderNameForMessages = message.from;

            if(senderNameForChat == undefined)
                senderNameForChat = message.from;

            const conversationJid = String(message.from || '');
            if (conversationJid.endsWith('@g.us') && waChat && waChat.name) {
                await getDb().run('INSERT OR REPLACE INTO q20_conversations(chat_jid,display_name,chat_type,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)',
                    [conversationJid, String(waChat.name), 'group']);
            } else if (conversationJid.endsWith('@lid') && waUser) {
                const contactName = String(waUser.name || waUser.pushname || waUser.shortName || '').trim();
                if (contactName) {
                    await getDb().run('INSERT OR REPLACE INTO q20_conversations(chat_jid,display_name,chat_type,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)',
                        [conversationJid, contactName, 'chat']);
                }
            }
            
            await getDb().run(`DELETE FROM chats where sender = ?`, [message.from]);

            await getDb().run(`INSERT INTO chats(sender, receiver, message, status, sender_name, chat_type, device_type) 
                VALUES(?, ?, ?, ?, ?, ?, ?)`,
                [message.from, message.to, msg, 0, senderNameForChat, message.type, message.deviceType]);


            const result = 
                await getDb().run(`INSERT INTO messages(sender, receiver, message, status, sender_name, chat_type, device_type) 
                VALUES(?, ?, ?, ?, ?, ?, ?)`,
                [message.from, message.to, msg, 0, senderNameForMessages, message.type, message.deviceType]);

            const newId = result.lastID;
            await getDb().run('UPDATE messages SET rich_json=?, message=? WHERE _id=?',
                [JSON.stringify(require('./q20RichMessages').richMessage(message)), String(message.body || message.pollName || msg), newId]);
            const incomingWaId = message.id && message.id._serialized || null;
            await getDb().run('UPDATE messages SET whatsapp_message_id=?, chat_jid=?, participant_jid=?, from_me=0 WHERE _id=?',
                [incomingWaId, message.from, message.author || message.from, newId]);

            if (String(message.from || '').endsWith('@lid') && typeof client.getContactLidAndPhone === 'function') {
                try {
                    const mappings = await client.getContactLidAndPhone([message.from]);
                    for (const mapping of (mappings || [])) {
                        if (mapping.lid && mapping.pn) {
                            const lid = normalizeWhatsAppJid(mapping.lid);
                            const phone = normalizeWhatsAppJid(mapping.pn);
                            await getDb().run('INSERT OR REPLACE INTO q20_jid_mappings(lid_jid,phone_jid,updated_at) VALUES(?,?,CURRENT_TIMESTAMP)', [lid, phone]);
                        }
                    }
                } catch (mappingError) {
                    console.warn('Could not cache incoming LID mapping:', mappingError.message || mappingError);
                }
            }


            if (message.hasMedia) {
                // WhatsApp Web 2.3000.x message ID compatibility
                if (message.id && !message.id._serialized) {
                    const serializedId = message.id.$1 ||
                        (message.id.remote && message.id.id !== undefined
                            ? `${message.id.fromMe}_${message.id.remote}_${message.id.id}`
                            : undefined);

                    if (serializedId) {
                        message.id._serialized = serializedId;
                    }
                }

                let media;
                try {
                    media = await message.downloadMedia();
                } catch (error) {
                    console.error('Media download failed:', error?.message || error);
                    return;
                }

                if (!media) {
                    console.warn('Media download returned no media.');
                    return;
                }

                // Uncomment below code if you want to save the received Image, Audio or Video in MongoDB database instead of file system
                /*
                const image = await Image.create({
                    mediaData: Buffer.from(media.data, "base64"),
                    mediaFilename: media.filename,
                    mediaMimetype: media.mimetype,
                    mediaFilesize: media.filesize
                });
                */

                if(message.type == 'image'){
                    // mediaMimetype: 'image/jpeg'
                    // mediaMimetype: 'image/webp'

                    let fileExt = '.jpg';

                    if(media.mimetype == 'image/webp')
                        fileExt = '.webp';

                    const sourceMediaFilename = './media/' + newId + fileExt;
                    fs.writeFileSync(sourceMediaFilename, Buffer.from(media.data, 'base64'));
                }
        
                else if(message.type == 'audio' || message.type == 'ptt'){
                    // mediaMimetype: 'audio/ogg; codecs=opus'

                    const sourceMediaFilename = './media/' + newId + '.ogg';
                    //const targetMediaFilename = './media/' + newId + '.wav';
                    const targetMediaFilename = './media/' + newId + '.mp3';
        
                    fs.writeFileSync(sourceMediaFilename, Buffer.from(media.data, 'base64'));
        
                    // Old Nokia phones cannot play audio with OGG format which is used by WhatsApp
                    // So convert from OGG to WAV file format

                    /*
                    ffmpeg()
                        .input(`${sourceMediaFilename}`)
                        .audioCodec("libvorbis")
                        .output(`${targetMediaFilename}`)
                        .audioCodec("pcm_s16le")
                        .on("end", async () => {
                            console.log("Conversion finished");
                        })
                        .on("error", (err) => {
                            console.error("Error:", err);
                        })
                        .run();
                    */
                    
                    ffmpeg()
                        .input(`${sourceMediaFilename}`)
                        .audioCodec("libvorbis")
                        .output(`${targetMediaFilename}`)
                        .audioCodec("libmp3lame")
                        .on("end", async () => {
                            console.log("Conversion finished");
                        })
                        .on("error", (err) => {
                            console.error("Error:", err);
                        })
                        .run();
                        
                }
                
                else if(message.type == 'video'){
                    // mediaMimetype: 'video/mp4'

                    const sourceMediaFilename = './media/' + newId + '.mp4';
                    fs.writeFileSync(sourceMediaFilename, Buffer.from(media.data, 'base64'));

                    const targetMediaFilename = './media/' + newId + '.3gp';

                    // Some old Nokia phones cannot play Video in MP4 format which is used by WhatsApp
                    // So convery from MP4 to 3GP file format 

                    ffmpeg()
                        .input(`${sourceMediaFilename}`)
                        .outputOptions([
                        '-acodec aac',
                        '-strict experimental',
                        '-ac 1',
                        '-ar 8000',
                        '-ab 24k'
                        ])
                        .videoFilters('scale=352:288:force_original_aspect_ratio=decrease,pad=352:288:(ow-iw)/2:(oh-ih)/2,setsar=1')
                        .output(`${targetMediaFilename}`)
                        .on("end", async () => {
                        console.log("Conversion finished");
                        })
                        .on("error", (err) => {
                        console.error("Error:", err);
                        })
                        .run();
                }
                
                
            }


        }


    });



async function loginUser(mobileNumber) {
    
    try {
        
        const regex = /;interface=wifi/i;
        mobileNumber = mobileNumber.replace(regex, "");

        console.log(`login user = ${mobileNumber}`);


        if(client == undefined || client.info == undefined || client.info.wid == undefined || client.info.wid.user == undefined)
            return { status: 401, data: { error: "User session not found" } };

        const user = {
            pushname: client.info.pushname, 
            user: client.info.wid.user, 
            platform: client.info.platform
        };

        return { status: 200, data: user };

    } catch (error){
        console.log(error);
        return { status: 500, data: { error: error.message } };
    }
}

async function getChats(receiver, page, pageSize) {
    try {
        receiver = normalizeWhatsAppJid(receiver);
        if (!client || !client.info) {
            return { status: 401, data: { error: "User session not found" } };
        }

        const safePage = Math.max(0, parseInt(page, 10) || 0);
        const safePageSize = Math.min(100, Math.max(1, parseInt(pageSize, 10) || 30));
        const offset = safePage * safePageSize;
        const rows = await getDb().all(
            'SELECT * FROM (' +
            ' SELECT _id, sender AS peer_jid, sender_name, message, status, timestamp FROM chats WHERE receiver = ?' +
            ' UNION ALL' +
            ' SELECT _id, receiver AS peer_jid, sender_name, message, status, timestamp FROM chats WHERE sender = ?' +
            ') ORDER BY timestamp DESC, _id DESC LIMIT ? OFFSET ?',
            [receiver, receiver, safePageSize * 3, offset]
        );

        const seen = {};
        const chats = [];
        for (const row of rows) {
            if (seen[row.peer_jid]) continue;
            seen[row.peer_jid] = true;
            chats.push({
                _id: row._id,
                sender: row.peer_jid,
                senderName: row.sender_name,
                message: row.message,
                status: row.status,
                createdAt: row.timestamp,
                updatedAt: row.timestamp
            });
            if (chats.length >= safePageSize) break;
        }

        return { status: 200, data: { chats: chats } };
    } catch (error) {
        console.error('getChats failed:', error.stack || error);
        return { status: 500, data: { error: error.message } };
    }
}
async function getAllChats(mobileNumber) {
    try {
        
        const regex = /;interface=wifi/i;
        mobileNumber = mobileNumber.replace(regex, "");

        if(client == undefined){
            return { status: 401, data: { error: "User session not found" } }
        }

        var chats = await client.getChats();

        return { status: 200, data: { chats: chats } };

    } catch (error){
        console.log(error);
        return { status: 500, data: { error: error.message } };
    }
}

async function getAllMessages(mobileNumber, chatId) {
    try {
        
        
        const regex = /;interface=wifi/i;
        mobileNumber = mobileNumber.replace(regex, "");

        if(client == undefined)
            return { status: 401, data: { error: "User session not found" } };

        
        var chat = await client.getChatById(chatId);
        if(chat == undefined)
            return { status: 404, data: { error: "Chat not found" } };

        const messages = await chat.fetchMessages({limit: 2});
        
        return { status: 200, data: { messages: messages } };

    } catch (error){
        console.log(error);
        return { status: 500, data: { error: error.message } };
    }
}


async function getMessages(receiver, sender, page, pageSize) {
    try {
        const regex = /;interface=wifi/i;

        receiver = String(receiver || '').replace(regex, '');
        sender = String(sender || '').replace(regex, '');

        if (!client || !client.info) {
            return {
                status: 401,
                data: { error: "User session not found" }
            };
        }

        // A WhatsApp contact can have both a phone-number JID
        // (@c.us) and a linked-device ID (@lid).
        const peerIds = new Set([sender]);

        // Resolve the alternate ID for direct chats.
        // Do not transform group JIDs.
        if (
            !sender.endsWith('@g.us') &&
            typeof client.getContactLidAndPhone === 'function'
        ) {
            try {
                const mappings = await client.getContactLidAndPhone([sender]);

                for (const mapping of (mappings || [])) {
                    if (mapping.lid) peerIds.add(mapping.lid);
                    if (mapping.pn) peerIds.add(mapping.pn);
                }

            } catch (mappingError) {
                console.warn(
                    'Could not resolve WhatsApp LID mapping:',
                    mappingError.message || mappingError
                );
            }
        }

        const peerList = [...peerIds];
        const placeholders = peerList.map(() => '?').join(', ');

        // Retrieve incoming and outgoing messages using either contact ID.
        const sql = `
            SELECT * FROM messages
            WHERE
                (receiver = ? AND sender IN (${placeholders}))
                OR
                (sender = ? AND receiver IN (${placeholders}))
            ORDER BY timestamp DESC
            LIMIT 20
        `;

        const params = [
            receiver,
            ...peerList,
            receiver,
            ...peerList
        ];

        const rows = await getDb().all(sql, params);

        const messages = rows.map(row => ({
            _id: row._id,
            sender: row.sender,
            receiver: row.receiver,
            message: row.message,
            status: row.status,
            senderName: row.sender_name,
            chatType: row.chat_type,
            deviceType: row.device_type,
            createdAt: row.timestamp,
            updatedAt: row.timestamp
        }));

        return {
            status: 200,
            data: { messages }
        };

    } catch (error) {
        console.error('getMessages failed:', error.stack || error);

        return {
            status: 500,
            data: { error: error.message }
        };
    }
}

async function getContacts(mobileNumber, searchTerm, page, pageSize) {
    try {
         const regex = /;interface=wifi/i;
        mobileNumber = mobileNumber.replace(regex, "");

        if(client == undefined)
            return { status: 401, data: { error: "User session not found" } };

        var contacts = await q20CachedContacts();

        var filteredContacts =  contacts.filter(item => {
            return item.id && (item.isWAContact !== false || /@lid$/.test(item.id._serialized||''))
        });

        const compactContactsList = filteredContacts.map(item => {
            const container = {};

            container.id = item.id._serialized;
            const candidates = [item.name,item._data&&item._data.name,item._data&&item._data.formattedName,item.pushname, item.shortName, item.verifiedName,
                item._data && item._data.verifiedName, item._data && item._data.notifyName];
            const displayName = candidates.map(value => String(value || '').trim()).find(value =>
                value && value !== container.id && !/@[^\s]+$/.test(value)
                && !/^\+?[0-9][0-9\s().-]{5,}$/.test(value));
            container.name = displayName || 'WhatsApp contact';

            return container;
        });

        var filteredContacts2 =  compactContactsList.filter(item => {
            return item.name.toLowerCase().includes(searchTerm.toLowerCase());
        });

        var startIndex = page * pageSize;
        var endIndex = parseInt(startIndex) + parseInt(pageSize);

        console.log(`page: ${page}, pageSize: ${pageSize}, startIndex: ${startIndex}, endIndex: ${endIndex}, count: ${filteredContacts2.length}`);

        return { status: 200, data: { contacts: filteredContacts2.slice(startIndex, endIndex), count: filteredContacts2.length, contact_cache: q20Contacts.status() } };

    } catch (error){
        if(error.status !== 503)console.log(error);
        return { status: error.status || 500, data: { error: error.message, contact_cache: q20Contacts.status() } };
    }
}


function normalizeWhatsAppJid(value) {
    const jid = String(value || '').replace(/;interface=wifi/ig, '').trim();
    if (!jid) throw new Error('Missing WhatsApp recipient');
    if (/@(?:c.us|lid|g.us)$/i.test(jid)) return jid;
    return jid.replace(/^[+]/, '') + '@c.us';
}

async function uploadMedia(media, sender, receiver, sendAsDocument, options) {
    let temporaryFiles = media && media.path ? [media.path] : [];

    try {
        if (!client || !client.info) {
            return {
                status: 401,
                data: { statusCode: '002', statusDesc: 'User session not found' }
            };
        }

        const uploadedPath = media && media.path && fs.existsSync(media.path) ? media.path : '';
        if (!media || (!media.data && !uploadedPath)) {
            return {
                status: 400,
                data: { statusCode: '001', statusDesc: 'No media received' }
            };
        }

        const mime = String(media.mimetype || '')
            .split(';')[0].toLowerCase();

        const supported = {
            'image/jpeg': { ext: '.jpg', type: 'image', label: 'Image sent' },
            'image/png': { ext: '.png', type: 'image', label: 'Image sent' },
            'image/webp': { ext: '.webp', type: 'image', label: 'Image sent' },
            'image/gif': { ext: '.gif', type: 'gif', label: 'GIF sent' },
            'video/mp4': { ext: '.mp4', type: 'video', label: 'Video sent' },
            'audio/mpeg': { ext: '.mp3', type: 'audio', label: 'Audio sent' },
            'audio/3gpp': { ext: '.3gp', type: 'audio', label: 'Voice sent' },
            'audio/amr': { ext: '.amr', type: 'audio', label: 'Voice sent' },
            'audio/ogg': { ext: '.ogg', type: 'audio', label: 'Audio sent' },
            'audio/aac': { ext: '.aac', type: 'audio', label: 'Audio sent' },
            'audio/mp4': { ext: '.m4a', type: 'audio', label: 'Audio sent' },
            'audio/wav': { ext: '.wav', type: 'audio', label: 'Audio sent' },
            'application/pdf': { ext: '.pdf', type: 'document', label: 'Document sent' },
            'text/plain': { ext: '.txt', type: 'document', label: 'Document sent' },
            'text/csv': { ext: '.csv', type: 'document', label: 'Document sent' },
            'application/msword': { ext: '.doc', type: 'document', label: 'Document sent' },
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document': { ext: '.docx', type: 'document', label: 'Document sent' },
            'application/vnd.ms-excel': { ext: '.xls', type: 'document', label: 'Document sent' },
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': { ext: '.xlsx', type: 'document', label: 'Document sent' },
            'application/vnd.ms-powerpoint': { ext: '.ppt', type: 'document', label: 'Document sent' },
            'application/vnd.openxmlformats-officedocument.presentationml.presentation': { ext: '.pptx', type: 'document', label: 'Document sent' },
            'application/zip': { ext: '.zip', type: 'document', label: 'Document sent' }
        };

        let spec = supported[mime];
        if (sendAsDocument || (!spec && !/^(image|video|audio)\//.test(mime))) {
            const extension = require('path').extname(String(media.name || media.filename || '')).toLowerCase();
            spec = { ext: /^\.[a-z0-9]{1,10}$/.test(extension) ? extension : '.bin', type: 'document', label: 'Document sent' };
        }

        if (!spec) {
            return {
                status: 415,
                data: {
                    statusCode: '004',
                    statusDesc: 'Unsupported media format: ' + mime
                }
            };
        }

        const senderJid = normalizeWhatsAppJid(sender);
        const receiverJid = normalizeWhatsAppJid(receiver);

        const bytes = media.data
            ? (Buffer.isBuffer(media.data) ? media.data : Buffer.from(media.data, 'base64'))
            : null;
        const uploadSize = bytes ? bytes.length : Number(media.size || fs.statSync(uploadedPath).size);
        if (!uploadSize) throw new Error('Uploaded media is empty');

        fs.mkdirSync('./media', { recursive: true });

        const tempBase = './media/upload-' +
            Date.now() + '-' + Math.random().toString(16).slice(2);

        const sourcePath = uploadedPath || (tempBase + spec.ext);
        temporaryFiles.push(sourcePath);
        if (!uploadedPath) fs.writeFileSync(sourcePath, bytes);

        let sendPath = sourcePath;
        let sendOptions = {};
        if (spec.type === 'document' || (spec.type === 'video' && sendAsDocument)) {
            sendOptions = { sendMediaAsDocument: true };
        }

        // Convert MP3 to Opus/Ogg and send as a WhatsApp voice note.
        const voiceNote = spec.type === 'audio' && (!options || options.voiceNote !== false);
        if (voiceNote) {
            sendPath = tempBase + '.ogg';
            temporaryFiles.push(sendPath);

            await new Promise((resolve, reject) => {
                ffmpeg(sourcePath)
                    .noVideo()
                    .audioCodec('libopus')
                    .audioChannels(1)
                    .audioFrequency(48000)
                    .audioBitrate('64k')
                    .outputOptions(['-application voip'])
                    .format('ogg')
                    .on('end', resolve)
                    .on('error', reject)
                    .save(sendPath);
            });

            if (!fs.existsSync(sendPath) || fs.statSync(sendPath).size === 0) {
                throw new Error('Voice-note conversion produced an empty file');
            }

            sendOptions = { sendAudioAsVoice: true };
        }

        console.log('Sending media:', spec.label, 'to', receiverJid);

        const mediaObject = MessageMedia.fromFilePath(sendPath);
        if (media.name || media.filename) mediaObject.filename = require('path').basename(String(media.name || media.filename));

        const sentMessage = await client.sendMessage(
            receiverJid,
            mediaObject,
            sendOptions
        );

        if (!sentMessage) {
            throw new Error('WhatsApp did not confirm media sending');
        }

        // Save message history only after WhatsApp accepts the media.
        const chatSql = `
            INSERT INTO chats
            (sender, receiver, message, status, sender_name, chat_type, device_type)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        `;

        await getDb().run(chatSql, [
            senderJid,
            receiverJid,
            spec.label,
            0,
            'Me',
            spec.type,
            'android'
        ]);

        const messageSql = `
            INSERT INTO messages
            (sender, receiver, message, status, sender_name, chat_type, device_type)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        `;

        const result = await getDb().run(messageSql, [
            senderJid,
            receiverJid,
            spec.label,
            0,
            'Me',
            spec.type,
            'android'
        ]);

        const id = result.lastID;
        await getDb().run('UPDATE messages SET rich_json=? WHERE _id=?',
            [JSON.stringify(require('./q20RichMessages').richMessage(sentMessage)), id]);
        const savedFilename = id + spec.ext;
        await getDb().run('UPDATE messages SET chat_jid=?, from_me=1, media_filename=?, media_original_filename=?, media_mimetype=?, media_filesize=?, media_conversion_state=? WHERE _id=?',
            [receiverJid, savedFilename, String(media.name || media.filename || ''), mime, uploadSize, voiceNote ? 'converted' : 'ready', id]);

        // Keep the original file for the BlackBerry media API.
        fs.copyFileSync(sourcePath, './media/' + id + spec.ext);

        // Also preserve the converted voice-note version.
        if (spec.type === 'audio') {
            fs.copyFileSync(sendPath, './media/' + id + '.ogg');
        }

        console.log('Media sent successfully. Message ID:', id);

        return {
            status: 200,
            data: {
                statusCode: '000',
                statusDesc: 'Media uploaded successfully'
            }
        };

    } catch (error) {
        console.error('Media upload failed:', error.stack || error);

        return {
            status: 500,
            data: {
                statusCode: '003',
                statusDesc: error.message || 'Media upload failed'
            }
        };

    } finally {
        for (const file of temporaryFiles) {
            try {
                if (fs.existsSync(file)) fs.unlinkSync(file);
            } catch (cleanupError) {
                console.error('Temporary media cleanup failed:', cleanupError.message);
            }
        }
    }
}

async function sendMessage(sender, receiver, message) {
    try {

        if(client == undefined)
            return { status: 401, data: {statusCode: '002', statusDesc: 'User session not found'} };

        console.log(`message to send: ${message}`);
        console.log(`retrieved user from socket list: ${client.info.wid.user}`);

        const senderJid = normalizeWhatsAppJid(sender);
        const receiverJid = normalizeWhatsAppJid(receiver);
        const messageReply = await client.sendMessage(receiverJid, message);
        if (!messageReply) throw new Error('WhatsApp did not confirm text sending');
        //console.log(messageReply);

        let sql = `INSERT INTO chats(sender, receiver, message, status, sender_name, chat_type, device_type)
            VALUES(?, ?, ?, ?, ?, ?, ?)
        `;


        await getDb().run(sql, [
            senderJid,
            receiverJid,
            message,
            0,
            client.info.pushname,
            'chat',
            client.info.platform
        ]);

        sql = `INSERT INTO messages(sender, receiver, message, status, sender_name, chat_type, device_type)
            VALUES(?, ?, ?, ?, ?, ?, ?)
        `;

        const storedMessage = await getDb().run(sql, [
            senderJid,
            receiverJid,
            message,
            0,
            client.info.pushname,
            'chat',
            client.info.platform
        ]);
        await getDb().run('UPDATE messages SET chat_jid = ?, from_me = 1, whatsapp_message_id = ? WHERE _id = ?',
            [receiverJid, messageReply && messageReply.id && messageReply.id._serialized || null, storedMessage.lastID]);

        return { status: 200, data: { message: 'message sent successfully' } };

    } catch (error) {
        
        var errorMessage = error.message.split(/\r?\n|\r|\n/g);
        var errorMessageLine1 = errorMessage[0];
        console.log(errorMessageLine1);
        
        return { status: 500, data: { error: errorMessageLine1 } };
    }
}

async function createCallInvite(sender, receiver, callType) {
    if (!client || !client.info) throw new Error('WhatsApp session is not connected');
    if (callType !== 'voice' && callType !== 'video') throw new Error('Call type must be voice or video');
    if (typeof client.createCallLink !== 'function') throw new Error('This whatsapp-web.js version cannot create WhatsApp call links');
    let link;
    try {
        // Upstream PR #201950: call-link generation moved into a lazy event-screen bundle.
        if (client.pupPage && typeof client.pupPage.evaluate === 'function') {
            await client.pupPage.evaluate(async () => {
                if (typeof window.require !== 'function') return;
                if (!window.require('WAWebGenerateEventCallLink')) {
                    await window.require('JSResourceForInteraction')('WAWebEventsCreateEventModalFlow.react').load();
                }
            });
        }
        link = await client.createCallLink(new Date(Date.now() + 60000), callType);
    } catch (error) {
        const failure = new Error('WhatsApp call-link generation failed: ' + (error && error.message || error));
        failure.callStage = 'link_generation';
        throw failure;
    }
    if (!link) throw new Error('WhatsApp did not create a call link');
    let sent;
    try {
        sent = await sendMessage(sender, receiver, `Join my WhatsApp ${callType} call: ${link}`);
    } catch (error) {
        return {
            link, call_type: callType, invitation_sent: false,
            warning: 'Call link was created, but invitation sending failed: ' + (error && error.message || error)
        };
    }
    if (!sent || sent.status !== 200) {
        const detail = sent && sent.data && (sent.data.error || sent.data.statusDesc);
        return {
            link, call_type: callType, invitation_sent: false,
            warning: 'Call link was created, but invitation sending failed: ' + (detail || 'WhatsApp did not confirm the message')
        };
    }
    return { link, call_type: callType, invitation_sent: true, message: 'Call invitation sent' };
}




// Compatibility helpers for NovelProfessor's original REST API.
// This installation deliberately uses one existing global WhatsApp client.

function normalizeSessionNumber(value) {
    return String(value || '')
        .replace(/;interface=wifi/ig, '')
        .replace(/@c\.us$/i, '')
        .replace(/\D/g, '');
}

function getStatus(phoneNumber) {
    const info = client && client.info ? client.info : null;
    const actualUser = info?.wid?.user ? String(info.wid.user) : '';
    const requested = normalizeSessionNumber(phoneNumber);

    const authenticated = Boolean(
        actualUser &&
        (!requested || requested === normalizeSessionNumber(actualUser))
    );

    return {
        isAuthenticated: authenticated,
        qr: authenticated ? null : currentQr,
        pushname: authenticated ? (info.pushname || '') : null,
        user: authenticated ? actualUser : null,
        platform: authenticated ? (info.platform || '') : null
    };
}

function isAuthenticated(phoneNumber) {
    return getStatus(phoneNumber).isAuthenticated;
}

function validate(input, countryCode) {
    try {
        if (!countryCode || countryCode === "0") {
            return { valid: false, reason: "Country code is required" };
        }

        const number = phoneUtil.parse(input, countryCode);

        return {
            valid: phoneUtil.isValidNumber(number),
            formatted: phoneUtil.format(number, PhoneNumberFormat.E164),
            type: phoneUtil.getNumberType(number),
            country: phoneUtil.getRegionCodeForNumber(number)
        };

    } catch (error) {
        return { valid: false, reason: error.message };
    }
}

// The global client is already initialized at server startup.
// Never create a second browser or overwrite the existing session.
function startClient(phoneNumber) {
    const requested = normalizeSessionNumber(phoneNumber);
    const actual = normalizeSessionNumber(client?.info?.wid?.user);

    if (actual && requested && actual !== requested) {
        console.warn(
            'Single-session mode: refusing to launch another WhatsApp account.'
        );
        return false;
    }

    console.log(
        'Single-session WhatsApp client is already managed by the server.'
    );

    return true;
}

function listUsers() {
    let html = '<style>table,th,td{border:1px solid black;border-collapse:collapse;}</style>';
    html += '<table><tr><td>User</td><td>Platform</td></tr>';

    if (client?.info) {
        const escapeHtml = value => String(value || '').replace(
            /[&<>"']/g,
            character => ({
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#39;'
            })[character]
        );

        html += '<tr>';
        html += `<td>${escapeHtml(client.info.pushname)}</td>`;
        html += `<td>${escapeHtml(client.info.platform)}</td>`;
        html += '</tr>';
    }

    html += '</table>';
    return html;
}


const q20HistorySupport = require('./q20HistorySupport')(client);

module.exports = {
    getWhatsAppClient: () => client,
    startHistoryImport: q20HistorySupport.startHistoryImport,
    restoreHistoryImportState: q20HistorySupport.restoreHistoryImportState,
    getHistoryImportStatus: q20HistorySupport.getHistoryImportStatus,
    downloadMessageMedia: q20HistorySupport.downloadMessageMedia,
    refreshConversationMetadata: q20HistorySupport.refreshConversationMetadata,
    startClient,
    sendMessage,
    createCallInvite,
    isAuthenticated,
    getStatus,
    validate,
    getAllChats,
    getAllMessages,
    loginUser,
    getChats,
    getContacts,
    uploadMedia,
    getMessages,
    listUsers
};
