package au.example.zkc26ble;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Strict recognition of the ZKC26B-NA 108-byte startup report captured on 5CBA.
 * Only used to gate a user-armed, one-shot reply. Does not decode or ACK the report.
 */
final class Startup108 {
    private static final byte[] MODEL = "ZKC26B-NA".getBytes(StandardCharsets.US_ASCII);

    private Startup108() {}

    static boolean matches(byte[] packet, byte[] targetMac) {
        if (packet == null || packet.length != 108 || targetMac == null || targetMac.length != 6) return false;
        if ((packet[0] & 0xFF) != 0x01) return false;
        for (int i = 0; i < 6; i++) if (packet[i + 1] != targetMac[i]) return false;
        // Shared length framing: byte 12 = len-4, bytes 14-15 = len-17.
        if ((packet[12] & 0xFF) != 0x68 || (packet[13] & 0xFF) != 0x01
                || packet[14] != 0 || (packet[15] & 0xFF) != 0x5B) return false;
        // Model field: tag 0x89; 4-byte big-endian length 9; ASCII model ZKC26B-NA.
        if ((packet[34] & 0xFF) != 0x89 || packet[35] != 0 || packet[36] != 0
                || packet[37] != 0 || (packet[38] & 0xFF) != MODEL.length) return false;
        return Arrays.equals(Arrays.copyOfRange(packet, 39, 48), MODEL);
    }
}
