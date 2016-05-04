package com.fasterxml.util.bitmate;

import java.io.*;

public class BitRatEncoder
{
    public final static int FULL_CHUNK_SIZE = 0x1000; // that is, 4k

    public final static int LEVEL2_CHUNK_SIZE = 512;

    private final static byte ZERO_BYTE = 0;

    /**
     * Buffer from which input to encode is read.
     */
    protected byte[] _input;

    protected byte[] _output;

    protected int _inputPtr;
    
    // Pointer to point after last byte actually output
    protected int _outputTail;

    // 8-bit value that constitutes continuation of the match
    protected int _matchLevel1 = 0x0; // starts with clear bits

    public BitRatEncoder() { }

    /*
    /**********************************************************************
    /* Public API, accessors
    /**********************************************************************
     */

    public int getInputPtr() { return _inputPtr; }
    public int getOutputPtr() { return _outputTail; }

    public boolean wasLastBitSet() {
        return (_matchLevel1 != 0);
    }

    /*
    /**********************************************************************
    /* Public API, encoding
    /**********************************************************************
     */
    
    /**
     * Top-level compressor that calls {@link #_encodeFullLevel2} to handle 8 of 512-byte chunks,
     * resulting in 4k input blocks, with 8-bit mask to indicate which chunks contain
     * literal bytes.
     * 
     * @return Byte mask indicating which of 512 chunks (of 4k input) have literal bytes;
     *    this is needed for decoding.
     */
    public int encodeFullChunk(boolean prevBit, byte[] input,
            byte[] output, int outputPtr)
    {
        _input = input;
        _output = output;
        _outputTail = outputPtr;
        _matchLevel1 = prevBit ? 0xFF : 0x0;
        _inputPtr = 0;

        // Let's do this unrolled:
        int resultMask = _encodeFullLevel2(outputPtr+1);
        if (resultMask != 0) { // had output, so prepend mask
            _output[outputPtr] = (byte) resultMask;
            resultMask |= 0x80;
            outputPtr = _outputTail;
        }

        // and then 7 more times
        int mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x40;
            outputPtr = _outputTail;
        }
        mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x20;
            outputPtr = _outputTail;
        }
        mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x10;
            outputPtr = _outputTail;
        }
        mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x08;
            outputPtr = _outputTail;
        }
        mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x04;
            outputPtr = _outputTail;
        }
        mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x02;
            outputPtr = _outputTail;
        }
        mask = _encodeFullLevel2(outputPtr+1);
        if (mask != 0) {
            _output[outputPtr] = (byte) mask;
            resultMask |= 0x01;
            outputPtr = _outputTail;
        }
        return resultMask;
    }

    /**
     * Alternative output method called when content to decode 
     */
    public int encodePartialChunk(boolean prevBit, byte[] input, int inputLen,
            byte[] output, int outputPtr)
    {
        if (inputLen >= FULL_CHUNK_SIZE) {
            if (inputLen == FULL_CHUNK_SIZE) {
                return encodeFullChunk(prevBit, input, output, outputPtr);
            }
            throw new IllegalArgumentException(String.format(
                    "Invalid chunk size %d for partial output: should be less than %d",
                    inputLen, FULL_CHUNK_SIZE));
        }
        _input = input;
        _inputPtr = 0;
        _output = output;
        _outputTail = outputPtr;
        _matchLevel1 = prevBit ? 0xFF : 0x0;
        
        int resultMask = 0;
        int marker = 0x80;

        // Trickier to unroll, plus not as much point
        int left = inputLen;
        for (; left >= LEVEL2_CHUNK_SIZE; left -= LEVEL2_CHUNK_SIZE) {
            int mask = _encodeFullLevel2(outputPtr+1);
            if (mask != 0) { // had output, so prepend mask
                _output[outputPtr] = (byte) mask;
                resultMask |= marker;
                outputPtr = _outputTail;
            }
            marker >>= 1;
        }
        // Ok no more half-k (level 2) chunks. But may have remainders
        if (left > 0) {
            int mask = _encodePartialLevel2(outputPtr+1, left);
            if (mask != 0) {
                _output[outputPtr] = (byte) mask;
                resultMask |= marker;
                outputPtr = _outputTail;
            }
        }
        return resultMask;
    }

    /*
    /**********************************************************************
    /* Internal methods, full chunk encoding
    /**********************************************************************
     */
    
    /**
     * Second-level encoding function that delegates to {@link #_encodeFullLevel1}
     * for 32 byte chunks, calling it 8 x 2 times, for total block size of
     * 512 bytes, with up to 16 marker bytes. Returns 8 bit mask to indicate chunks
     * that are NOT empty.
     *
     * @return 8-bit mask of blocks produced
     */
    int _encodeFullLevel2(int outputPtr)
    {
        int resultMask = 0;

        // Need 8 loops of 8 bytes each, with each level1-call getting a nibble
        int rounds = 8;
        // First: leave room for one-byte byte marker
        while (true) {
            final int origOutputPtr = outputPtr;
            ++outputPtr;
            int mask = _encodeFullLevel1(outputPtr);
            if (mask != 0) { // not a full run, appended output
                mask <<= 4;
                outputPtr = _outputTail;
            }
            int lo = _encodeFullLevel1(outputPtr);
            if (lo != 0) {
                outputPtr = _outputTail;
                mask |= lo;
            }
            if (mask == 0) { // no output, reset position
                outputPtr = origOutputPtr;
            } else { // had output, so prepend mask
                _output[origOutputPtr] = (byte) mask;
                resultMask |= 1;
            }
            if (--rounds == 0) {
                break;
            }
            resultMask <<= 1;
        }
        return resultMask;
    }

    /**
     * Lowest-level encoding method for full blocks: handles 32 bytes, that is, 256 bits.
     * Contains one special optimization for "non-compressing" content. Note that
     * contents of return value will be written as prefix bytes, as necessary (zeroes
     * omitted).
     *
     * @return 4-bit mask to indicate which of potential 8-byte blocks are included (that is,
     *    have further presence bit and 1-8 bytes of underlying data).
     */
    int _encodeFullLevel1(int outputPtr)
    {
        int match = _matchLevel1;
        int resultBits = 0;
        int origOutputPtr = outputPtr; // to check whether compression achieved
        int inputPtr = _inputPtr;
        
        // Need 4 loops of 8 bytes each as prefixes are interleaved
        int rounds = 4;
        while (true) {
            final int baseOut = outputPtr;
            int mask = 0; // lowest-level mask for group of 8 bytes

            byte b = _input[inputPtr++];
            // Basic component, repeated 8 times: see if run continues; if not, output byte, add bit
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b; // important: advance first, to leave room for prefix
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x80;
            }
            // and then repeat 7 more times
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x40;
            }
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x20;
            }
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x10;
            }
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x08;
            }
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x04;
            }
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x02;
            }
            b = _input[inputPtr++];
            if ((b & 0xFF) != match) {
                _output[++outputPtr] = b;
                match = ((b & 0x1) == 0) ? 0 : 0xFF;
                mask |= 0x01;
            }

            // and then assess the situation: did we output anything?
            if (mask != 0) { // yup: add prefix mask
                _output[baseOut] = (byte) mask;
                ++outputPtr; // since it pointed to the last added byte
                resultBits |= 1;
            }
            if (--rounds == 0) {
                break;
            }
            resultBits <<= 1;
        }

        // Three main outcomes:
        //
        // (a) completely compressed out; nothing output, zero returned
        // (b) enough compression, left as is
        // (c) not enough compression; rewrite with single 0 byte, copy 32 literal bytes after
        //    (which is safe as zero bytes are never otherwise encoded, "parent-bit" above indicates zero/non-zero)
        
        // and then update settings unless we had full run
        if (resultBits != 0) { // (b) or (c)
            int amount = outputPtr - origOutputPtr;

            if (amount > 32) { // (c), need to re-do
                _output[origOutputPtr++] = ZERO_BYTE;
                System.arraycopy(_input, _inputPtr, _output, origOutputPtr, 32);
                outputPtr = origOutputPtr+32;
                // also ensure we declare everything to be non-compressed
                resultBits = 0xF;
            }
            // at lowest level we are sure to advance the pointer
            _outputTail = outputPtr;
            _matchLevel1 = match;
        }
        _inputPtr = inputPtr;
        return resultBits;
    }

    /*
    /**********************************************************************
    /* Internal methods, partial chunk encoding
    /**********************************************************************
     */

    /**
     * Alternate method used when output for chunk smaller than 512
     * bytes is needed.
     */
    int _encodePartialLevel2(int outputPtr, int chunkSize)
    {
        if (chunkSize >= LEVEL2_CHUNK_SIZE) { // just sanity check to ensure it is partial
            throw new IllegalArgumentException(String.format(
                    "Invalid chunk size %d for partial output: should be less than %d",
                    chunkSize, LEVEL2_CHUNK_SIZE));
        }
        int resultMask = 0;
        int marker = 0x80;

        int left = chunkSize;
        for (; left >= 64; left -= 64) {
            final int origOutputPtr = outputPtr;
            ++outputPtr;
            int mask = _encodeFullLevel1(outputPtr);
            if (mask != 0) { // not a full run, appended output
                mask <<= 4;
                outputPtr = _outputTail;
            }
            int lo = _encodeFullLevel1(outputPtr);
            if (lo != 0) {
                outputPtr = _outputTail;
                mask |= lo;
            }
            if (mask == 0) { // no output, reset position
                outputPtr = origOutputPtr;
            } else { // had output, so prepend mask
                _output[origOutputPtr] = (byte) mask;
                resultMask |= marker;
            }
            marker >>= 1;
        }
        // Ok no more 64 byte (level 3) chunks. But may have smaller leftovers still...
        if (left > 0) {
            int mask = _encodePartialLevel1(outputPtr+1, left);
            if (mask != 0) {
                _output[outputPtr] = (byte) resultMask;
                resultMask |= marker;
                outputPtr = _outputTail;
            }
        }
        return resultMask;
    }

    int _encodePartialLevel1(int outputPtr, int chunkSize)
    {
        if (chunkSize >= LEVEL2_CHUNK_SIZE) { // just sanity check to ensure it is partial
            throw new IllegalArgumentException(String.format(
                    "Invalid chunk size %d for partial output: should be less than %d",
                    chunkSize, LEVEL2_CHUNK_SIZE));
        }
        int resultMask = 0;
        int resultBit = 0x80;

        // First, full 8-byte chunks. Note that here we do NOT worry about
        // sub-optimal last chunk

        int match = _matchLevel1;
        int inputPtr = _inputPtr;
        
        int left = chunkSize;
        for (; left >= 8; left -= 8) {
            final int baseOut = outputPtr;

            int mask8 = 0;
            for (int bit8 = 0x80; bit8 != 0; bit8 >>= 1) {
                byte b = _input[inputPtr++];
                // Basic component, repeated 8 times: see if run continues; if not, output byte, add bit
                if ((b & 0xFF) != match) {
                    _output[++outputPtr] = b; // important: advance first, to leave room for prefix
                    match = ((b & 0x1) == 0) ? 0 : 0xFF;
                    mask8 |= bit8;
                }
            }
            // did we output any?
            if (mask8 != 0) { // yes, need to output prefix
                _output[baseOut] = (byte) mask8;
                ++outputPtr; // since it pointed to the last added byte
                resultMask |= resultBit;
            }
            resultBit >>= 1;
        }
        
        // and finally, individual bytes, if need be
        if (left > 0) {
            int mask8 = 0;
            int bit8 = 0x80;
            final int baseOut = outputPtr;

            while (--left >= 0) {
                byte b = _input[inputPtr++];
                // Basic component, repeated 8 times: see if run continues; if not, output byte, add bit
                if ((b & 0xFF) != match) {
                    _output[++outputPtr] = b; // important: advance first, to leave room for prefix
                    match = ((b & 0x1) == 0) ? 0 : 0xFF;
                    mask8 |= bit8;
                }
            }
            if (mask8 != 0) { // yes, need to output prefix
                _output[baseOut] = (byte) mask8;
                ++outputPtr; // since it pointed to the last added byte
                resultMask |= resultBit;
            }
        }

        // at lowest level we are sure to advance the pointer
        if (resultMask != 0){
            _outputTail = outputPtr;
            _matchLevel1 = match;
        }
        _inputPtr = inputPtr;
        return resultMask;
    }

    /*
    /**********************************************************************
    /* Internal helper methods
    /**********************************************************************
     */
    
    // Helper method for changing extra unused bits to be the same
    // as the last actual content bit; this to make sure last run
    // is not accidentally broken by garbage
    final static void _fixLast(byte[] input, int offset, int lastBits)
    {
        int old = input[offset];
        int shift = (8 - lastBits);
        boolean lastSet = ((old >> shift) & 1) != 0;
        int mask = 0xFF >> lastBits;
        int changed;
        if (lastSet) {
            changed = old | mask;
        } else {
            changed = old & ~mask;
        }
        if (changed != old) {
            input[offset] = (byte) changed;
        }
    }

    /*
    /**********************************************************************
    /* Testing
    /**********************************************************************
     */

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1) {
            System.err.println("Usage: java [class] <input-file>");
            System.exit(1);
        }

        FileInputStream in = new FileInputStream(args[0]);
        byte[] input = new byte[FULL_CHUNK_SIZE];
        byte[] output = new byte[FULL_CHUNK_SIZE + (FULL_CHUNK_SIZE >> 3)];
        final BitRatEncoder enc = new BitRatEncoder();

        int totalInput = 0;
        int totalOutput = 0;
        int chunks = 0;

        int count;
        while ((count = in.read(input, 0, input.length)) == FULL_CHUNK_SIZE) {
            ++chunks;
            totalInput += count;
            enc.encodeFullChunk(false, input, output, 0);
            totalOutput += 1 + enc.getOutputPtr();
        }
        in.close();

        if (count > 0) {
            ++chunks;
            totalInput += count;
            enc.encodePartialChunk(false, input, count, output, 0);
            // one extra byte at least as header
            totalOutput += 1 + enc.getOutputPtr();
        }

        System.out.printf("Completed: read %.1fkB, wrote %.1fkB (in %d chunks), ratio %.2f%%\n",
                totalInput / 1024.0,
                totalOutput / 1024.0,
                chunks,
                100.0 * (double) totalOutput / (double) totalInput
                );
    }
}
