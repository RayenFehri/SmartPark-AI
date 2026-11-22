# SmartPark-AI

Système multi-agent JADE pour l'allocation dynamique et négociée de places de parking
(Contract Net, tarification dynamique, assistant LLM Mistral).

## Prérequis
- JDK 17, Maven 3.9+, Git
- JADE 4.6.1 est récupéré automatiquement depuis Maven Central (`de.enflexit.jade`)
- (plus tard) variable d'environnement `MISTRAL_API_KEY`

## Commandes
    mvn test                                               # tests unitaires (étape 1)
    mvn compile exec:java                                  # plateforme + HelloAgent
    mvn compile exec:java -Dexec.args="--gui"              # avec le RMA
    mvn compile exec:java -Dexec.args="--gui --sniffer"    # avec RMA et Sniffer

## Structure
- `agents/` : agents JADE
- `behaviours/` : comportements JADE
- `models/` : objets de données et contrats JSON (records Java)
- `services/` : logique métier en Java pur, sans import jade.* (SimClock, ScenarioLoader, ...)
- `utils/` : Ontology, EventTypes, JsonUtil, Config
- `config/` : parking.json et scénarios S1 à S4
- `results/` : CSV générés (ignorés par Git)

## Contrats figés (étape 1)
Modèles de `models/`, constantes de `utils/Ontology` et `utils/EventTypes`, interface `services/NlParser`.
Toute modification se décide à deux.
