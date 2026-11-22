package smartparking.models;

/** Stratégies d'allocation comparées dans les expériences. */
public enum Strategy {
    /** A : première place compatible, sans score. */
    FIRST_AVAILABLE,
    /** B : Contract Net + score pondéré. */
    CNP_OPTIMIZED,
    /** C : Contract Net + assistant LLM (même allocation que B). */
    CNP_LLM
}
