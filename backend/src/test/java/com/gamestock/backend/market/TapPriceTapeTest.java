package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TapPriceTapeTest {
    private static final long SLOT = TapPriceTape.SLOT_MS;

    @Test
    void slotsCollectHighAndLowAndCarryPriceAcrossQuietSlots() {
        TapPriceTape tape = new TapPriceTape();
        long t0 = 1_000 * SLOT;
        tape.start("UMA", 12_150, t0);
        tape.observe("UMA", 12_170, t0 + 1_000);
        tape.observe("UMA", 12_140, t0 + 2_000);
        tape.advance(t0 + 3 * SLOT + 100);

        assertArrayEquals(new long[]{12_140, 12_170}, tape.range("UMA", t0 / SLOT));
        // 체결이 없던 칸은 직전 가격 하나로 채운다.
        assertArrayEquals(new long[]{12_140, 12_140}, tape.range("UMA", t0 / SLOT + 1));
        assertArrayEquals(new long[]{12_140, 12_140}, tape.range("UMA", t0 / SLOT + 3));
        assertTrue(tape.coveredUntil("UMA", t0 + 3 * SLOT));
        assertFalse(tape.coveredUntil("UMA", t0 + 4 * SLOT));
    }

    @Test
    void slotsOpenedBeforeTrackingAreNotJudged() {
        TapPriceTape tape = new TapPriceTape();
        long t0 = 1_000 * SLOT + 2_000;
        tape.start("GI", 218_500, t0);
        assertNull(tape.range("GI", t0 / SLOT), "기록 전에 열린 칸은 일부만 봤다");
        tape.advance(t0 + SLOT);
        assertNotNull(tape.range("GI", t0 / SLOT + 1));
        assertNull(tape.range("SR", t0 / SLOT + 1));
    }

    @Test
    void bandIsOneTick() {
        TapPriceTape tape = new TapPriceTape();
        long t = 1_000 * SLOT;
        tape.start("OW", 106_800, t);
        long price = 106_800;
        for (int i = 1; i <= 400; i++) {
            price += (i % 2 == 0 ? 300 : -300);
            tape.observe("OW", price, t + i * 250L);
            tape.advance(t + i * 250L);
        }
        TapPriceTape.Snapshot snapshot = tape.snapshot("OW");
        assertEquals(100, snapshot.tick());
        assertEquals(100, snapshot.band(), "칸 높이는 늘 1틱이다");
        assertTrue(snapshot.sigmaPerSqrtSecond() > 100);
        assertFalse(snapshot.path().isEmpty());
    }

    @Test
    void stoppingForgetsEverything() {
        TapPriceTape tape = new TapPriceTape();
        tape.start("SR", 129_300, 0);
        tape.stop();
        assertFalse(tape.active("SR"));
        assertNull(tape.snapshot("SR"));
    }

    @Test
    void calibrationLearnsThatABouncingPriceRarelyTravels() {
        TapPriceTape tape = new TapPriceTape();
        long t = 1_000 * SLOT;
        tape.start("UMA", 12_150, t);
        double before = tape.probability("UMA", 3, 4_000, 12_180);
        // 20분 동안 12,150과 12,160 사이만 오간다.
        for (int i = 1; i <= 4_800; i++) {
            long now = t + i * 250L;
            tape.observe("UMA", i % 4 < 2 ? 12_150 : 12_160, now);
            tape.advance(now);
        }
        // 마지막 가격은 12,150. 방금 오간 12,160은 거의 반드시, 반대쪽 12,140은 거의 안 닿는다.
        double near = tape.probability("UMA", 1, 4_000, 12_160);
        double other = tape.probability("UMA", 1, 4_000, 12_140);
        double far = tape.probability("UMA", 3, 4_000, 12_180);
        assertTrue(near > 0.9, "방금 오간 가격은 거의 매번 닿는다: " + near);
        assertTrue(other < 0.15, "한 번도 안 간 쪽은 거의 안 닿는다: " + other);
        assertTrue(far < before && far < 0.05, "3틱 밖은 거의 안 닿는다고 배운다: " + before + " -> " + far);
        assertEquals(0, TapOdds.multiplier(near), "확실한 칸은 열지 않는다");
    }
}
