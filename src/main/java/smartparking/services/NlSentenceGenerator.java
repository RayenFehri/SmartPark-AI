package smartparking.services;

import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.VehicleType;

import java.util.Locale;

/**
 * Transforme une demande structurée en phrase française, de façon déterministe (le gabarit dépend
 * de l'identifiant de la demande). Sert à la stratégie C : le conducteur « parle », l'assistant comprend.
 * Les phrases sont aussi le jeu de test du parseur (aller-retour demande -> phrase -> demande).
 */
public final class NlSentenceGenerator {

    private NlSentenceGenerator() { }

    public static String sentence(ParkingRequest r) {
        int variant = Math.floorMod(r.requestId() == null ? 0 : r.requestId().hashCode(), 4);
        String duration = duration(r.duration(), variant);
        String budget = number(r.maxBudget()) + (variant % 2 == 0 ? " DT" : " dinars");

        StringBuilder text = new StringBuilder();
        String need = switch (r.vehicleType()) {
            case ELECTRIC -> variant < 2 ? "Ma voiture est électrique, j'ai besoin d'une recharge" : "Je cherche une borne de recharge électrique";
            case PMR -> variant < 2 ? "Je suis une personne à mobilité réduite" : "J'ai besoin d'une place PMR";
            default -> variant < 2 ? "Je voudrais une place" : "Une place de parking s'il vous plaît";
        };
        text.append(need);
        text.append(variant % 2 == 0 ? " pour " : ", pour ").append(duration);
        text.append(", budget ").append(budget);
        if (r.preferredLocation() == PreferredLocation.NEAR_EXIT) {
            text.append(variant < 2 ? ", près de la sortie" : ", si possible proche de la sortie");
        }
        if (r.priority() == Priority.URGENT) {
            text.append(", c'est urgent");
        }
        text.append('.');
        return text.toString();
    }

    private static String duration(double hours, int variant) {
        if (hours < 1.0) {
            return Math.round(hours * 60) + " minutes";
        }
        boolean half = Math.abs(hours - Math.floor(hours) - 0.5) < 1e-9;
        long whole = (long) Math.floor(hours);
        if (half) {
            return variant % 2 == 0 ? whole + "h30" : number(hours) + " heures";
        }
        return whole + (whole == 1 ? " heure" : " heures");
    }

    /** 8.0 -> "8" ; 18.5 -> "18,5" (virgule décimale française). */
    private static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
    }
}
