package au.example.zkc26ble;

public class Reply30Test {
    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length()/2];
        for (int i=0; i<out.length; i++) out[i]=(byte) Integer.parseInt(hex.substring(2*i,2*i+2),16);
        return out;
    }
    private static void assertTrue(boolean v,String message) {if(!v)throw new AssertionError(message);}
    public static void main(String[] args) {
        byte[] mac=fromHex("A0A31056CC0E");
        String[] samples={
          "01A0A31056CC0E00000000001A09000DFF00000001000000000674758480",
          "01A0A31056CC0E00000001001A09000D00000000010000000006DD253D2F",
          "01A0A31056CC0E00000000001A09000D00000000010000000006676FF77E",
          "01A0A31056CC0E00000002001A09000DFFFFFF00010000000006F83033AA",
          "01A0A31056CC0E00000003001A09000D000000000100000000064B783CDE"
        };
        int[] counters={0,1,0,2,3};
        for(int i=0;i<samples.length;i++) {
            Reply30 reply=Reply30.parse(fromHex(samples[i]), mac);
            assertTrue(reply!=null,"sample "+i+" not parsed");
            assertTrue(reply.counter==counters[i],"counter "+i+" should be "+counters[i]);
            assertTrue(reply.trailer26to29.equals(samples[i].substring(52)),"trailer "+i);
            System.out.println("OK sample "+i+" counter="+reply.counter+" field="+reply.field16to19);
        }
        byte[] malformed=fromHex(samples[0]); malformed[1]=(byte)0xDE;
        assertTrue(Reply30.parse(malformed,mac)==null,"bad MAC must reject");
        malformed=fromHex(samples[0]);malformed[12]=(byte)0;
        assertTrue(Reply30.parse(malformed,mac)==null,"bad constant byte must reject");
        assertTrue(Reply30.parse(new byte[29],mac)==null,"bad length must reject");
        System.out.println("ALL REPLY30 DECODER TESTS PASSED");
    }
}