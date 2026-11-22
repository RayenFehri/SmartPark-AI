package smartparking.utils;

import org.junit.jupiter.api.Test;
import smartparking.models.ParkingConfig;
import smartparking.models.Strategy;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void defaultConfigurationLoads() throws IOException {
        ParkingConfig config = Config.load();
        assertEquals(Strategy.CNP_OPTIMIZED, config.strategy());
        assertEquals(4, config.zones().size());
        assertEquals(40, config.totalSlots());
        assertEquals(2000, config.cnpTimeoutMs());
        assertTrue(config.timeScale() > 0);
    }

    @Test
    void pricingTableIsConsistent() throws IOException {
        ParkingConfig.Pricing pricing = Config.load().pricing();
        assertEquals(3, pricing.thresholds().length);
        assertEquals(4, pricing.surcharges().length);
        for (int i = 1; i < pricing.thresholds().length; i++) {
            assertTrue(pricing.thresholds()[i] > pricing.thresholds()[i - 1], "seuils non croissants");
        }
    }

    @Test
    void everyWeightProfileSumsToOne() throws IOException {
        ParkingConfig config = Config.load();
        assertTrue(config.weights().containsKey("default"));
        assertTrue(config.weights().containsKey("nearExit"));
        assertTrue(config.weights().containsKey("tightBudget"));
        config.weights().forEach((name, w) ->
                assertEquals(1.0, w.wD() + w.wP() + w.wW(), 1e-9, "poids du profil " + name));
    }
}
