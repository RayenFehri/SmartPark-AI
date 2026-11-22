package smartparking;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

import java.util.Arrays;
import java.util.List;

/**
 * Point d'entrée : démarre le Main Container JADE puis les agents.
 * Options : --gui (ouvre le RMA), --sniffer (ouvre le Sniffer).
 * Étape 0 : ne lance que HelloAgent. Les vrais agents seront ajoutés aux étapes suivantes.
 */
public class Launcher {

    public static void main(String[] args) throws StaleProxyException {
        List<String> options = Arrays.asList(args);
        boolean gui = options.contains("--gui");
        boolean sniffer = options.contains("--sniffer");

        Runtime runtime = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, String.valueOf(gui));

        AgentContainer container = runtime.createMainContainer(profile);

        container.createNewAgent("hello", "smartparking.agents.HelloAgent", null).start();

        if (sniffer) {
            container.createNewAgent("sniffer", "jade.tools.sniffer.Sniffer", new Object[] {"hello"}).start();
        }
    }
}
