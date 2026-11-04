const express = require("express")
const http = require('http')
const crypto = require('crypto')
const { WebSocketServer } = require('ws')
const fileUpload = require("express-fileupload");

const messageRouter = require('./messageRouter.js')
const q20V2Router = require('./messageRouterV2.js')
const { initializeQ20Schema } = require('./q20SchemaV2.js')
const fs = require("fs");
const { initializeDatabase } = require('./connect.js')

const app = express()
const httpServer = http.createServer(app)
const callSockets = new WebSocketServer({ noServer: true, maxPayload: 8192, perMessageDeflate: false })

httpServer.on('upgrade', (request, socket, head) => {
    let parsed;
    try { parsed = new URL(request.url, 'http://localhost'); }
    catch (_) { socket.destroy(); return; }
    if (parsed.pathname !== '/api/v2/calls/stream') { socket.destroy(); return; }
    const expected = require('./q20Config').apiToken();
    const supplied = String(request.headers.authorization || '').replace(/^Bearer\s+/i, '');
    const a = Buffer.from(expected);
    const b = Buffer.from(supplied);
    if (!expected || !supplied || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
    }
    const callId = String(parsed.searchParams.get('call_id') || '');
    if (!callId) { socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); socket.destroy(); return; }
    callSockets.handleUpgrade(request, socket, head, webSocket => {
        q20V2Router.q20Calls.attachSocket(callId, webSocket);
    });
});

app.use(express.json())
app.use('/api/v2', q20V2Router)
app.use(fileUpload());
app.use(express.static("public"))
app.use(messageRouter)

// create media folder if it doesn't exist
const mediaPath = `${__dirname}/media`;

try {

    if (!fs.existsSync(mediaPath)) {
        fs.mkdirSync(mediaPath);
        console.log(`Folder '${mediaPath}' created successfully.`);
    } else {
        console.log(`Folder '${mediaPath}' already exists.`);
    }

} catch (err) {
    console.log('Error creating media folder:', err);
    process.exit(1);
}

// Initialize database and start server
initializeDatabase().then(async () => {
    await initializeQ20Schema();
    httpServer.listen(process.env.PORT || 80, () => console.log(`Server is ready on port ${process.env.PORT || 80}`))
}).catch(err => {
    console.error('Failed to initialize database:', err);
    process.exit(1);
})
