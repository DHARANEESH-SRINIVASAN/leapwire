package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

final class Learner {

    static final long HOUR = 3_600_000L;
    static final int MAX_SOURCES = 4096;
    static final int MAX_CANDIDATES = 32;
    static final int MAX_USERS = 64;

    private final LeapwireProperties properties;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Page> pages = new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<Key, Source> sources = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Source> eldest) {
            return size() > MAX_SOURCES;
        }
    };
    private long indexed;

    Learner(LeapwireProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    synchronized void record(Observation observation) {
        long now = observation.completedAt();
        expire(now);
        Page page = pages.get(observation.page());
        if (page == null) {
            page = new Page();
            pages.put(observation.page(), page);
        }
        page.lastSeen = now;
        if (page.requests >= properties.maxPageRequests()) {
            return;
        }
        page.requests++;
        long generation = now / HOUR;
        follow(page, observation, generation);
        Key own = new Key(observation.route(), observation.endpoint());
        if (page.counted.add(own)) {
            Source source = sources.get(own);
            if (source == null) {
                source = new Source(generation);
                sources.put(own, source);
            }
            source.see(observation.user(), generation);
        }
        int size = observation.index().size();
        if (size > 0 && size <= properties.maxIndexedValues()) {
            page.responses.add(new Recorded(observation.route(), observation.endpoint(), now, observation.index()));
            page.indexed += size;
            indexed += size;
        }
        trim();
    }

    synchronized List<Target> activeRules(String route, String endpoint) {
        Source source = sources.get(new Key(route, endpoint));
        return source == null ? List.of() : source.active(now() / HOUR, properties);
    }

    boolean hasActiveRules(String route, String endpoint) {
        return !activeRules(route, endpoint).isEmpty();
    }

    synchronized int pages() {
        return pages.size();
    }

    synchronized long indexedValues() {
        return indexed;
    }

    synchronized int recorded(String page) {
        Page found = pages.get(page);
        return found == null ? 0 : found.responses.size();
    }

    private void follow(Page page, Observation observation, long generation) {
        for (int i = page.responses.size() - 1; i >= 0; i--) {
            Recorded earlier = page.responses.get(i);
            if (earlier.completedAt() > observation.arrivedAt() || !earlier.route().equals(observation.route())) {
                continue;
            }
            List<Target> targets = Candidates.of(observation, earlier.index());
            if (targets.isEmpty()) {
                continue;
            }
            Key key = new Key(earlier.route(), earlier.endpoint());
            Source source = sources.get(key);
            if (source != null && page.counted.contains(key)) {
                for (Target target : targets) {
                    if (page.followed.add(new Follow(key, target))) {
                        source.follow(target, generation);
                    }
                }
            }
            return;
        }
    }

    private void expire(long now) {
        long ttl = properties.pageTtl().toMillis();
        Iterator<Page> eldest = pages.values().iterator();
        while (eldest.hasNext()) {
            Page page = eldest.next();
            if (now - page.lastSeen <= ttl) {
                return;
            }
            eldest.remove();
            indexed -= page.indexed;
        }
    }

    private void trim() {
        Iterator<Page> eldest = pages.values().iterator();
        while (eldest.hasNext() && (pages.size() > properties.maxPages() || indexed > properties.maxIndexedValues())) {
            Page page = eldest.next();
            eldest.remove();
            indexed -= page.indexed;
        }
    }

    private record Key(String route, String endpoint) {
    }

    private record Follow(Key source, Target target) {
    }

    private record Recorded(String route, String endpoint, long completedAt, ValueIndex index) {
    }

    private static final class Page {

        private final List<Recorded> responses = new ArrayList<>();
        private final Set<Key> counted = new HashSet<>();
        private final Set<Follow> followed = new HashSet<>();
        private long lastSeen;
        private int requests;
        private long indexed;
    }

    private static final class Source {

        private final LinkedHashMap<String, Long> users = new LinkedHashMap<>(8, 0.75f, true);
        private final LinkedHashMap<Target, Double> candidates = new LinkedHashMap<>();
        private long generation;
        private double seen;

        private Source(long generation) {
            this.generation = generation;
        }

        private void see(String user, long now) {
            decay(now);
            seen++;
            users.put(user, now);
            if (users.size() > MAX_USERS) {
                users.remove(users.keySet().iterator().next());
            }
        }

        private void follow(Target target, long now) {
            decay(now);
            Double followed = candidates.get(target);
            if (followed == null && candidates.size() >= MAX_CANDIDATES) {
                Target weakest = null;
                double lowest = Double.MAX_VALUE;
                for (Map.Entry<Target, Double> candidate : candidates.entrySet()) {
                    if (candidate.getValue() < lowest) {
                        lowest = candidate.getValue();
                        weakest = candidate.getKey();
                    }
                }
                candidates.remove(weakest);
            }
            candidates.put(target, Math.min(seen, (followed == null ? 0 : followed) + 1));
        }

        private List<Target> active(long now, LeapwireProperties properties) {
            decay(now);
            if (seen < properties.minPages() || users.size() < properties.minUsers()) {
                return List.of();
            }
            List<Map.Entry<Target, Double>> confident = new ArrayList<>();
            for (Map.Entry<Target, Double> candidate : candidates.entrySet()) {
                if (candidate.getValue() / seen >= properties.minConfidence()) {
                    confident.add(candidate);
                }
            }
            confident.sort(Map.Entry.<Target, Double>comparingByValue(Comparator.reverseOrder()));
            List<Target> targets = new ArrayList<>(confident.size());
            for (Map.Entry<Target, Double> candidate : confident) {
                targets.add(candidate.getKey());
            }
            return targets;
        }

        private void decay(long now) {
            long steps = now - generation;
            if (steps <= 0) {
                return;
            }
            double factor = steps >= 64 ? 0 : Math.pow(0.5, steps);
            seen *= factor;
            candidates.replaceAll((target, followed) -> followed * factor);
            candidates.values().removeIf(followed -> followed < 0.01);
            users.values().removeIf(last -> last < now - 1);
            generation = now;
        }
    }
}
