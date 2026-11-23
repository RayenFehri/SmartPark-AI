package smartparking.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * File d'attente de saturation (EF10) : FIFO, avec priorité aux demandes PMR et URGENT,
 * et durée d'attente maximale en temps simulé. Java pur, non thread-safe (un seul agent l'utilise).
 *
 * Une demande qui retourne en file après une nouvelle tentative infructueuse garde son rang
 * et son heure d'entrée d'origine (reinsert), pour que l'attente maximale se compte depuis la première mise en file.
 */
public class WaitQueue<T> {

    /** Un élément de la file : id = identifiant de la demande, sequence = ordre d'arrivée. */
    public record Entry<T>(String id, T payload, boolean priority, double enqueuedSimMin, long sequence) {
        public Entry<T> withPayload(T newPayload) {
            return new Entry<>(id, newPayload, priority, enqueuedSimMin, sequence);
        }
    }

    private final List<Entry<T>> entries = new ArrayList<>();
    private long nextSequence;

    public Entry<T> add(String id, T payload, boolean priority, double enqueuedSimMin) {
        Entry<T> entry = new Entry<>(id, payload, priority, enqueuedSimMin, nextSequence++);
        entries.add(entry);
        return entry;
    }

    /** Remet un élément déjà passé en file (même rang, même heure d'entrée). */
    public void reinsert(Entry<T> entry) {
        entries.add(entry);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public boolean contains(String id) {
        return entries.stream().anyMatch(e -> e.id().equals(id));
    }

    /** Ordre de service : prioritaires d'abord, puis par ordre d'arrivée. */
    public List<Entry<T>> ordered() {
        List<Entry<T>> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.<Entry<T>, Boolean>comparing(e -> !e.priority())
                .thenComparingLong(Entry::sequence));
        return sorted;
    }

    /** Vide la file et retourne son contenu dans l'ordre de service. */
    public List<Entry<T>> drainAll() {
        List<Entry<T>> sorted = ordered();
        entries.clear();
        return sorted;
    }

    /** Retire et retourne les éléments en attente depuis plus de maxWaitSimMin minutes simulées. */
    public List<Entry<T>> removeExpired(double nowSimMin, double maxWaitSimMin) {
        List<Entry<T>> expired = new ArrayList<>();
        Iterator<Entry<T>> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (nowSimMin - entry.enqueuedSimMin() > maxWaitSimMin) {
                expired.add(entry);
                iterator.remove();
            }
        }
        expired.sort(Comparator.<Entry<T>, Boolean>comparing(e -> !e.priority())
                .thenComparingLong(Entry::sequence));
        return expired;
    }
}