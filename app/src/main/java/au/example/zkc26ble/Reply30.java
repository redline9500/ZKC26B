package au.example.zkc26ble;

import java.util.Arrays;
import java.util.Locale;

/**
 * Parses the 30-byte responses seen on 5CBA from ZKC26B E4 during 2026-11-13 tests.
 * This is deliberately a structural decoder, NOT proof of a successful image ACK.
 */
final class Reply30 {
    final long counter;
    final String field16to19;
    final String trailer26to29;
    final String raw;

    private Reply30(long counter, String field16to19, String trailer26to29, String raw) {
        this.counter = counter;
        this.field16to19 = field16to19;
        this.trailer26to29 = trailer26to29;
        this.raw = raw;
    }

    static Reply30 parse(byte[] bytes, byte[] targetMac) {
        if (bytes == null || bytes.length != 30 || targetMac == null || targetMac.length != 6) return null;
        if ((bytes[0] & 0xff) != 0x01) return null;
        if (!Arrays.equals(Arrays.copyOfRange(bytes, 1, 7), targetMac)) return null;
        // Only constant bytes established by the five captured samples are checked.
        // The meaning of the changing field at [16..19] and trailer [26..29] is UNKNOWN.
        int[] offsets = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 25};
        int[] values  = { 0, 26,  9,  0, 13,  1,  0,  0,  0,  0,  6};
        for (int i = 0; i < offsets.length; i++) {
            if ((bytes[offsets[i]] & 0xff) != values[i]) return null;
        }
        long counter = ((long)(bytes[7] & 0xff) << 24)
                     | ((long)(bytes[8] & 0xff) << 16)
                     | ((long)(bytes[9] & 0xff) << 8)
                     | ((long)(bytes[10] & 0xff));
        return new Reply30(counter, hex(bytes, 16, 20), hex(bytes, 26, 30), hex(bytes, 0, 30));
    }

    private static String hex(byte[] v, int begin, int end) {
        StringBuilder out = new StringBuilder();
        for (int i = begin; i < end; i++) out.append(String.format(Locale.US, "%02X", v[i] & 0xff));
        return out.toString();
    }
}