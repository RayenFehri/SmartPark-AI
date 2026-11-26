package smartparking.services;

import smartparking.models.DecisionTrace;
import smartparking.models.ParkingConfig;

import java.util.List;
import java.util.Optional;

/** Ce que les outils de l'assistant peuvent lire de l'état du Manager (lecture seule). */
public interface ManagerView {

    double nowSimMin();

    Optional<DecisionTrace> decision(String requestId);

    List<ParkingConfig.ZoneConfig> zones();

    /** Places occupées à l'instant courant, d'après les attributions du Manager. */
    int occupiedSlots(String zoneId);

    boolean isDown(String zoneId);

    /** Rang dans la file (1 = prochain) ; 0 = nouvelle négociation en cours ; -1 = pas en file. */
    int queuePosition(String requestId);

    int queueSize();
}
