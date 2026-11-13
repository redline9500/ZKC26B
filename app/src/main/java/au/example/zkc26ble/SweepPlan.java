package au.example.zkc26ble;

import java.util.Locale;

/** Pure-Java, deterministic list of 1280 protocol probes. No BLE dependencies. */
public final class SweepPlan {
    private SweepPlan() { }
    public static final int PER_STAGE = 256;
    public static final int TOTAL = 5 * PER_STAGE;
    private static final String[] STAGES = {"XX", "XX00", "XX01", "XX0000", "XX0001"};

    public static byte[] payload(int index) {
        checkIndex(index);
        int stage = index / PER_STAGE;
        byte prefix = (byte) (index % PER_STAGE);
        switch (stage) {
            case 0: return new byte[] {prefix};
            case 1: return new byte[] {prefix, 0};
            case 2: return new byte[] {prefix, 1};
            case 3: return new byte[] {prefix, 0, 0};
            case 4: return new byte[] {prefix, 0, 1};
            default: throw new AssertionError(stage);
        }
    }

    public static String hexAt(int index) {
        byte[] b = payload(index);
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format(Locale.US, "%02X", v & 0xff));
        return sb.toString();
    }

    public static String describe(int index) {
        if (index == TOTAL) return "COMPLETED";
        checkIndex(index);
        return STAGES[index / PER_STAGE] + " / " + hexAt(index)
            + " (test " + (index + 1) + "/" + TOTAL + ")";
    }

    /**
     * Accept a full packet in hex (spaces/colons/hyphens optional), or #index.
     * The packet length selects the stage; trailing bytes MUST match the plan.
     * #0 is the first test, #1279 the last.
     */
    public static int parseStart(String input) {
        if (input == null) throw new IllegalArgumentException("Enter a packet or #index");
        String s = input.trim();
        if (s.startsWith("#")) {
            try {
                int idx = Integer.parseInt(s.substring(1));
                checkIndex(idx);
                return idx;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Use #0 through #1279");
            }
        }
        s = s.replaceAll("[\\s:,-]", "").toUpperCase(Locale.US);
        if (!s.matches("[0-9A-F]{2}([0-9A-F]{2}){0,2}"))
            throw new IllegalArgumentException("Enter 1–3 bytes of hex, e.g. A50001");
        int first = Integer.parseInt(s.substring(0,2),16);
        if (s.length() == 2) return first;
        if (s.length() == 4) {
            if (s.endsWith("00")) return 256 + first;
            if (s.endsWith("01")) return 512 + first;
        }
        if (s.length() == 6) {
            if (s.endsWith("0000")) return 768 + first;
            if (s.endsWith("0001")) return 1024 + first;
        }
        throw new IllegalArgumentException("Not in sweep: XX, XX00, XX01, XX0000 or XX0001");
    }

    public static void checkIndex(int index) {
        if (index < 0 || index >= TOTAL) throw new IllegalArgumentException("Index 0–1279 required");
    }
}