package manual;

import java.text.DecimalFormat;

import org.roaringbitmap.RoaringBitmap;

public class RoaringExample
{
    public static final int universe_size = 262144;
    public static DecimalFormat F = new DecimalFormat("0.000");

    public static void testSuperSparse() {
      System.out.println("Sparse case... universe = [0,"+universe_size+"), "+(universe_size/8)+"b");
      RoaringBitmap r = new RoaringBitmap();
      int howmany = 100;
      int gap = universe_size / howmany;
      System.out.println("Adding "+howmany+" values separated by gaps of "+gap+ "...");
      System.out.println("As a bitmap it would look like 1000...001000... ");
      for (int i = 1; i < howmany; i++) {
        r.add(i * gap);
      }
      System.out.println("Bits used per value = "+F.format(r.getSizeInBytes()*8.0/howmany));
      r.runOptimize();
      System.out.println("Bits used per value after run optimize = "+F.format(r.getSizeInBytes()*8.0/howmany));

      System.out.println("Size in bytes TOTAL = "+F.format(r.getSizeInBytes()));
      System.out.println("An uncompressed bitset might use "+F.format(universe_size*1.0/howmany)+" bits per value set");
      System.out.println();

    }

    public static void testSuperDense() {
      System.out.println("Sparse case... universe = [0,"+universe_size+"), "+(universe_size/8)+"b");
      RoaringBitmap r = new RoaringBitmap();
      int howmany = 100;
      int gap = universe_size / howmany;
      for (int i = 1; i < howmany; i++) {
        r.add(i * gap+1,((i+1) * gap));
      }
      System.out.println("Adding "+r.getCardinality()+" values partitioned by "+howmany+" gaps of 1 ...");
      System.out.println("As a bitmap it would look like 01111...11011111... ");

      System.out.println("Bits used per value = "+F.format(r.getSizeInBytes()*8.0/r.getCardinality()));
      r.runOptimize();
      System.out.println("Bits used per value after run optimize = "+F.format(r.getSizeInBytes()*8.0/r.getCardinality()));
      System.out.println("Bits used per gap after run optimize = "+F.format(r.getSizeInBytes()*8.0/howmany));

      System.out.println("Size in bytes TOTAL = "+F.format(r.getSizeInBytes()));
      System.out.println("An uncompressed bitset might use "+F.format(universe_size*1.0/r.getCardinality())+" bits per value set");
      System.out.println();
    }

    public static void testAlternating() {
      System.out.println("Alternating case... universe = [0,"+universe_size+"), "+(universe_size/8)+"b");
      RoaringBitmap r = new RoaringBitmap();
      for (int i = 1; i < universe_size; i++) {
        if(i%2 == 0)
          r.add(i);
      }
      System.out.println("Adding all even values in the universe");
      System.out.println("As a bitmap it would look like 01010101... ");
      System.out.println("Bits used per value = "+F.format(r.getSizeInBytes()*8.0/r.getCardinality()));
      r.runOptimize();
      System.out.println("Bits used per value after run optimize = "+F.format(r.getSizeInBytes()*8.0/r.getCardinality()));

      System.out.println("Size in bytes TOTAL = "+F.format(r.getSizeInBytes()));
      System.out.println("An uncompressed bitset might use "+F.format(universe_size*1.0/r.getCardinality())+" bits per value set");
      System.out.println();
    }

    public static void main(String[] args) {
      testSuperSparse();
      testSuperDense();
      testAlternating();
    }
}
