package smartparking.services;

import smartparking.models.ParkingRequest;

/**
 * Contrat commun du parsing de langage naturel : implémenté par MistralService
 * (LLM) et FallbackParser (règles). Le résultat n'a pas d'identifiants :
 * l'appelant utilise ParkingRequest.withIds(...).
 */
public interface NlParser {

    ParkingRequest parse(String text) throws NlParseException;
}
