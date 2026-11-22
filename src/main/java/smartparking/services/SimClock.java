package smartparking.services;

import java.util.function.LongSupplier;

/**
 * Horloge de simulation : convertit le temps réel en temps simulé.
 * timeScale = nombre de minutes simulées par seconde réelle (ex. 20 : 1 s réelle = 20 min simulées).
 * Les délais de protocole (CNP, hold, heartbeat) restent en temps réel ;
 * les durées de stationnement et les arrivées sont en temps simulé.
 */
public class SimClock {

    private static volatile SimClock instance;

    private final double timeScale;
    private final LongSupplier realClockMs;
    private volatile long startRealMs;
    private volatile boolean started;

    public SimClock(double timeScale) {
        this(timeScale, System::currentTimeMillis);
    }

    /** Constructeur avec source de temps injectable (tests). */
    public SimClock(double timeScale, LongSupplier realClockMs) {
        if (timeScale <= 0) {
            throw new IllegalArgumentException("timeScale doit être > 0");
        }
        this.timeScale = timeScale;
        this.realClockMs = realClockMs;
    }

    /** Installe l'horloge partagée par tous les agents de la JVM. */
    public static void install(SimClock clock) {
        instance = clock;
    }

    public static SimClock get() {
        SimClock clock = instance;
        if (clock == null) {
            throw new IllegalStateException("SimClock non installée : appeler SimClock.install(...) dans le Launcher");
        }
        return clock;
    }

    public void start() {
        startRealMs = realClockMs.getAsLong();
        started = true;
    }

    /** Minutes simulées écoulées depuis start() ; 0 si l'horloge n'est pas démarrée. */
    public double nowSimMin() {
        if (!started) {
            return 0.0;
        }
        return realMsToSimMin(realClockMs.getAsLong() - startRealMs);
    }

    public double realMsToSimMin(long realMs) {
        return realMs / 1000.0 * timeScale;
    }

    public long simMinToRealMs(double simMin) {
        return Math.round(simMin / timeScale * 1000.0);
    }

    /** Délai réel (ms) à attendre avant l'instant simulé donné ; 0 s'il est déjà passé. */
    public long realMsUntil(double simMin) {
        return Math.max(0L, simMinToRealMs(simMin - nowSimMin()));
    }

    public double getTimeScale() {
        return timeScale;
    }
}
