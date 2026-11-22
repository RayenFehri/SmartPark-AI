package smartparking.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimClockTest {

    @Test
    void notStartedClockReturnsZero() {
        SimClock clock = new SimClock(20.0, new AtomicLong(5000)::get);
        assertEquals(0.0, clock.nowSimMin(), 1e-9);
    }

    @Test
    void elapsedRealTimeConvertsToSimulatedMinutes() {
        AtomicLong now = new AtomicLong(10_000);
        SimClock clock = new SimClock(20.0, now::get);
        clock.start();
        now.addAndGet(3_000); // 3 s réelles = 60 min simulées à 20 min/s
        assertEquals(60.0, clock.nowSimMin(), 1e-9);
    }

    @Test
    void conversionsAreConsistent() {
        SimClock clock = new SimClock(20.0, () -> 0L);
        assertEquals(1000L, clock.simMinToRealMs(20.0));
        assertEquals(20.0, clock.realMsToSimMin(1000L), 1e-9);
        assertEquals(9_000L, clock.simMinToRealMs(180.0)); // 180 min simulées = 9 s réelles
    }

    @Test
    void realMsUntilNeverNegative() {
        AtomicLong now = new AtomicLong(0);
        SimClock clock = new SimClock(20.0, now::get);
        clock.start();
        now.set(10_000); // 200 min simulées écoulées
        assertEquals(0L, clock.realMsUntil(100.0));
        assertEquals(2_000L, clock.realMsUntil(240.0)); // 40 min simulées restantes = 2 s
    }

    @Test
    void invalidTimeScaleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SimClock(0.0));
        assertThrows(IllegalArgumentException.class, () -> new SimClock(-1.0));
    }
}
