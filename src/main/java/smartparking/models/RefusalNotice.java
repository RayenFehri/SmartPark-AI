package smartparking.models;

import java.util.List;

/** Contenu du REFUSE du Manager vers le véhicule : le motif et des alternatives (§8). */
public record RefusalNotice(String requestId, RefusalReason reason, List<Alternative> alternatives) {
}