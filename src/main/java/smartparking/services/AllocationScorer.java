package smartparking.services;

import smartparking.models.DecisionTrace;
import smartparking.models.OfferEvaluation;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Proposal;
import smartparking.models.Refusal;
import smartparking.models.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Choix de la meilleure offre côté Manager (cahier des charges, §9.1 et §9.2).
 *
 * 1. Filtre dur : revérifie les contraintes obligatoires déjà vérifiées par les zones.
 * 2. Score : Score = wD x Dn + wP x Pn + wW x Wn (plus petit = meilleur).
 *      Dn = distance / distance maximale du parking (borne absolue, pas de min-max)
 *      Pn = prix total / budget maximal
 *      Wn = pénalité de gaspillage : 0 zone adaptée, 0,5 Premium non demandée, 1 zone spécialisée inutile
 * 3. Départage des égalités : zoneId, puis slotId.
 *
 * Classe en Java pur : aucune dépendance à JADE.
 */
public class AllocationScorer {

    public static final String PROFILE_DEFAULT = "default";
    public static final String PROFILE_NEAR_EXIT = "nearExit";
    public static final String PROFILE_TIGHT_BUDGET = "tightBudget";

    /** Budget serré : budget < 1,3 x tarif de base total (tarif de base le plus bas x durée). */
    public static final double TIGHT_BUDGET_FACTOR = 1.3;

    public static final String STATUS_SELECTED = "SELECTED";
    public static final String STATUS_NOT_SELECTED = "NOT_SELECTED";
    public static final String STATUS_ELIMINATED = "ELIMINATED";

    /** Résultat d'une évaluation : l'offre retenue (null si aucune) et la trace de décision. */
    public record Result(Proposal best, double bestScore, DecisionTrace trace) {
        public boolean isAllocated() {
            return best != null;
        }
    }

    private record Scored(Proposal proposal, double score) { }

    private final Map<String, ParkingConfig.Weights> weights;
    private final double distanceMax;
    private final double referenceBasePrice;

    public AllocationScorer(ParkingConfig config) {
        this(config.weights(),
                config.zones().stream().mapToDouble(ParkingConfig.ZoneConfig::maxDistance).max().orElse(1.0),
                config.zones().stream().mapToDouble(ParkingConfig.ZoneConfig::basePricePerHour).min().orElse(1.0));
    }

    public AllocationScorer(Map<String, ParkingConfig.Weights> weights, double distanceMax,
                            double referenceBasePrice) {
        if (weights == null || !weights.containsKey(PROFILE_DEFAULT)
                || !weights.containsKey(PROFILE_NEAR_EXIT) || !weights.containsKey(PROFILE_TIGHT_BUDGET)) {
            throw new IllegalArgumentException("les poids default, nearExit et tightBudget sont obligatoires");
        }
        if (distanceMax <= 0) {
            throw new IllegalArgumentException("distanceMax doit être > 0");
        }
        this.weights = Map.copyOf(weights);
        this.distanceMax = distanceMax;
        this.referenceBasePrice = referenceBasePrice;
    }

    /** Nom du profil de poids : budget serré d'abord, puis proximité de la sortie, sinon défaut. */
    public String profileFor(ParkingRequest request) {
        if (request.maxBudget() < TIGHT_BUDGET_FACTOR * referenceBasePrice * request.duration()) {
            return PROFILE_TIGHT_BUDGET;
        }
        if (request.preferredLocation() == PreferredLocation.NEAR_EXIT) {
            return PROFILE_NEAR_EXIT;
        }
        return PROFILE_DEFAULT;
    }

    /**
     * Pénalité de gaspillage : 1 pour une zone spécialisée (borne, PMR) dont le véhicule n'a pas besoin,
     * 0,5 pour une zone Premium non demandée (elle est « demandée » si la proximité de la sortie l'est),
     * 0 sinon.
     */
    public double wastePenalty(ParkingRequest request, ZoneType zoneType) {
        switch (zoneType) {
            case ELECTRIC:
                return request.requiresCharging() ? 0.0 : 1.0;
            case PMR:
                return request.requiresPmr() ? 0.0 : 1.0;
            case PREMIUM:
                return request.preferredLocation() == PreferredLocation.NEAR_EXIT ? 0.0 : 0.5;
            default:
                return 0.0;
        }
    }

    /** Score d'une offre, arrondi à 6 décimales pour que les égalités soient exactes. */
    public double score(ParkingRequest request, Proposal proposal) {
        ParkingConfig.Weights w = weights.get(profileFor(request));
        double dn = Math.min(1.0, Math.max(0.0, proposal.distanceToExit() / distanceMax));
        double pn = request.maxBudget() > 0 ? proposal.totalPrice() / request.maxBudget() : 1.0;
        double wn = wastePenalty(request, proposal.zoneType());
        return round6(w.wD() * dn + w.wP() * pn + w.wW() * wn);
    }

