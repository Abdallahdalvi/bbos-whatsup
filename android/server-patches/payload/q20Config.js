'use strict';
const fs = require('fs');
const path = require('path');

function apiToken() {
    const environment = String(process.env.Q20_API_TOKEN || '').trim();
    if (environment) return environment;
    // A private config beside the server also works with plain node and Docker CMD.
    try {
        const config = JSON.parse(fs.readFileSync(path.join(__dirname, 'q20-private-config.json'), 'utf8'));
        return String(config.Q20_API_TOKEN || '').trim();
    } catch (_) { return ''; }
}

module.exports = { apiToken };
