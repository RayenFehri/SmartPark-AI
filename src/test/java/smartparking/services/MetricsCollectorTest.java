package smartparking.services;

import org.junit.jupiter.api.Test;
import smartparking.models.Event;
import smartparking.utils.EventTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorTest {

    private static Event event(String type, String requestId, String zoneId, long realMs, double simMin,
                               String details) {
        return new Event(realMs, simMin, type, "test", requestId, zoneId, details);
    }

    private static Event received(String id, long realMs, double simMin) {
        return event(EventTypes.REQUEST_SENT, id, null, realMs, simMin, null);
    }

    private static Event allocated(String id, long realMs, double simMin, String slot, double price,
                                   double start, double end, boolean wasteful) {
        String details = String.format(java.util.Locale.ROOT,
                "{\"zoneId\":\"Z\",\"slotId\":\"%s\",\"price\":%s,\"score\":0.25,\"startSimMin\":%s,"
                        + "\"endSimMin\":%s,\"wasteful\":%s}", slot, price, start, end, wasteful);
        return event(EventTypes.ALLOCATED, id, "Z", realMs, simMin, details);
    }

    @Test
    void countsOutcomesAndComputesRates() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 1000, 10.0));
        m.onEvent(received("R2", 1100, 11.0));
        m.onEvent(received("R3", 1200, 12.0));
        m.onEvent(received("R4", 1300, 13.0));
        m.onEvent(allocated("R1", 1050, 10.0, "A-01", 4.0, 10.0, 130.0, false));
        m.onEvent(allocated("R2", 1250, 11.5, "A-02", 6.0, 11.5, 100.0, true));
        m.onEvent(event(EventTypes.REFUSED, "R3", null, 1250, 12.0, "{\"reason\":\"BUDGET_EXCEEDED\"}"));
        m.onEvent(event(EventTypes.FAILED, "R4", null, 1400, 13.0, "{\"reason\":\"x\"}"));

        MetricsCollector.Summary s = m.summary("S1", "CNP_OPTIMIZED", 1L);

        assertEquals(4, s.requests());
        assertEquals(2, s.allocated());
        assertEquals(1, s.refused());
        assertEquals(1, s.failed());
        assertEquals(0.5, s.satisfactionRate(), 1e-9);
        assertEquals(10.0, s.revenue(), 1e-9);
        assertEquals(1, s.unneededSpecialized());
        assertEquals((50 + 150) / 2.0, s.avgAssignmentMs(), 1e-9);
        assertEquals((0.0 + 0.5) / 2.0, s.avgWaitSimMin(), 1e-9);
    }

    @Test
    void conflictsAreCounted() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(event(EventTypes.CONFLICT, "R1", "Z", 0, 0, null));
        m.onEvent(event(EventTypes.CONFLICT, "R2", "Z", 0, 0, null));
        assertEquals(2, m.summary("S1", "CNP_OPTIMIZED", 1L).conflicts());
    }

    @Test
    void occupancyIsAveragedOverAllZonesWithLatestKnownLoad() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(event(EventTypes.HEARTBEAT, null, "Z1", 0, 0, "{\"used\":0,\"usable\":10}"));
        // échantillon 1 : 0 / 10 = 0 %
        m.onEvent(event(EventTypes.HEARTBEAT, null, "Z2", 0, 0, "{\"used\":10,\"usable\":10}"));
        // échantillon 2 : 10 / 20 = 50 %
        m.onEvent(event(EventTypes.HEARTBEAT, null, "Z1", 0, 0, "{\"used\":10,\"usable\":10}"));
        // échantillon 3 : 20 / 20 = 100 %
        assertEquals(0.5, m.summary("S1", "CNP_OPTIMIZED", 1L).avgOccupancy(), 1e-9);
    }

    @Test
    void overlappingAllocationOfTheSameSlotIsFlaggedAsDouble() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 0, 10.0));
        m.onEvent(received("R2", 0, 20.0));
        m.onEvent(allocated("R1", 10, 10.0, "A-01", 4.0, 10.0, 130.0, false));
        m.onEvent(allocated("R2", 10, 20.0, "A-01", 4.0, 20.0, 80.0, false));   // A-01 occupée jusqu'à 130

        assertEquals(1, m.summary("S1", "CNP_OPTIMIZED", 1L).doubleAllocations());
    }

    @Test
    void reuseAfterTheEndOfAStayIsNotADoubleAllocation() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 0, 10.0));
        m.onEvent(received("R2", 0, 200.0));
        m.onEvent(allocated("R1", 10, 10.0, "A-01", 4.0, 10.0, 130.0, false));
        m.onEvent(allocated("R2", 10, 200.0, "A-01", 4.0, 200.0, 260.0, false));

        assertEquals(0, m.summary("S1", "CNP_OPTIMIZED", 1L).doubleAllocations());
    }

    @Test
    void malformedEventsAreIgnored() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(null);
        m.onEvent(event(EventTypes.ALLOCATED, "R1", "Z", 0, 0, "pas du json {"));
        m.onEvent(event(EventTypes.HEARTBEAT, null, "Z1", 0, 0, "???"));
        MetricsCollector.Summary s = m.summary("S1", "CNP_OPTIMIZED", 1L);
        assertEquals(1, s.requests());
        assertEquals(0.0, s.avgOccupancy(), 1e-9);
    }

    @Test
    void csvHasOneColumnPerHeaderField() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 0, 1.0));
        m.onEvent(allocated("R1", 5, 1.0, "A-01", 4.0, 1.0, 61.0, false));
        String header = MetricsCollector.summaryHeader();
        String line = MetricsCollector.summaryLine(m.summary("S1", "FIRST_AVAILABLE", 3L));

        assertEquals(header.split(",").length, line.split(",").length);
        assertTrue(line.startsWith("S1,FIRST_AVAILABLE,3,1,1,0,0,1.0000"));
    }

    @Test
    void exportWritesDetailFileAndAppendsToSummary() throws IOException {
        Path dir = Files.createTempDirectory("smartpark-metrics");
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 0, 1.0));
        m.onEvent(allocated("R1", 5, 1.0, "A-01", 4.0, 1.0, 61.0, false));

        Path detail = m.export(dir, "S1", "CNP_OPTIMIZED", 1L);
        m.export(dir, "S1", "CNP_OPTIMIZED", 2L);

        assertEquals("detail_S1_CNP_OPTIMIZED_seed1.csv", detail.getFileName().toString());
        List<String> detailLines = Files.readAllLines(detail);
        assertEquals(2, detailLines.size());
        assertTrue(detailLines.get(1).startsWith("R1,ALLOCATED,Z,A-01,4.00"));

        List<String> summaryLines = Files.readAllLines(dir.resolve("summary.csv"));
        assertEquals(3, summaryLines.size());   // un seul en-tête, deux exécutions
        assertEquals(MetricsCollector.summaryHeader(), summaryLines.get(0));
    }

    @Test
    void decidedCountAndLatestEndTrackTheProgressOfTheRun() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 0, 1.0));
        m.onEvent(received("R2", 0, 2.0));
        assertEquals(0, m.decidedCount());

        m.onEvent(allocated("R1", 5, 1.0, "A-01", 4.0, 1.0, 61.0, false));
        m.onEvent(event(EventTypes.REFUSED, "R2", null, 6, 2.0, "{\"reason\":\"ZONE_FULL\"}"));

        assertEquals(2, m.decidedCount());
        assertEquals(61.0, m.latestEndSimMin(), 1e-9);
    }

    @Test
    void queuedRequestsAndDistancesAreSummarized() {
        MetricsCollector m = new MetricsCollector();
        m.onEvent(received("R1", 0, 1.0));
        m.onEvent(received("R2", 0, 2.0));
        m.onEvent(event(EventTypes.QUEUED, "R2", null, 1, 2.0, null));
        m.onEvent(event(EventTypes.QUEUED, "R2", null, 2, 2.0, null));   // doublon : compté une fois
        m.onEvent(event(EventTypes.ALLOCATED, "R1", "Z", 5, 1.0,
                "{\"slotId\":\"A-01\",\"price\":4.0,\"startSimMin\":1.0,\"endSimMin\":61.0,"
                        + "\"distanceToExit\":60.0,\"nearExit\":false}"));
        m.onEvent(event(EventTypes.ALLOCATED, "R2", "Z", 6, 3.0,
                "{\"slotId\":\"A-02\",\"price\":4.0,\"startSimMin\":3.0,\"endSimMin\":63.0,"
                        + "\"distanceToExit\":10.0,\"nearExit\":true}"));

        MetricsCollector.Summary s = m.summary("S3", "CNP_OPTIMIZED", 1L);

        assertEquals(1, s.queued());
        assertEquals(35.0, s.avgDistanceToExit(), 1e-9);
        assertEquals(10.0, s.avgDistanceNearExit(), 1e-9);
    }
}