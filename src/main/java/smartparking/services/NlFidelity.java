package smartparking.services;

import smartparking.models.ParkingRequest;

import java.util.ArrayList;
import java.util.List;

/** Compare la demande comprise à la demande d'origine : liste des champs qui diffèrent (ids et arrivée ignorés). */
public final class NlFidelity {

    private NlFidelity() { }

    public static List<String> mismatches(ParkingRequest original, ParkingRequest parsed) {
        List<String> diff = new ArrayList<>();
        if (parsed == null) {
            diff.add("all");
            return diff;
        }
        if (Math.abs(original.duration() - parsed.duration()) > 0.01) diff.add("duration");
        if (Math.abs(original.maxBudget() - parsed.maxBudget()) > 0.01) diff.add("maxBudget");
        if (original.vehicleType() != parsed.vehicleType()) diff.add("vehicleType");
        if (original.requiresCharging() != parsed.requiresCharging()) diff.add("requiresCharging");
        if (original.requiresPmr() != parsed.requiresPmr()) diff.add("requiresPmr");
        if (original.priority() != parsed.priority()) diff.add("priority");
        if (original.preferredLocation() != parsed.preferredLocation()) diff.add("preferredLocation");
        return diff;
    }
}
