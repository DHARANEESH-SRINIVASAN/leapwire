package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.servlet.HandlerMapping;

final class Exchange {

    static final String ATTRIBUTE = Exchange.class.getName();
    static final String SPECULATIVE = Exchange.class.getName() + ".speculative";

    private final String page;
    private final String route;
    private final boolean accept;
    private final long arrivedAt;
    private final CapturingResponse response;
    private String endpoint;
    private Map<String, String> variables = Map.of();
    private String prefix = "";
    private boolean skip;

    Exchange(String page, String route, boolean accept, long arrivedAt, CapturingResponse response) {
        this.page = page;
        this.route = route;
        this.accept = accept;
        this.arrivedAt = arrivedAt;
        this.response = response;
    }

    void matched(HttpServletRequest request, boolean skip) {
        if (endpoint != null) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return;
        }
        endpoint = pattern.toString();
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> found) {
            Map<String, String> copy = new LinkedHashMap<>();
            found.forEach((name, value) -> copy.put(String.valueOf(name), String.valueOf(value)));
            variables = copy;
        }
        prefix = request.getContextPath() + (request.getPathInfo() == null ? "" : request.getServletPath());
        this.skip = skip;
    }

    boolean capture() {
        return response.capture();
    }

    String page() {
        return page;
    }

    String route() {
        return route;
    }

    boolean accept() {
        return accept;
    }

    long arrivedAt() {
        return arrivedAt;
    }

    String endpoint() {
        return endpoint;
    }

    Map<String, String> variables() {
        return variables;
    }

    String prefix() {
        return prefix;
    }

    boolean skip() {
        return skip;
    }
}
