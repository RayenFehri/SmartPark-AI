package smartparking.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitQueueTest {

    private static List<String> ids(List<WaitQueue.Entry<String>> entries) {
        return entries.stream().map(WaitQueue.Entry::id).toList();
    }

    @Test
    void servesInArrivalOrderWithinTheSamePriority() {
        WaitQueue<String> queue = new WaitQueue<>();
        queue.add("R1", "a", false, 10.0);
        queue.add("R2", "b", false, 11.0);
        queue.add("R3", "c", false, 12.0);

        assertEquals(List.of("R1", "R2", "R3"), ids(queue.ordered()));
    }

    @Test
    void priorityRequestsOvertakeNormalOnesButKeepFifoAmongThemselves() {
        WaitQueue<String> queue = new WaitQueue<>();
        queue.add("N1", "a", false, 10.0);
        queue.add("P1", "b", true, 11.0);
        queue.add("N2", "c", false, 12.0);
        queue.add("P2", "d", true, 13.0);

        assertEquals(List.of("P1", "P2", "N1", "N2"), ids(queue.ordered()));
    }

    @Test
    void drainAllEmptiesTheQueueInServiceOrder() {
        WaitQueue<String> queue = new WaitQueue<>();
        queue.add("N1", "a", false, 10.0);
        queue.add("P1", "b", true, 11.0);

        assertEquals(List.of("P1", "N1"), ids(queue.drainAll()));
        assertTrue(queue.isEmpty());
    }

    @Test
    void reinsertedEntryKeepsItsRankAndOriginalEnqueueTime() {
        WaitQueue<String> queue = new WaitQueue<>();
        queue.add("R1", "round0", false, 10.0);
        queue.add("R2", "x", false, 11.0);

        WaitQueue.Entry<String> first = queue.drainAll().get(0);
        queue.add("R3", "y", false, 20.0);
        queue.reinsert(first.withPayload("round1"));
        queue.add("R2", "x", false, 11.0);   // R2 revient après R3

        List<WaitQueue.Entry<String>> ordered = queue.ordered();
        assertEquals("R1", ordered.get(0).id());
        assertEquals("round1", ordered.get(0).payload());
        assertEquals(10.0, ordered.get(0).enqueuedSimMin(), 1e-9);
    }

    @Test
    void entriesWaitingTooLongExpireButTheBoundaryItselfDoesNot() {
        WaitQueue<String> queue = new WaitQueue<>();
        queue.add("OLD", "a", false, 0.0);
        queue.add("EDGE", "b", false, 10.0);
        queue.add("NEW", "c", false, 35.0);

        // maximum 30 min : à t = 40, OLD a attendu 40 (expiré), EDGE 30 (pas encore), NEW 5
        List<WaitQueue.Entry<String>> expired = queue.removeExpired(40.0, 30.0);

        assertEquals(List.of("OLD"), ids(expired));
        assertEquals(2, queue.size());
        assertTrue(queue.contains("EDGE"));
        assertFalse(queue.contains("OLD"));
    }
}