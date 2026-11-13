package au.example.zkc26ble;

import java.util.Arrays;

public final class HexPacketTest {
    public static void main(String[] args) {
        eq(new byte[] {(byte) 0xA0, 0x00}, HexPacket.parse("A000"));
        eq(new byte[] {(byte) 0xC1, 0x00, 0x00}, HexPacket.parse("0xC1 00 00"));
        eq(new byte[] {0x0A, 0x00, 0x01}, HexPacket.parse("0a:00:01"));
        eq(new byte[] {0x01, (byte) 0xFF}, HexPacket.parse("01-ff"));
        bad(""); bad(" "); bad("0"); bad("ABC"); bad("A0GG"); bad("00 1");
        System.out.println("HexPacketTest: PASS");
    }
    private static void eq(byte[] a, byte[] b) {
        if (!Arrays.equals(a, b))
            throw new AssertionError("expected " + Arrays.toString(a) + " got " + Arrays.toString(b));
    }
    private static void bad(String input) {
        try { HexPacket.parse(input); throw new AssertionError("Accepted: " + input); }
        catch (IllegalArgumentException expected) { }
    }
}
