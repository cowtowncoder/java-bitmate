package com.fasterxml.util.bitmate;

import java.io.FileInputStream;

public class NibblerEncoder
{
    public final static int MAX_CHUNK_SIZE = 0x2000; // that is, 8k

    /**
     * Even if nothing compresses, we only get 2 bytes for header
     * (which contains 
     * and then 2 byte length indicator
     */
    public final static int MAX_OVERHEAD_BYTES = 4;

    public final static int MAX_OUTPUT_BUFFER = MAX_CHUNK_SIZE + MAX_OVERHEAD_BYTES;
    
    protected byte[] _input;

    protected byte[] _output;

    protected int _inputEnd;
    
    // Pointer to point after last byte actually output
    protected int _outputPtr;

    // 8-bit value that constitutes continuation of the match
    protected int _matchByte = 0x0; // starts with clear bits

    /**
     * Pointer to position where there is room for 4-bit nibble in
     * LSB; 0 if, no such place available.
     */
    protected int _nibblePtr;
    
    /**
     * @return Offset right after last encoded byte
     */
    public int encode(byte[] input, int inputPtr, int inputLen,
            byte[] output, int outputPtr)
    {
        _validate(input, inputPtr, inputLen, output, outputPtr);
        
        _input = input;
        _output = output;
        _inputEnd = inputPtr + inputLen;
        _nibblePtr = 0;

        // First things first: 3 possible start conditions
        int ch = input[inputPtr++];
        final int outputStart = outputPtr+2;

        _outputPtr = outputStart; // to reserve room for 2 header bytes

        int marker;
        if ((ch == 0) || (ch == -1)) { // "run" of all-zero-bits/all-one-bits; at
            // this point even one is enough to warrant write (no minimum)
            int repeats = _findRunLength(inputPtr, ch); // one less than full length
            marker = (ch == 0) ? 0x0 : 0x40;
            _outputPtr = outputPtr;
            _writeRunLength(repeats);
            // also possible, if unlikely, that we are all done now
            inputPtr += repeats;
            if (inputPtr == _inputEnd) {
                outputPtr = _outputPtr;
                int encodedLength = outputPtr - outputStart;
                _output[outputStart-2] = (byte) (marker + (encodedLength >> 8));
                _output[outputStart-1] = (byte) encodedLength;
                return outputPtr;
            }
            ch = input[inputPtr++];
        } else { // otherwise run of literals. Only need to encode header, then go 
            marker = 0x80;
        }
        // On to scheduled programming; got a literal run of at least one byte...
        outputPtr = _encode2(inputPtr, ch);
        int encodedLength = outputPtr - outputStart;
        _output[outputStart-2] = (byte) (marker + (encodedLength >> 8));
        _output[outputStart-1] = (byte) encodedLength;
        return outputPtr;
    }

    /**
     * Second-level method, starts with a literal-run of length of at least 1 byte
     */
    protected int _encode2(int inputPtr, int ch)
    {
        // let's separate handling of last 3 bytes
        final int lastStart = _inputEnd - 3;

        // reserve room for one length byte iff no room for nibble.
        int startOutputOffset = _outputPtr;
        if (_nibblePtr == 0) {
            ++_outputPtr;
        }
        int count = 1;
        _output[_outputPtr++] = (byte) ch;
        int match = ((ch & 1) == 1) ? -1 : 0x0;

        while (true) {
            if (inputPtr > lastStart) {
                return _encodeTailLiterals(inputPtr, ch, startOutputOffset, count);
            }
            ch = _input[inputPtr++];
            // first, simple case; can't start a new run, so just copy
            if (ch != match) {
                _output[_outputPtr++] = (byte) ch;
                ++count;
                match = ((ch & 1) == 1) ? -1 : 0x0;
                continue;
            }
            // otherwise got first byte of possible run; 2 more needed
            ch = _input[inputPtr++];
            if (ch != match) {
                // NOTE: need to copy first byte too
                _output[_outputPtr++] = (byte) match;
                _output[_outputPtr++] = (byte) ch;
                count += 2;
                match = ((ch & 1) == 1) ? -1 : 0x0;
                continue;
            }
            // one more?
            ch = _input[inputPtr++];
            if (ch != match) {
                _output[_outputPtr++] = (byte) match;
                _output[_outputPtr++] = (byte) match;
                _output[_outputPtr++] = (byte) ch;
                count += 3;
                match = ((ch & 1) == 1) ? -1 : 0x0;
                continue;
            }

            // Ok: got a run. First need to update literal length indicator,
            // with its shuffling. Minimal length is just 1, unlike with runs.
            _writeLiteralLength(count - 1, startOutputOffset);

            // After which we'll figure actual length of all-one/all-zero run
            int repeats = _findRunLength(inputPtr, ch); // one less than full length
            _writeRunLength(repeats); // ignore based of 3
            // also possible, if unlikely, that we are all done now
            inputPtr += repeats;
            if (inputPtr == _inputEnd) { // reached the end?
                return _outputPtr;
            }
            ch = _input[inputPtr++];
            startOutputOffset = _outputPtr;
            if (_nibblePtr == 0) {
                ++_outputPtr;
            }
            count = 1;
            _output[_outputPtr++] = (byte) ch;
            match = ((ch & 1) == 1) ? -1 : 0x0;
        }
    }

