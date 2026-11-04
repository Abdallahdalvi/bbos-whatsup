package com.aghani.q20messenger;
import org.junit.Test;
import static org.junit.Assert.*;
public class PcmResamplerTest {
    @Test public void streamingRateConversionPreservesDurationAndLevel() {
        for (int rate : new int[] {8000, 44100, 48000}) {
            byte[] input = new byte[rate * 2];
            for (int i = 0; i < rate; i++) { input[i * 2] = 0x10; input[i * 2 + 1] = 0x27; }
            PcmResampler converter = new PcmResampler(rate, 16000);
            int bytes = 0;
            for (int at = 0; at < input.length; at += 200) {
                int count = Math.min(200, input.length - at);
                byte[] chunk = java.util.Arrays.copyOfRange(input, at, at + count);
                byte[] output = converter.convert(chunk, chunk.length);
                bytes += output.length;
                for (int i = 0; i < output.length; i += 2) assertEquals(10000, (short) ((output[i] & 255) | (output[i + 1] << 8)));
            }
            assertTrue("Duration drift at " + rate, Math.abs(bytes / 2 - 16000) <= 2);
        }
    }
}
