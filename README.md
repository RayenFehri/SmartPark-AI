# SmartPark-AI

Système multi-agent JADE pour l'allocation dynamique et négociée de places de parking
(Contract Net, tarification dynamique, assistant LLM Mistral).

## Prérequis
- JDK 17, Maven 3.9+, Git
- (plus tard) variable d'environnement `MISTRAL_API_KEY`

## Lancement (étape 0)
    mvn compile exec:java                                  # plateforme + HelloAgent
    mvn compile exec:java -Dexec.args="--gui"              # avec le RMA
    mvn compile exec:java -Dexec.args="--gui --sniffer"    # avec RMA et Sniffer

Lancement direct de JADE :

    mvn compile exec:java -Dexec.mainClass=jade.Boot -Dexec.args="-gui -agents hello:smartparking.agents.HelloAgent"

## Si JADE ne se résout pas depuis Maven
Télécharger JADE 4.6.0 sur https://jade.tilab.com, puis :

    mvn install:install-file -Dfile=jade.jar -DgroupId=com.tilab.jade -DartifactId=jade -Dversion=4.6.0 -Dpackaging=jar

## Structure
agents/ · behaviours/ · models/ · services/ · utils/ · config/ · results/ · docs/