    protected int _encodeTailLiterals(int inputPtr, int ch, int startOutputOffset, int count)
    {
        // could try something more complex, but for now maybe simplest to simply
        // extend current literal segment till end
        while (inputPtr < _inputEnd) {
            _output[_outputPtr++] = _input[inputPtr++];
            ++count;
        }
        _writeLiteralLength(count - 1, startOutputOffset);
        return _outputPtr;
    }

    /**
     * Method called to append run length indicator for a sequence of literal bytes.
     * Bit more complicated than one for one/zero-runs because in case of 16-bit
     * length we will need to shuffle one of literal bytes.
     *
     * @param lengthInd Modified length indicator to use
     * @param startOutputOffset Offset of the first copied literal
     */
    protected void _writeLiteralLength(int lengthInd, int startOutputOffset)
    {
        if (lengthInd <= 0x7) { // 3-bit into nibble (4-bit)
            // room for nibble?
            int nptr = _nibblePtr;
            if (nptr != 0) {
                int value = _output[nptr];
                _output[nptr] = (byte) (value | lengthInd);
                _nibblePtr = 0; // no room any more
            } else {
                // no; now the empty byte left before first literal comes in handy:
                nptr = startOutputOffset-1;
                _output[nptr] = (byte)(lengthInd << 4);
                _nibblePtr = nptr;
            }
            return;
        }
        // 6-bit value into 8-bit, 2 nibble slots
        if (lengthInd <= 71) {
            lengthInd -= 8;
            // prefix with marker
            lengthInd = (lengthInd - 8) | 0x80;

            // room for nibble? If so, use one, produce another one.
            int nptr = _nibblePtr;
            if (nptr != 0) {
                int value = _output[nptr];
                _output[nptr] = (byte) (value | (lengthInd >> 4));
                // and produce another nibble slot...
                nptr = startOutputOffset-1;
                _output[nptr] = (byte) (lengthInd << 4);
                _nibblePtr = nptr;
            } else { // but if not, simpler, just append length indicator as is
                 nptr = startOutputOffset-1;
                _output[nptr] = (byte) lengthInd;
            }
            return;
        }
        // 13-bit into two 8-bit slots
        // but first sanity check
        if (lengthInd >= MAX_CHUNK_SIZE) {
            throw new IllegalStateException("Internal error: trying to write length "+lengthInd);
        }
        // note: we won't modify length indicator any further; this leaves values (0-71) as
        // indicators that should not be used
        lengthInd = (lengthInd - 8) | 0xC0;
        // still, nibble-alignment needs to be followed
        int nptr = _nibblePtr;
        if (nptr != 0) {
            // got nibble; do nibble, full-byte, another nibble
            int value = _output[nptr];
            _output[nptr] = (byte) (value | (lengthInd >> 12));
            // then the full byte
            _output[startOutputOffset-1] = (byte) (lengthInd >> 4);
            // and produce another nibble slot, but this one requires moving the
            // first byte output to go after last otherwise written, and then using
            // "free" slot for nibble
            _output[_outputPtr++] = _output[startOutputOffset];
            _output[startOutputOffset] = (byte) ((lengthInd & 0xF) << 4);
            _nibblePtr = startOutputOffset;
        } else { // no nibble. Can use pre-allocated one, but then need to do the shuffle
           _output[startOutputOffset-1] = (byte) (lengthInd >> 8);
           _output[_outputPtr++] = _output[startOutputOffset];
           _output[startOutputOffset] = (byte) lengthInd;
        }
    }

