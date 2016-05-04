package com.fasterxml.util.bitmate;

import java.util.Arrays;

import com.fasterxml.util.bitmate.BitRatEncoder;

public class BitmapEncoderTest extends ModuleTestBase
{
    public void testFixLast()
    {
        // with 4 bits in last byte, last bit is 1, so should fill
        _verifyFixLast(0xF0, 4, 0xFF);
        _verifyFixLast(0xF0, 5, 0xF0);
        _verifyFixLast(0x80, 1, 0xFF);
    }
    
    private void _verifyFixLast(int base, int lastBits, int exp)
    {
        byte[] b = new byte[] { (byte) base };
        BitRatEncoder._fixLast(b, 0, lastBits);
        assertEquals(exp, b[0] & 0xFF);

        // also verify it works with offset too
        b = new byte[3];
        b[1] = (byte) base;
        BitRatEncoder._fixLast(b, 1, lastBits);
        assertEquals(exp, b[1] & 0xFF);
        // and that no change occurs for adjacent values
        assertEquals(0, b[0]);
        assertEquals(0, b[2]);
    }

    public void testLevel1Compressing()
    {
        byte[] input = new byte[32];
        BitRatEncoder encoder = new BitRatEncoder();

        // with empty contents should just get straight run
        
        int result = encoder._encodeFullLevel1(0);
        assertEquals(0, result);
        // but has now consumed input...
        assertEquals(32, encoder._inputPtr);
        assertEquals(0, encoder._outputTail);
        assertEquals(0, encoder._matchLevel1);

        // with all 1s, bit different due to assumption of starting with '0'
        input = new byte[32];
        Arrays.fill(input, (byte) 0xFF);
        encoder = new BitRatEncoder();
        result = encoder._encodeFullLevel1(0);
        // 4-bit mask with first one set
        assertEquals(0x8, result);
        assertEquals(32, encoder._inputPtr);
        assertEquals(2, encoder._outputTail); // 1 changed bit plus the prefix
        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0xFF, encoder._output[1] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);

