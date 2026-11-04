'use strict';
const fs = require('fs');
const path = require('path');
const ffmpeg = require('fluent-ffmpeg');
const jobs = new Map();
let queue = Promise.resolve();
const thumbnailQueues=[Promise.resolve(),Promise.resolve()];
let thumbnailLane=0;

async function mediaVariant(source, id, variant, root) {
    const thumbnail = variant === 'thumbnail';
    const output = path.join(root, `${id}-${thumbnail ? 'preview.jpg' : 'q20-480.mp4'}`);
    if (fs.existsSync(output) && fs.statSync(output).size) return output;
    if (jobs.has(output)) return jobs.get(output);
    const lane=thumbnail?thumbnailLane++%2:-1;
    const previous=thumbnail?thumbnailQueues[lane]:queue;
    const job = previous.catch(() => {}).then(() => new Promise((resolve, reject) => {
        const temporary = output + '.part';
        let command = ffmpeg(source).videoFilters("scale=w='min(480,iw)':h='min(480,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2");
        if (thumbnail) command = command.noAudio().frames(1).outputOptions(['-threads 1']).format('image2');
        else command = command.videoCodec('libx264').audioCodec('aac').audioChannels(1).audioFrequency(44100)
            .videoBitrate('700k').audioBitrate('64k').fps(24)
            .outputOptions(['-profile:v baseline', '-level 3.0', '-pix_fmt yuv420p', '-preset veryfast', '-movflags +faststart', '-threads 1']).format('mp4');
        const timeout = setTimeout(() => command.kill('SIGKILL'), 180000);
        command.on('end', () => { clearTimeout(timeout); try { fs.renameSync(temporary, output); resolve(output); } catch (error) { reject(error); } })
            .on('error', error => { clearTimeout(timeout); try { fs.unlinkSync(temporary); } catch (_) { } reject(new Error('Q20 media conversion failed. FFmpeg with H.264/AAC support is required: ' + error.message)); })
            .save(temporary);
    })).finally(() => jobs.delete(output));
    jobs.set(output, job);
    if(thumbnail)thumbnailQueues[lane]=job;else queue = job;
    return job;
}
module.exports = { mediaVariant };
