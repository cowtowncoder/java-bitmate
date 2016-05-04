package manual;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.util.bitmate.BitRatEncoder;
import com.fasterxml.util.bitmate.NibblerEncoder;

public class ToolBase
{
    static class Bitsets {
        public int rowCount;
        public int columnCount;
        public Map<String,BitsetRecord> bitsets;
    }

    static class BitsetRecord {
        public int set; // count of set bits
        public byte[] presence;
    }
    
    static MessageDigest _sha1;
    static {
        try {
            _sha1 = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    final static ObjectMapper JSON_MAPPER = new ObjectMapper();
    static {
        JSON_MAPPER.enable(JsonParser.Feature.ALLOW_COMMENTS);
    }

    protected Bitsets readBitsets(String filename) throws IOException
    {
        Bitsets bs = JSON_MAPPER.readValue(new File(filename), Bitsets.class);
        final int rows = bs.rowCount;
        System.out.printf("Read %d records, with %d columns\n", rows, bs.columnCount);

        Set<String> seenResults = new HashSet<>();

        boolean firstEmpty = true;

        Iterator<Map.Entry<String,BitsetRecord>> it = bs.bitsets.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String,BitsetRecord> entry = it.next();
            BitsetRecord r = entry.getValue();
            byte[] rawSet = r.presence;
            if (rawSet == null) {
                if (firstEmpty) {
                    firstEmpty = false;
                    BitSet full = new BitSet();
                    full.set(0, rows);
                    rawSet = full.toByteArray();
                    r.presence = rawSet;
                } else {
                    it.remove();
                }
            } else {
                // Let's reduce noise by only using unique results:
                if (!seenResults.add(sha1(rawSet))) {
                    it.remove();
                }
            }
        }
        System.out.printf("... of which %d unique.\n", bs.bitsets.size());
        return bs;
    }

    static int compressedLengthLZF(byte[] data) {
        return com.ning.compress.lzf.LZFEncoder.encode(data).length;
    }

    static int compressedLengthGzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(10 + data.length>>2);
        try {
            OutputStream gzip = new com.ning.compress.gzip.OptimizedGZIPOutputStream(bytes);
            gzip.write(data);
            gzip.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytes.size();
    }

    final byte[] _ratInput = new byte[4096];
    
    int ratCompress(byte[] data)
    {
        byte[] output = new byte[4096 + 100];
        final BitRatEncoder enc = new BitRatEncoder();
        int i = 0;
        int left = data.length;
        int totalOutput = 0;

        for (; left >= 4096; i += 4096, left -= 4096) {
            System.arraycopy(data, i, _ratInput, 0, 4096);
            enc.encodeFullChunk(false, _ratInput, output, 0);
            totalOutput += enc.getOutputPtr() + ratOverheadPerChunk();
        }
        if (left > 0) {
            System.arraycopy(data, i, _ratInput, 0, left);
            enc.encodePartialChunk(false, _ratInput, left, output, 0);
            totalOutput += enc.getOutputPtr() + ratOverheadPerChunk();
        }
        return totalOutput;
    }

    static int ratOverheadPerChunk() {
        // one byte for bit mask, 2-byte length indicator
        return 1 + 2;
    }
    
    final byte[] _nibblerOutput = new byte[NibblerEncoder.MAX_OUTPUT_BUFFER];
    
    int nibblerCompress(byte[] data)
    {
        final int CHUNK_LEN = NibblerEncoder.MAX_CHUNK_SIZE;
        final NibblerEncoder enc = new NibblerEncoder();
        int i = 0;
        int left = data.length;
        int totalOutput = 0;

        for (; left >= CHUNK_LEN; i += CHUNK_LEN, left -= CHUNK_LEN) {
            int outBytes = enc.encode(data, i, CHUNK_LEN, _nibblerOutput, 0);
            totalOutput += outBytes;
        }
        if (left > 0) {
            int outBytes = enc.encode(data, i, left, _nibblerOutput, 0);
            totalOutput += outBytes;
        }
        return totalOutput;
    }

    static String _length(int length) {
        if (length < 2048) {
            return String.format("%db", length);
        }
        if (length < (100 * 1024)) {
            return String.format("%.2fkB", length/1024.0);
        }
        return String.format("%.1fkB", length/1024.0);
    }
    
    static String sha1(byte[] data) {
        try {
            return new String(_sha1.digest(data), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
