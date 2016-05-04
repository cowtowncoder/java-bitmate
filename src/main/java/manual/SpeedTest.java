package manual;

import java.io.IOException;
import java.util.Map;

// Simple comparison of performance of bitrat vs nibbler compressions
public class SpeedTest extends ToolBase
{
    private SpeedTest() { }

    public static void main(String[] args) throws IOException
    {
        if (args.length < 1) {
            System.err.println("Usage: java ... "+SpeedTest.class.getName()+" <input json file>");
            System.exit(1);
        }
        new SpeedTest().run(args[0]);
    }

    protected void run(String filename) throws IOException
    {
        Bitsets bitsets = readBitsets(filename);
        final int rows = bitsets.rowCount;

        // First, run through as warm up, couple of times

        for (int i = 0; i < 5; ++i) {
            for (Map.Entry<String,BitsetRecord> entry : bitsets.bitsets.entrySet()) {
                BitsetRecord r = entry.getValue();
                byte[] rawSet = r.presence;
                ratCompress(rawSet);
                nibblerCompress(rawSet);
            }
        }

        // to get close to 1M of encoded data, use
        final int REPS = 80;
        // and then second time, with timings
        for (Map.Entry<String,BitsetRecord> entry : bitsets.bitsets.entrySet()) {
            BitsetRecord r = entry.getValue();
            byte[] rawSet = r.presence;

            int nibblerSize = 0;
            int ratSize = 0;
            long nanos1 = System.nanoTime();
            for (int i = 0; i < REPS; ++i) {
                ratSize = ratCompress(rawSet);
            }
            long nanos2 = System.nanoTime();

            for (int i = 0; i < REPS; ++i) {
                nibblerSize = nibblerCompress(rawSet);
            }
            long nanos3 = System.nanoTime();

            double msecs1 = (nanos2 - nanos1) / 1000000.0;
            double msecs2 = (nanos3 - nanos2) / 1000000.0;

            double pct = 100.0 * (double) r.set / (double) rows;
            String pctDesc = (pct < 1.0) ? String.format("%.1f%%(%db)", pct, r.set)
                    : String.format("%.2f%%", pct);
            System.out.printf("Column '%s' (%s):", entry.getKey(), pctDesc);            
            System.out.printf(" compress %s/%s",
                    _length(ratSize), _length(nibblerSize));
            System.out.printf("; time %.2f / %.2f msec (bitrat/nibbler)",
                    msecs1, msecs2);
            System.out.println();
        }
    }
}