    /**
     * Method called to append run length indicator for all-zero/all-one runs.
     * Note that it is not used for literals because literal indicator may need
     * different shuffling.
     *
     * @param lengthInd Physical count indicator to write, possibly NOT the actual count indicator;
     *    caller is free to offset differently, and does so (first run vs later)
     */
    protected void _writeRunLength(int lengthInd) 
    {
        if (lengthInd <= 0x7) { // 3-bit into nibble (4-bit)
            // room for nibble?
            int nptr = _nibblePtr;
            if (nptr != 0) {
                int value = _output[nptr];
                _output[nptr] = (byte) (value | lengthInd);
                _nibblePtr = 0; // no room any more
            } else {
                // no, append, leave room for nibble
                nptr = _outputPtr;
                _output[nptr] = (byte)(lengthInd << 4);
                _nibblePtr = nptr;
                _outputPtr = nptr + 1;
            }
            return;
        }
        // 6-bit value into 8-bit, 2 nibble slots
        if (lengthInd <= 71) {
            lengthInd -= 8;
            // prefix with marker
            lengthInd = (lengthInd - 8) | 0x80;

            // room for nibble? If so, use one, produce another one.
            int nptr = _nibblePtr;
            if (nptr != 0) {
                int value = _output[nptr];
                _output[nptr] = (byte) (value | (lengthInd >> 4));
                // and produce another nibble slot...
                nptr = _outputPtr;
                _output[nptr] = (byte) (lengthInd << 4);
                _nibblePtr = nptr;
                _outputPtr = nptr + 1;
            } else { // but if not, simpler, just append lenght indicator as is
                 nptr = _outputPtr;
                _output[nptr] = (byte) lengthInd;
                _outputPtr = nptr + 1;
            }
            return;
        }
        // 13-bit into two 8-bit slots
        // but first sanity check
        if (lengthInd >= MAX_CHUNK_SIZE) {
            throw new IllegalStateException("Internal error: trying to write length "+lengthInd);
        }
        // note: we won't modify length indicator any further; this leaves values (0-71) as
        // indicators that should not be used
        lengthInd = (lengthInd - 8) | 0xC0;
        // still, nibble-alignment needs to be followed
        int nptr = _nibblePtr;
        if (nptr != 0) {
            // got nibble; do nibble, full-byte, another nibble
            int value = _output[nptr];
            _output[nptr] = (byte) (value | (lengthInd >> 12));
            // then the full byte
            nptr = _outputPtr;
            _output[nptr++] = (byte) (lengthInd >> 4);
            // and produce another nibble slot...
            _output[nptr] = (byte) ((lengthInd & 0xF) << 4);
            _nibblePtr = nptr;
            _outputPtr = nptr + 1;
        } else { // no nibble; just append two full bytes
            nptr = _outputPtr;
           _output[nptr++] = (byte) (lengthInd >> 8);
           _output[nptr++] = (byte) lengthInd;
           _outputPtr = nptr;
        }
    }

    protected int _findRunLength(int ptr, int ch)
    {
        final int start = ptr;

        for (final int end = _inputEnd; ptr < end; ) {
            if (_input[ptr] != ch) {
                break;
            }
            ++ptr;
        }
        return (ptr - start);
    }

    protected void _validate(byte[] input, int inputPtr, int inputLen,
            byte[] output, int outputPtr)
        throws IllegalArgumentException
    {
        // bit of sanity checking to ensure that, first, input is valid
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        if (inputLen < 0) {
            throw new IllegalArgumentException("missing input, inputLen = "+inputLen);
        }
        if (inputLen > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("invalid inputLen ("+inputLen
                    +"), exceeds max chunk size of "+MAX_CHUNK_SIZE);
        }
        if ((inputPtr < 0) || (inputPtr + inputLen) > input.length) {
            throw new IllegalArgumentException("invalid inputPtr ("+inputPtr+") and/or inputLen ("
                    +inputLen+"), for input buffer of size "+input.length);
        }
        // and then that output buffer, pointer, valid as well
        if (output == null) {
            throw new IllegalArgumentException("null output");
        }
        if (outputPtr < 0) {
            throw new IllegalArgumentException("invalid outputPtr ("+outputPtr+")");
        }
        int maxSize = inputLen + MAX_OVERHEAD_BYTES;
        if ((outputPtr + maxSize) > output.length) {
            throw new IllegalArgumentException("invalid outputPtr ("+outputPtr+"), with inputLen ("
                    +inputLen+", output buffer of size "+input.length
                    +": max size of encoded content: "+maxSize);
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
        byte[] input = new byte[MAX_CHUNK_SIZE];
        byte[] output = new byte[MAX_CHUNK_SIZE + MAX_OVERHEAD_BYTES];
        final NibblerEncoder enc = new NibblerEncoder();

        int totalInput = 0;
        int totalOutput = 0;
        int chunks = 0;

        int count;
        while ((count = in.read(input, 0, input.length)) == MAX_CHUNK_SIZE) {
            ++chunks;
            totalInput += count;

            int outLen = enc.encode(input, 0, count, output, 0);
            totalOutput += outLen;
        }
        in.close();

        if (count > 0) {
            ++chunks;
            totalInput += count;

            int outLen = enc.encode(input, 0, count, output, 0);
            totalOutput += outLen;
        }

        System.out.printf("Completed: read %.1fkB, wrote %.1fkB (in %d chunks), ratio %.2f%%\n",
                totalInput / 1024.0,
                totalOutput / 1024.0,
                chunks,
                100.0 * (double) totalOutput / (double) totalInput
                );
    }
}
