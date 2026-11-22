package smartparking.models;

/**
 * Demande de stationnement. Les noms de champs correspondent au contrat JSON
 * produit par le LLM ou le FallbackParser (voir cahier des charges, §8 et §11).
 *
 * @param requestId         identifiant unique de la demande (attribué par l'appelant)
 * @param vehicleId         identifiant du véhicule
 * @param duration          durée de stationnement en heures
 * @param maxBudget         budget maximal total en dinars (DT)
 * @param vehicleType       type de véhicule
 * @param requiresCharging  besoin de recharge électrique
 * @param requiresPmr       besoin d'une place PMR
 * @param priority          priorité de la demande
 * @param preferredLocation préférence de proximité avec la sortie
 * @param arrivalSimMin     instant d'arrivée en minutes simulées depuis le début du scénario
 */
public record ParkingRequest(
        String requestId,
        String vehicleId,
        double duration,
        double maxBudget,
        VehicleType vehicleType,
        boolean requiresCharging,
        boolean requiresPmr,
        Priority priority,
        PreferredLocation preferredLocation,
        long arrivalSimMin) {

    /** Retourne une copie avec les identifiants renseignés (le parseur ne les connaît pas). */
    public ParkingRequest withIds(String newRequestId, String newVehicleId) {
        return new ParkingRequest(newRequestId, newVehicleId, duration, maxBudget, vehicleType,
                requiresCharging, requiresPmr, priority, preferredLocation, arrivalSimMin);
    }
}
