package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

final class Bundler {

    private final Learner learner;
    private final Speculator speculator;
    private final LeapwireProperties properties;

    Bundler(Learner learner, Speculator speculator, LeapwireProperties properties) {
        this.learner = learner;
        this.speculator = speculator;
        this.properties = properties;
    }

    boolean bundle(HttpServletRequest request, HttpServletResponse response, Exchange exchange, JsonNode body)
            throws IOException {
        String query = request.getQueryString();
        Plan plan = new Plan(exchange.prefix(), request.getRequestURI() + (query == null ? "" : "?" + query));
        List<Planned> first = plan.add(learner.activeRules(exchange.route(), exchange.endpoint()), body, 1);
        if (first.isEmpty()) {
            return false;
        }
        long deadline = System.nanoTime() + properties.deadline().toNanos();
        List<Speculator.Header> headers = Speculator.headers(request);
        Map<String, String> main = Json.headers(name -> name.equals("content-type")
                ? response.getContentType() : response.getHeader(name));
        BlockingQueue<Speculator.Part> done = new LinkedBlockingQueue<>();
        Map<String, Future<?>> running = new LinkedHashMap<>();
        boolean interrupted = false;
        try {
            BundleWriter writer = BundleWriter.open(response);
            writer.main(main, body);
            writer.plan(urls(first));
            start(first, headers, deadline, done, running);
            while (!running.isEmpty()) {
                long left = deadline - System.nanoTime();
                Speculator.Part part;
                try {
                    part = left > 0 ? done.poll(left, TimeUnit.NANOSECONDS) : null;
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                if (part == null) {
                    break;
                }
                if (running.remove(part.url()) == null) {
                    continue;
                }
                Planned item = plan.get(part.url());
                if (part.status() == HttpServletResponse.SC_OK && item.depth() < properties.maxDepth()) {
                    List<Planned> next = plan.add(learner.activeRules(exchange.route(), item.endpoint()),
                            part.body(), item.depth() + 1);
                    if (!next.isEmpty()) {
                        writer.plan(urls(next));
                        start(next, headers, deadline, done, running);
                    }
                }
                writer.part(part);
            }
            for (String url : running.keySet()) {
                writer.part(Speculator.Part.failed(url));
            }
            writer.end();
            return true;
        } finally {
            running.values().forEach(future -> future.cancel(true));
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void start(List<Planned> planned, List<Speculator.Header> headers, long deadline,
                       BlockingQueue<Speculator.Part> done, Map<String, Future<?>> running) {
        for (Planned item : planned) {
            running.put(item.url(), speculator.submit(item.url(), headers, deadline, done));
        }
    }

    private static List<String> urls(List<Planned> planned) {
        List<String> urls = new ArrayList<>(planned.size());
        for (Planned item : planned) {
            urls.add(item.url());
        }
        return urls;
    }

    private record Planned(String url, String endpoint, int depth) {
    }

    private final class Plan {

        private final String prefix;
        private final Set<String> urls = new HashSet<>();
        private final Map<String, Planned> planned = new HashMap<>();

        private Plan(String prefix, String primary) {
            this.prefix = prefix;
            urls.add(primary);
        }

        private List<Planned> add(List<Target> rules, JsonNode body, int depth) {
            List<Planned> added = new ArrayList<>();
            for (Target rule : rules) {
                if (planned.size() >= properties.maxRequests()) {
                    break;
                }
                String target = rule.url(body);
                if (target == null) {
                    continue;
                }
                String url = prefix + target;
                if (urls.add(url)) {
                    Planned item = new Planned(url, rule.endpoint(), depth);
                    planned.put(url, item);
                    added.add(item);
                }
            }
            return added;
        }

        private Planned get(String url) {
            return planned.get(url);
        }
    }
}
