package au.example.zkc26ble;

public final class Startup108Test {
    private static byte[] bytes(String hex) {
        byte[] b = new byte[hex.length()/2];
        for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return b;
    }
    private static void check(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        byte[] mac = bytes("A0A31056CC0E");
        byte[] sample = bytes("01A0A31056CC0E00000077006801005B8100004A298260860202003888010128009889000000095A4B433236422D4E418A00007530000054600000000C000151808C018D028E078F000000009100920293020100940704D2C68B110269950B8396000075309A01013FD66F5E");
        check(sample.length==108,"sample is 108 bytes");
        check(Startup108.matches(sample,mac),"actual startup sample recognised");
        byte[] sample2 = bytes("01A0A31056CC0E00000004006801005B8100000E07825B860202003888010128009889000000095A4B433236422D4E418A00007530000054600000000C000151808C018D028E078F000000009100920293020100940704D2C68B110269950B6096000075309A0101C795240D");
        check(Startup108.matches(sample2,mac),"second real startup recognised");
        byte[] changed = sample.clone(); changed[1]=(byte)0xAA;
        check(!Startup108.matches(changed,mac),"wrong MAC rejected");
        changed=sample.clone();changed[12]=0;
        check(!Startup108.matches(changed,mac),"wrong framing rejected");
        changed=sample.clone();changed[45]='X';
        check(!Startup108.matches(changed,mac),"wrong model rejected");
        changed=sample.clone();changed[38]=8;
        check(!Startup108.matches(changed,mac),"wrong model field length rejected");
        check(!Startup108.matches(new byte[30],mac),"30-byte response rejected");
        check(!Startup108.matches(sample,null),"null MAC rejected");
        System.out.println("STARTUP108 report recognition tests passed");
    }
}
