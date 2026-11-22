package smartparking.utils;

import smartparking.models.ParkingConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Chargement de la configuration globale du parking. */
public final class Config {

    public static final Path DEFAULT_PATH = Path.of("config", "parking.json");

    private Config() { }

    public static ParkingConfig load() throws IOException {
        return load(DEFAULT_PATH);
    }

    public static ParkingConfig load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonUtil.fromJson(reader, ParkingConfig.class);
        }
    }
}