    /**
     * Filtre, score et choisit.
     *
     * @param proposals les PROPOSE reçus à temps
     * @param refusals  les REFUSE reçus (pour la trace uniquement)
     * @param downZones identifiants des zones actuellement DOWN
     */
    public Result evaluate(ParkingRequest request, List<Proposal> proposals, List<Refusal> refusals,
                           Set<String> downZones) {
        List<OfferEvaluation> evaluations = new ArrayList<>();

        for (Refusal refusal : refusals) {
            evaluations.add(new OfferEvaluation(refusal.zoneId(), null, 0.0, null, STATUS_ELIMINATED,
                    "refus de la zone : " + refusal.reason()));
        }

        // 1. Filtre dur
        List<Proposal> valid = new ArrayList<>();
        for (Proposal proposal : proposals) {
            String reason = eliminationReason(request, proposal, downZones);
            if (reason == null) {
                valid.add(proposal);
            } else {
                evaluations.add(eliminated(proposal, reason));
            }
        }

        // 2. Règle de non-gaspillage : une zone spécialisée inutile n'est qu'un repli exceptionnel
        boolean hasSuitable = valid.stream().anyMatch(p -> wastePenalty(request, p.zoneType()) < 1.0);
        List<Proposal> candidates = new ArrayList<>();
        for (Proposal proposal : valid) {
            if (hasSuitable && wastePenalty(request, proposal.zoneType()) >= 1.0) {
                evaluations.add(eliminated(proposal,
                        "zone spécialisée non nécessaire : des offres adaptées existent"));
            } else {
                candidates.add(proposal);
            }
        }

        // 3. Score et classement
        List<Scored> ranked = new ArrayList<>();
        for (Proposal proposal : candidates) {
            ranked.add(new Scored(proposal, score(request, proposal)));
        }
        ranked.sort(Comparator.<Scored>comparingDouble(Scored::score)
                .thenComparing(s -> s.proposal().zoneId())
                .thenComparing(s -> s.proposal().slotId()));

        for (int i = 0; i < ranked.size(); i++) {
            Scored s = ranked.get(i);
            evaluations.add(new OfferEvaluation(s.proposal().zoneId(), s.proposal().slotId(),
                    s.proposal().totalPrice(), s.score(),
                    i == 0 ? STATUS_SELECTED : STATUS_NOT_SELECTED, null));
        }

        String profile = profileFor(request);
        if (ranked.isEmpty()) {
            String summary = String.format(Locale.ROOT,
                    "Demande %s : aucune offre valable (%d refus, %d offre(s) éliminée(s)).",
                    request.requestId(), refusals.size(), proposals.size());
            return new Result(null, 0.0, new DecisionTrace(request.requestId(), evaluations, null, summary));
        }

        Scored best = ranked.get(0);
        String summary = String.format(Locale.ROOT,
                "Demande %s : %d offre(s) valable(s) sur %d reçue(s). Retenue : %s, place %s, %.2f DT, "
                        + "score %.3f (profil %s).",
                request.requestId(), ranked.size(), proposals.size(), best.proposal().zoneId(),
                best.proposal().slotId(), best.proposal().totalPrice(), best.score(), profile);
        return new Result(best.proposal(), best.score(),
                new DecisionTrace(request.requestId(), evaluations, best.proposal().zoneId(), summary));
    }

    /** Contraintes obligatoires (§9.1) ; retourne le motif d'élimination, ou null si l'offre est valable. */
    private String eliminationReason(ParkingRequest request, Proposal proposal, Set<String> downZones) {
        if (!request.requestId().equals(proposal.requestId())) {
            return "offre rattachée à une autre demande";
        }
        if (downZones.contains(proposal.zoneId())) {
            return "zone hors service (DOWN)";
        }
        if (proposal.totalPrice() > request.maxBudget() + 1e-9) {
            return String.format(Locale.ROOT, "prix %.2f DT supérieur au budget %.2f DT",
                    proposal.totalPrice(), request.maxBudget());
        }
        if (request.requiresCharging() && proposal.zoneType() != ZoneType.ELECTRIC) {
            return "recharge requise : zone sans borne";
        }
        if (request.requiresPmr() && proposal.zoneType() != ZoneType.PMR) {
            return "place PMR requise : zone non PMR";
        }
        return null;
    }

    private static OfferEvaluation eliminated(Proposal proposal, String reason) {
        return new OfferEvaluation(proposal.zoneId(), proposal.slotId(), proposal.totalPrice(), null,
                STATUS_ELIMINATED, reason);
    }

    private static double round6(double value) {
        return Math.round(value * 1e6) / 1e6;
    }
}