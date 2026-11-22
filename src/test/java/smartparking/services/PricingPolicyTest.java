package smartparking.services;

import org.junit.jupiter.api.Test;
import smartparking.models.Slot;
import smartparking.models.SlotState;
import smartparking.models.ZoneType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PricingPolicyTest {

    private static final PricingPolicy POLICY =
            new PricingPolicy(new double[] {0.4, 0.7, 0.9}, new double[] {0.0, 0.1, 0.2, 0.3});

    private static Slot slot(String id, SlotState state) {
        Slot slot = new Slot(id, "Z", ZoneType.STANDARD, 2.0, 10.0, false, false);
        slot.setState(state);
        return slot;
    }

    @Test
    void surchargeFollowsThePricingTableIncludingBoundaries() {
        assertEquals(0.0, POLICY.surcharge(0.0), 1e-9);
        assertEquals(0.0, POLICY.surcharge(0.399), 1e-9);
        assertEquals(0.1, POLICY.surcharge(0.4), 1e-9);
        assertEquals(0.1, POLICY.surcharge(0.699), 1e-9);
        assertEquals(0.2, POLICY.surcharge(0.7), 1e-9);
        assertEquals(0.2, POLICY.surcharge(0.899), 1e-9);
        assertEquals(0.3, POLICY.surcharge(0.9), 1e-9);
        assertEquals(0.3, POLICY.surcharge(1.0), 1e-9);
    }

    @Test
    void occupancyCountsReservedAndOccupiedButNotOutOfService() {
        List<Slot> slots = List.of(
                slot("a", SlotState.FREE), slot("b", SlotState.FREE),
                slot("c", SlotState.RESERVED), slot("d", SlotState.RESERVED),
                slot("e", SlotState.OCCUPIED), slot("f", SlotState.OCCUPIED), slot("g", SlotState.OCCUPIED),
                slot("h", SlotState.FREE), slot("i", SlotState.FREE),
                slot("j", SlotState.OUT_OF_SERVICE));
        // 9 places utilisables, 5 utilisées
        assertEquals(5.0 / 9.0, POLICY.occupancyRate(slots), 1e-9);
    }

    @Test
    void zoneWithoutUsableSlotsIsConsideredFull() {
        List<Slot> slots = List.of(slot("a", SlotState.OUT_OF_SERVICE), slot("b", SlotState.OUT_OF_SERVICE));
        assertEquals(1.0, POLICY.occupancyRate(slots), 1e-9);
    }

    @Test
    void totalPriceAppliesSurchargeAndDuration() {
        // base 2 DT/h, occupation 50 % (+10 %), 3 h : 2 x 1,1 x 3 = 6,6
        assertEquals(6.6, POLICY.totalPrice(2.0, 0.5, 3.0), 1e-9);
        assertEquals(2.2, POLICY.unitPrice(2.0, 0.5), 1e-9);
        assertEquals(4.0, POLICY.totalPrice(2.0, 0.0, 2.0), 1e-9);
    }

    @Test
    void inconsistentTablesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PricingPolicy(new double[] {0.4, 0.7}, new double[] {0.0, 0.1}));
        assertThrows(IllegalArgumentException.class,
                () -> new PricingPolicy(null, new double[] {0.0}));
    }
}