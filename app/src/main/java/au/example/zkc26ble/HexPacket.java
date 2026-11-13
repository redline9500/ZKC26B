package au.example.zkc26ble;

/** Parses a single, unchunked BLE notification payload from a hexadecimal text field. */
public final class HexPacket {
    private HexPacket() {}

    public static byte[] parse(String input) {
        if (input == null) throw new IllegalArgumentException("Enter HEX bytes");
        String value = input.trim();
        if (value.regionMatches(true, 0, "0x", 0, 2)) value = value.substring(2);
        value = value.replaceAll("[\\s,:-]", "");
        if (value.isEmpty()) throw new IllegalArgumentException("Enter HEX bytes");
        if ((value.length() & 1) != 0)
            throw new IllegalArgumentException("HEX must have an even number of digits");
        if (!value.matches("(?i)[0-9a-f]+"))
            throw new IllegalArgumentException("Use hexadecimal digits 0-9 and A-F only");
        if (value.length() > 1024)
            throw new IllegalArgumentException("One packet cannot exceed 512 bytes");
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
