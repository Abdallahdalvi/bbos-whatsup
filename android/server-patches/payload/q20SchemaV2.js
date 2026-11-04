'use strict';

const { db } = require('./connect');

async function initializeQ20Schema() {
    const connection = db();
    const columns = [
        ['whatsapp_message_id', 'TEXT'], ['chat_jid', 'TEXT'],
        ['participant_jid', 'TEXT'], ['from_me', 'INTEGER NOT NULL DEFAULT 0'],
        ['media_filename', 'TEXT'], ['media_original_filename', 'TEXT'], ['media_mimetype', 'TEXT'],
        ['media_filesize', 'INTEGER NOT NULL DEFAULT 0'],
        ['media_conversion_state', 'TEXT'], ['reply_to_id', 'TEXT'],
        ['link_url', 'TEXT'], ['rich_json', 'TEXT']
    ];
    const existing = await connection.all('PRAGMA table_info(messages)');
    const names = new Set(existing.map(column => column.name));
    for (const [name, type] of columns) {
        if (!names.has(name)) await connection.exec(`ALTER TABLE messages ADD COLUMN ${name} ${type}`);
    }
    await connection.exec(`
        CREATE TABLE IF NOT EXISTS sync_state (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS q20_jid_mappings (
            lid_jid TEXT PRIMARY KEY,
            phone_jid TEXT NOT NULL,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS q20_conversations (
            chat_jid TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            chat_type TEXT NOT NULL DEFAULT 'chat',
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE INDEX IF NOT EXISTS idx_messages_receiver_sender_id ON messages(receiver,sender,_id);
        CREATE INDEX IF NOT EXISTS idx_messages_sender_receiver_id ON messages(sender,receiver,_id);
        CREATE INDEX IF NOT EXISTS idx_messages_chat_id ON messages(chat_jid,_id);
        CREATE INDEX IF NOT EXISTS idx_messages_whatsapp_id ON messages(whatsapp_message_id);
        CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_whatsapp_id_unique ON messages(whatsapp_message_id) WHERE whatsapp_message_id IS NOT NULL AND whatsapp_message_id <> '';
        CREATE INDEX IF NOT EXISTS idx_chats_sender_receiver_time ON chats(sender,receiver,timestamp);
        CREATE INDEX IF NOT EXISTS idx_chats_receiver_sender_time ON chats(receiver,sender,timestamp);
    `);
}

module.exports = { initializeQ20Schema };