        // and more, with 16 0s, 16 1s, yet different
        input = new byte[32];
        Arrays.fill(input, 16, 32, (byte) 0xFF);
        encoder = new BitRatEncoder();
        result = encoder._encodeFullLevel1(0);
        assertEquals(0x02, result);
        assertEquals(32, encoder._inputPtr);
        assertEquals(2, encoder._outputTail); // 1 changed bit plus the prefix
        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0xFF, encoder._output[1] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);
    }

    // Test that verifies our optimization for not-well-enough compression
    public void testLevel1NotEnoughCompressible()
    {
        byte[] input = new byte[32];
        BitRatEncoder encoder = new BitRatEncoder();

        input = new byte[32];

        // make first and last two compressible; still not enough (34 bytes, vs 33 with 'no-comp')
        Arrays.fill(input, (byte) 0xAA);
        input[0] = 0;
        input[30] = input[31] = -1;
        
        encoder = new BitRatEncoder();
        int result = encoder._encodeFullLevel1(0);
        assertEquals(0xF, result);
        assertEquals(32, encoder._inputPtr);
        // Without optimization would be 36, with optimization 33
        assertEquals(33, encoder._outputTail);
        // and the marker we have is 0x0, otherwise illegal as marker (that is,
        // never produced by encoder)
        assertEquals(0x00, encoder._output[0] & 0xFF);
        assertEquals(0x00, encoder._output[1] & 0xFF); // first byte
        for (int i = 2; i <= 30; ++i) {
            assertEquals(0xAA, encoder._output[i] & 0xFF);
        }
        assertEquals(0xFF, encoder._output[31] & 0xFF); // second-to-last byte
        assertEquals(0xFF, encoder._output[32] & 0xFF); // last byte
    }

    // And test for stuff that does not have any detectable runs (fully non-compressible)
    public void testLevel1NonCompressible()
    {
        byte[] input = new byte[32];
        BitRatEncoder encoder = new BitRatEncoder();

        // Test with zigzag pattern
        input = new byte[32];
        Arrays.fill(input, (byte) 0xAA);
        encoder = new BitRatEncoder();
        int result = encoder._encodeFullLevel1(0);
        assertEquals(0xF, result);
        assertEquals(32, encoder._inputPtr);
        // Without optimization would be 36, with optimization 33
        assertEquals(33, encoder._outputTail);
        // and the marker we have is 0x0, otherwise illegal as marker (that is,
        // never produced by encoder)
        assertEquals(0x00, encoder._output[0] & 0xFF);
        for (int i = 1; i <= 33; ++i) {
            assertEquals(0xAA, encoder._output[i] & 0xFF);
        }
        
        // the very last bit is 0 so
        assertEquals(0x00, encoder._matchLevel1);

        // and just to ensure bit is properly checked
        input = new byte[32];
        Arrays.fill(input, (byte) 0x55);
        encoder = new BitRatEncoder();
        result = encoder._encodeFullLevel1(0);
        assertEquals(0xF, result);
        assertEquals(32, encoder._inputPtr);
        assertEquals(33, encoder._outputTail);
        // the very last bit is now 1:
        assertEquals(0xFF, encoder._matchLevel1);
        assertEquals(0x00, encoder._output[0] & 0xFF);
        for (int i = 1; i <= 33; ++i) {
            assertEquals(0x55, encoder._output[i] & 0xFF);
        }
    }

    public void testLevel2Compressing()
    {
        final int BYTES = 32 * 16;
        byte[] input;
        BitRatEncoder encoder;
        int result;

        // with empty contents should just get straight run
        input = new byte[BYTES];
        encoder = new BitRatEncoder();
        assertEquals(0, encoder._encodeFullLevel2(0));
        // but has now consumed input...
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0, encoder._outputTail);
        assertEquals(0, encoder._matchLevel1);

        // with all 1s, bit different due to assumption of starting with '0'
        input = new byte[BYTES];
        Arrays.fill(input, (byte) 0xFF);
        encoder = new BitRatEncoder();
        // 8-bit mask with first one set
        assertEquals(0x80, encoder._encodeFullLevel2(0));
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(3, encoder._outputTail); // 1 actual byte at low level, 2 levels of masks
        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0x80, encoder._output[1] & 0xFF);
        assertEquals(0xFF, encoder._output[2] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);

        // and more, with half 0s, followed by half 1s
        input = new byte[BYTES];
        Arrays.fill(input, BYTES/2, input.length, (byte) 0xFF);
        encoder = new BitRatEncoder();
        // should not be completely empty...
        result = encoder._encodeFullLevel2(0);
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0x08, result);
        assertEquals(3, encoder._outputTail); // 1 data byte, plus 2 levels of prefixes
        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0x80, encoder._output[1] & 0xFF);
        assertEquals(0xFF, encoder._output[2] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);
    }

    public void testLevel2NotCompressing()
    {
        final int BYTES = 32 * 16;
        byte[] input;
        BitRatEncoder encoder;

        // if non-compressing, should just get sets of literals
        input = new byte[BYTES];
        Arrays.fill(input, (byte)0xAA);
        encoder = new BitRatEncoder();
        assertEquals(0xFF, encoder._encodeFullLevel2(0));
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0, encoder._matchLevel1); // ends with 0-bit

        assertEquals(BYTES, encoder._inputPtr);
        // 1/32 for level 1 (optimized from 1/8); further 1/64 from level 2
        assertEquals(BYTES + BYTES/32 + BYTES/64, encoder._outputTail);

        int ptr = 0;

        byte[] outBuf = encoder._output;
        for (int level2 = 0; level2 < 8; ++level2) { // 8 sections on level 2
            assertEquals(0xFF, outBuf[ptr++] & 0xFF); // mask bits full at level2
            assertEquals(0, outBuf[ptr++] & 0xFF); // short-cut marker to indicate "all 32"
            for (int i = 0; i < 32; ++i) {
                assertEquals(0xAA, outBuf[ptr++] & 0xFF); // actual data bytes
            }
            assertEquals(0, outBuf[ptr++] & 0xFF);
            for (int i = 0; i < 32; ++i) {
                assertEquals(0xAA, outBuf[ptr++] & 0xFF); // actual data bytes
            }
        }
    }

    public void testLevel3Compressing()
    {
        final int BYTES = 32 * 16 * 8; // 4k
        byte[] input;
        final byte[] output = new byte[BYTES + BYTES/8];
        BitRatEncoder encoder;
        int result;

        // with empty contents should just get straight run
        input = new byte[BYTES];
        encoder = new BitRatEncoder();
        assertEquals(0, encoder.encodeFullChunk(false, input, output, 0));
        // but has now consumed input...
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0, encoder._outputTail);
        assertEquals(0, encoder._matchLevel1);

        // with all 1s, bit different due to assumption of starting with '0'
        input = new byte[BYTES];
        Arrays.fill(input, (byte) 0xFF);
        encoder = new BitRatEncoder();
        // 8-bit mask with first one set
        assertEquals(0x80, encoder.encodeFullChunk(false, input, output, 0));
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(4, encoder._outputTail); // 1 actual byte at low level, 3 levels of masks
        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0x80, encoder._output[1] & 0xFF);
        assertEquals(0x80, encoder._output[2] & 0xFF);
        assertEquals(0xFF, encoder._output[3] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);

        // and more, with half 0s, followed by half 1s
        input = new byte[BYTES];
        Arrays.fill(input, BYTES/2, input.length, (byte) 0xFF);
        encoder = new BitRatEncoder();
        // should not be completely empty...
        result = encoder.encodeFullChunk(false, input, output, 0);
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0x08, result);
        assertEquals(4, encoder._outputTail); // 1 data byte, plus 2 levels of prefixes
        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0x80, encoder._output[1] & 0xFF);
        assertEquals(0x80, encoder._output[2] & 0xFF);
        assertEquals(0xFF, encoder._output[3] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);
    }

    public void testLevel3NotCompressing()
    {
        final int BYTES = 32 * 16 * 8; // 4k
        byte[] input;
        BitRatEncoder encoder;

        // if non-compressing, should just get sets of literals
        input = new byte[BYTES];
        byte[] output = new byte[BYTES + BYTES/8];
        Arrays.fill(input, (byte)0xAA);
        encoder = new BitRatEncoder();
        assertEquals(0xFF, encoder.encodeFullChunk(false, input, output, 0));
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0, encoder._matchLevel1); // ends with 0-bit

        assertEquals(BYTES, encoder._inputPtr);
        // 1/32 for level 1 (optimized from 1/8); further 1/64 from level 2; and 1/512 for level 3
        assertEquals(BYTES + BYTES/32 + BYTES/64 + BYTES/512, encoder._outputTail);

        int ptr = 0;

        byte[] outBuf = encoder._output;
        for (int level3 = 0; level3 < 8; ++level3) {
            assertEquals(0xFF, outBuf[ptr++] & 0xFF); // mask bits full at level3
            for (int level2 = 0; level2 < 8; ++level2) { // 8 sections on level 2
                assertEquals(0xFF, outBuf[ptr++] & 0xFF); // mask bits full at level2
                assertEquals(0, outBuf[ptr++] & 0xFF); // short-cut marker to indicate "all 32"
                for (int i = 0; i < 32; ++i) {
                    assertEquals(0xAA, outBuf[ptr++] & 0xFF); // actual data bytes
                }
                assertEquals(0, outBuf[ptr++] & 0xFF);
                for (int i = 0; i < 32; ++i) {
                    assertEquals(0xAA, outBuf[ptr++] & 0xFF); // actual data bytes
                }
            }
        }
    }

    // Test to check that it is possible to encode partial chunks too
    public void testLevel3Partial()
    {
        final int BYTES = 512 + 64 + 8 + 3;
        byte[] input;
        byte[] output = new byte[BYTES + 100];
        BitRatEncoder encoder;
        int result;

        // with empty contents should just get straight run
        input = new byte[BYTES];
        encoder = new BitRatEncoder();
        assertEquals(0, encoder.encodePartialChunk(false, input, BYTES, output, 0));
        // but has now consumed input...
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(0, encoder._outputTail);
        assertEquals(0, encoder._matchLevel1);

        // with all 1s, bit different due to assumption of starting with '0'
        input = new byte[BYTES];
        Arrays.fill(input, (byte) 0xFF);
        encoder = new BitRatEncoder();
        // 8-bit mask with first one set
        assertEquals(0x80, encoder.encodePartialChunk(false, input, BYTES, output, 0));
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(4, encoder._outputTail); // 1 actual byte at low level, 3 levels of masks

        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0x80, encoder._output[1] & 0xFF);
        assertEquals(0x80, encoder._output[2] & 0xFF);
        assertEquals(0xFF, encoder._output[3] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);

        // and more, with half 0s, followed by half 1s
        input = new byte[BYTES];
        Arrays.fill(input, 512, input.length, (byte) 0xFF);
        encoder = new BitRatEncoder();
        // should not be completely empty...
        result = encoder.encodePartialChunk(false, input, BYTES, output, 0);
        assertEquals(BYTES, encoder._inputPtr);
        assertEquals(4, encoder._outputTail); // 1 data byte, plus 2 levels of prefixes
        assertEquals(0x40, result);

        assertEquals(0x80, encoder._output[0] & 0xFF);
        assertEquals(0x80, encoder._output[1] & 0xFF);
        assertEquals(0x80, encoder._output[2] & 0xFF);
        assertEquals(0xFF, encoder._output[3] & 0xFF);
        assertEquals(0xFF, encoder._matchLevel1);
    }

}
