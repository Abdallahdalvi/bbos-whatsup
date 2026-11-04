'use strict';

// Whitelist presentation data; never persist WhatsApp message secrets or raw models.
function richMessage(message) {
    const data = message._data || message;
    const rich = { caption: String(message.body || data.caption || ''), actions: [] };
    if (message.hasMedia || /^(image|video|audio|ptt|document|sticker|gif)$/.test(message.type || data.type || '')) {
        const positive = (value, maximum) => Math.max(0, Math.min(maximum, Number(value) || 0));
        rich.media = { width: positive(data.width, 32768), height: positive(data.height, 32768),
            duration: positive(message.duration || data.duration, 86400), filename: String(message.filename || data.filename || ((message.type || data.type) === 'document' ? message.title || data.title : '') || '') };
    }
    if (message.type === 'poll_creation' || message.pollOptions || data.pollOptions) {
        rich.poll = {
            name: String(message.pollName || data.pollName || message.body || 'Poll'),
            multiple: message.allowMultipleAnswers !== undefined ? !!message.allowMultipleAnswers : !data.pollSelectableOptionsCount,
            options: (message.pollOptions || data.pollOptions || []).map(option => String(option.name || option)).slice(0, 12)
        };
    }
    const buttons = message.dynamicReplyButtons || data.dynamicReplyButtons || data.buttons || [];
    for (const button of buttons.slice(0, 20)) {
        const url = button.url || button.urlButton && button.urlButton.url;
        const phone = button.phoneNumber || button.callButton && button.callButton.phoneNumber;
        const label = String(button.displayText || button.text || button.buttonText && button.buttonText.displayText
            || button.urlButton && button.urlButton.displayText || button.callButton && button.callButton.displayText || 'Action');
        rich.actions.push({ label, kind: url ? 'url' : phone ? 'phone' : 'unsupported_reply',
            value: String(url || phone || button.id || button.buttonId || '') });
    }
    const flow = data.interactiveMessage && data.interactiveMessage.nativeFlowMessage;
    for (const button of flow && flow.buttons || []) {
        let params = {};
        try { params = JSON.parse(button.buttonParamsJson || '{}'); } catch (_) { }
        rich.actions.push({ label: String(params.display_text || button.name || 'Action'),
            kind: button.name === 'cta_url' ? 'url' : button.name === 'cta_call' ? 'phone' : button.name === 'cta_copy' ? 'copy' : 'unsupported_reply',
            value: String(params.url || params.phone_number || params.copy_code || params.id || '') });
    }
    if (data.lat && data.lng) rich.location = { latitude: Number(data.lat), longitude: Number(data.lng), name: String(data.loc || '') };
    if (message.vCards && message.vCards.length) rich.vcards = message.vCards.slice(0, 20);
    return rich;
}

module.exports = { richMessage };
