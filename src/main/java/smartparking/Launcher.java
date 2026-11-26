package smartparking;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.Scenario;
import smartparking.models.Strategy;
import smartparking.services.ScenarioLoader;
import smartparking.services.SimClock;
import smartparking.utils.Config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Point d'entrée : charge la configuration, démarre le Main Container JADE, puis les agents
 * (zones, Manager, véhicules du scénario).
 * Options : --gui (ouvre le RMA), --sniffer (ouvre le Sniffer),
 *           --scenario <fichier> (par défaut config/scenarios/s1_normal.json),
 *           --strategy A|B|C (A = FIRST_AVAILABLE, B = CNP_OPTIMIZED, C = CNP_LLM ; par défaut celle de parking.json),
 *           --explain (stratégie C : chaque véhicule demande à l'assistant pourquoi sa place lui a été attribuée),
 *           --auto-exit (quitte quand l'exécution est terminée, après l'export CSV).
 */
public class Launcher {

    private static final String DEFAULT_SCENARIO = "config/scenarios/s1_normal.json";
    private static final long PREPARSE_TIMEOUT_S = 180;

    public static void main(String[] args) throws Exception {
        List<String> options = Arrays.asList(args);
        boolean gui = options.contains("--gui");
        boolean sniffer = options.contains("--sniffer");
        int scenarioIndex = options.indexOf("--scenario");
        String scenarioPath = scenarioIndex >= 0 && scenarioIndex + 1 < options.size()
                ? options.get(scenarioIndex + 1) : DEFAULT_SCENARIO;

        boolean autoExit = options.contains("--auto-exit");
        int strategyIndex = options.indexOf("--strategy");
        String strategyOption = strategyIndex >= 0 && strategyIndex + 1 < options.size()
                ? options.get(strategyIndex + 1) : null;

        ParkingConfig config = Config.load();
        Strategy strategy = resolveStrategy(strategyOption, config.strategy());
        // lu par le ParkingManagerAgent : l'option remplace la valeur du fichier de configuration
        System.setProperty("smartpark.strategy", strategy.name());
        System.setProperty("smartpark.explain", String.valueOf(options.contains("--explain")));
        System.out.println("[Launcher] configuration chargée : " + config.zones().size() + " zones, "
                + config.totalSlots() + " places, stratégie " + strategy);

        Scenario scenario = ScenarioLoader.load(Path.of(scenarioPath));
        List<ParkingRequest> requests = ScenarioLoader.generate(scenario);
        System.out.println("[Launcher] scénario " + scenario.name() + " : " + requests.size()
                + " véhicules (graine " + scenario.seed() + ")");

        SimClock.install(new SimClock(config.timeScale()));

        Runtime runtime = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, String.valueOf(gui));

        AgentContainer container = runtime.createMainContainer(profile);

        if (sniffer) {
            StringBuilder watched = new StringBuilder("manager;ZoneStandard;ZoneElectric;ZonePMR;ZonePremium");
            for (ParkingRequest request : requests) {
                watched.append(';').append(request.vehicleId());
            }
            container.createNewAgent("sniffer", "jade.tools.sniffer.Sniffer",
                    new Object[] {watched.toString()}).start();
        }

        container.createNewAgent("monitor", "smartparking.agents.MonitorAgent",
                new Object[] {strategy.name(), scenario.name(), scenario.seed(), requests.size(), autoExit}).start();

        for (ParkingConfig.ZoneConfig zone : config.zones()) {
            container.createNewAgent(zone.zoneId(), "smartparking.agents.ZoneAgent",
                    new Object[] {zone.zoneId()}).start();
        }
        container.createNewAgent("manager", "smartparking.agents.ParkingManagerAgent", null).start();

        // stratégie C : l'assistant comprend toutes les phrases AVANT le début de la simulation, pour que la latence
        // du LLM (en secondes réelles) ne décale pas les arrivées simulées et ne fausse pas la comparaison avec B
        if (strategy == Strategy.CNP_LLM) {
            Map<String, ParkingRequest> originals = new LinkedHashMap<>();
            requests.forEach(r -> originals.put(r.requestId(), r));
            CountDownLatch ready = new CountDownLatch(1);
            container.createNewAgent("assistant", "smartparking.agents.LLMAssistantAgent",
                    new Object[] {originals, ready}).start();
            System.out.println("[Launcher] pré-analyse de " + originals.size() + " phrases par l'assistant...");
            if (!ready.await(PREPARSE_TIMEOUT_S, TimeUnit.SECONDS)) {
                System.out.println("[Launcher] pré-analyse incomplète après " + PREPARSE_TIMEOUT_S
                        + " s : les phrases restantes seront comprises à l'arrivée");
            }
        }

        // laisse aux zones le temps de s'enregistrer au DF, puis démarre l'horloge de simulation
        Thread.sleep(1500);
        SimClock.get().start();

        if (scenario.faults() != null && !scenario.faults().isEmpty()) {
            container.createNewAgent("injector", "smartparking.agents.FaultInjectorAgent",
                    new Object[] {scenario.faults()}).start();
        }
        for (ParkingRequest request : requests) {
            container.createNewAgent(request.vehicleId(), "smartparking.agents.VehicleAgent",
                    new Object[] {request}).start();
        }
    }

    /** A et B sont des raccourcis ; sans option, la stratégie du fichier de configuration est conservée. */
    private static Strategy resolveStrategy(String option, Strategy fromConfig) {
        if (option == null) {
            return fromConfig;
        }
        switch (option.toUpperCase()) {
            case "A":
                return Strategy.FIRST_AVAILABLE;
            case "B":
                return Strategy.CNP_OPTIMIZED;
            case "C":
                return Strategy.CNP_LLM;
            default:
                try {
                    return Strategy.valueOf(option.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("stratégie inconnue : " + option + " (A, B ou un nom complet)");
                }
        }
    }
}
