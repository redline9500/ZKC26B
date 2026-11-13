package au.example.zkc26ble;

import java.util.HashSet;
import java.util.Set;

public final class SweepPlanTest {
    public static void main(String[] args) {
        Set<String> all = new HashSet<>();
        for (int i = 0; i < SweepPlan.TOTAL; i++) {
            String h = SweepPlan.hexAt(i);
            if (!all.add(h)) throw new AssertionError("duplicate " + h);
            if (SweepPlan.parseStart(h) != i)
                throw new AssertionError("roundtrip failed " + i + ": " + h);
        }
        eq(1280, all.size());
        eq("00", SweepPlan.hexAt(0));
        eq("FF", SweepPlan.hexAt(255));
        eq("0000", SweepPlan.hexAt(256));
        eq("FF00", SweepPlan.hexAt(511));
        eq("0001", SweepPlan.hexAt(512));
        eq("FF01", SweepPlan.hexAt(767));
        eq("000000", SweepPlan.hexAt(768));
        eq("FF0000", SweepPlan.hexAt(1023));
        eq("000001", SweepPlan.hexAt(1024));
        eq("FF0001", SweepPlan.hexAt(1279));
        eq(1025, SweepPlan.parseStart("01 00 01"));
        eq(511, SweepPlan.parseStart("#511"));
        expectReject("AA02");
        expectReject("FFFFFF");
        expectReject("#1280");
        System.out.println("SweepPlanTest: PASS (1280 unique payloads; manual start mapping OK)");
    }
    private static void eq(Object want, Object actual) {
        if (!want.equals(actual)) throw new AssertionError("expected " + want + ", got " + actual);
    }
    private static void expectReject(String x) {
        try { SweepPlan.parseStart(x); throw new AssertionError("accepted " + x); }
        catch (IllegalArgumentException expected) { }
    }
}