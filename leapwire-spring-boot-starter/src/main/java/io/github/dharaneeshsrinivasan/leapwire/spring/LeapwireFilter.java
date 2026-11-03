package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class LeapwireFilter implements Filter {

    private final Learner learner;
    private final Speculator speculator;
    private final Bundler bundler;
    private final LeapwireProperties properties;

    LeapwireFilter(Learner learner, Speculator speculator, Bundler bundler, LeapwireProperties properties) {
        this.learner = learner;
        this.speculator = speculator;
        this.bundler = bundler;
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest request) || !(res instanceof HttpServletResponse response)) {
            chain.doFilter(req, res);
            return;
        }
        String page = request.getHeader(Protocol.PAGE);
        if (page == null || !"GET".equals(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        if (request.getHeader(Protocol.SPECULATIVE) != null) {
            request.setAttribute(Exchange.SPECULATIVE, Boolean.TRUE);
            try {
                chain.doFilter(req, res);
            } finally {
                request.removeAttribute(Exchange.SPECULATIVE);
            }
            return;
        }
        String route = Protocol.routeKey(request.getHeader(Protocol.ROUTE));
        if (route == null || !Protocol.validPage(page) || !speculator.ready()) {
            chain.doFilter(req, res);
            return;
        }
        CapturingResponse captured = new CapturingResponse(response, properties.maxBodyBytes());
        Exchange exchange = new Exchange(page, route, "1".equals(request.getHeader(Protocol.ACCEPT)),
                learner.now(), captured);
        request.setAttribute(Exchange.ATTRIBUTE, exchange);
        try {
            chain.doFilter(request, captured);
        } catch (Throwable failure) {
            try {
                captured.release();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        } finally {
            request.removeAttribute(Exchange.ATTRIBUTE);
        }
        if (request.isAsyncStarted() || exchange.endpoint() == null || exchange.skip()) {
            captured.release();
            return;
        }
        captured.finish();
        complete(request, response, captured, exchange);
    }

    private void complete(HttpServletRequest request, HttpServletResponse response, CapturingResponse captured,
                          Exchange exchange) throws IOException {
        byte[] body = captured.body();
        String contentType = captured.getContentType();
        JsonNode tree = body != null && captured.getStatus() == HttpServletResponse.SC_OK && Json.isJson(contentType)
                ? Json.parse(body, contentType) : null;
        if (captured.capturing()) {
            if (tree != null && bundler.bundle(request, response, exchange, tree)) {
                return;
            }
            captured.release();
        }
        if (tree == null) {
            return;
        }
        List<QueryParam> query = QueryParam.parse(request.getQueryString());
        if (query != null) {
            learner.record(new Observation(exchange.page(), exchange.route(), exchange.endpoint(),
                    exchange.variables(), query, user(request), exchange.arrivedAt(), learner.now(),
                    ValueIndex.of(tree)));
        }
    }

    private static String user(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null) {
            return "principal:" + principal.getName();
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            return "session:" + session.getId();
        }
        return "address:" + request.getRemoteAddr();
    }
}
