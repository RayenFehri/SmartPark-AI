package smartparking.models;

/**
 * Place de parking. Objet géré exclusivement par un ZoneAgent (jamais partagé entre agents).
 * Les transitions d'état seront implémentées dans ZoneState (étape 2).
 */
public class Slot {

    private final String slotId;
    private final String zoneId;
    private final ZoneType zoneType;
    private final double basePricePerHour;
    private final double distanceToExit;
    private final boolean hasCharger;
    private final boolean pmr;

    private SlotState state = SlotState.FREE;
    private String heldBy;
    private long holdExpiresAtRealMs;
    private double occupiedUntilSimMin;

    public Slot(String slotId, String zoneId, ZoneType zoneType, double basePricePerHour,
                double distanceToExit, boolean hasCharger, boolean pmr) {
        this.slotId = slotId;
        this.zoneId = zoneId;
        this.zoneType = zoneType;
        this.basePricePerHour = basePricePerHour;
        this.distanceToExit = distanceToExit;
        this.hasCharger = hasCharger;
        this.pmr = pmr;
    }

    public String getSlotId() { return slotId; }
    public String getZoneId() { return zoneId; }
    public ZoneType getZoneType() { return zoneType; }
    public double getBasePricePerHour() { return basePricePerHour; }
    public double getDistanceToExit() { return distanceToExit; }
    public boolean hasCharger() { return hasCharger; }
    public boolean isPmr() { return pmr; }

    public SlotState getState() { return state; }
    public void setState(SlotState state) { this.state = state; }
    public boolean isFree() { return state == SlotState.FREE; }

    public String getHeldBy() { return heldBy; }
    public void setHeldBy(String requestId) { this.heldBy = requestId; }

    public long getHoldExpiresAtRealMs() { return holdExpiresAtRealMs; }
    public void setHoldExpiresAtRealMs(long ms) { this.holdExpiresAtRealMs = ms; }

    public double getOccupiedUntilSimMin() { return occupiedUntilSimMin; }
    public void setOccupiedUntilSimMin(double simMin) { this.occupiedUntilSimMin = simMin; }

    @Override
    public String toString() {
        return slotId + "[" + state + "]";
    }
}
