package com.aghani.q20messenger;

import java.io.ByteArrayOutputStream;

/** Streaming PCM16 mono rate conversion for legacy audio devices. */
final class PcmResampler {
    private final double step;
    private double next;
    private long count;
    private short previous;
    PcmResampler(int inputRate, int outputRate) { step = inputRate / (double) outputRate; }
    byte[] convert(byte[] data, int length) {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) (length / step) + 8);
        for (int i = 0; i + 1 < length; i += 2) {
            short current = (short) ((data[i] & 255) | (data[i + 1] << 8));
            while (next <= count) {
                double fraction = count == 0 ? 1 : next - (count - 1);
                int value = (int) Math.round(previous + (current - previous) * fraction);
                output.write(value & 255); output.write((value >> 8) & 255);
                next += step;
            }
            previous = current; count++;
        }
        return output.toByteArray();
    }
}
