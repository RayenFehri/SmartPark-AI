package smartparking;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import smartparking.models.ParkingConfig;
import smartparking.services.SimClock;
import smartparking.utils.Config;
import java.util.Arrays;
import java.util.List;

/**
 * Point d'entrée : charge la configuration, installe l'horloge de simulation,
 * démarre le Main Container JADE puis les agents.
 * Options : --gui (ouvre le RMA), --sniffer (ouvre le Sniffer).
 * Étapes 0-1 : seul HelloAgent est lancé. Les vrais agents seront ajoutés aux étapes suivantes.
 */
public class Launcher {

    public static void main(String[] args) throws Exception {
        List<String> options = Arrays.asList(args);
        boolean gui = options.contains("--gui");
        boolean sniffer = options.contains("--sniffer");

        ParkingConfig config = Config.load();
        System.out.println("[Launcher] configuration chargée : " + config.zones().size() + " zones, "
                + config.totalSlots() + " places, stratégie " + config.strategy());

        SimClock clock = new SimClock(config.timeScale());
        SimClock.install(clock);
        clock.start();

        Runtime runtime = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, String.valueOf(gui));

        AgentContainer container = runtime.createMainContainer(profile);

        if (sniffer) {
            container.createNewAgent("sniffer", "jade.tools.sniffer.Sniffer",
                    new Object[] {"ZoneStandard;ZoneElectric;ZonePMR;ZonePremium;tester"}).start();
        }

        for (ParkingConfig.ZoneConfig zone : config.zones()) {
            container.createNewAgent(zone.zoneId(), "smartparking.agents.ZoneAgent",
                    new Object[] {zone.zoneId()}).start();
        }

        if (options.contains("--test-cfp")) {
            container.createNewAgent("tester", "smartparking.agents.TestInitiatorAgent", null).start();
        }

    }
}
