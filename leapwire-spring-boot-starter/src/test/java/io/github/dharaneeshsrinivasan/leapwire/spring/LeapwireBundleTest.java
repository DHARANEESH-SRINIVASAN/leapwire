package io.github.dharaneeshsrinivasan.leapwire.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeapwireBundleTest {

    private static final List<String> USERS = List.of("alice", "bob", "carol");

    @LocalServerPort
    int port;

    @Autowired
    TestApplication.Calls calls;

    @Autowired
    TestApplication.Delays delays;

    @AfterEach
    void clearDelays() {
        delays.clear();
    }

    @Test
    void bundlesTheLearnedChainAfterTenPagesFromThreeUsers() {
        String route = "/orders/42";
        for (int i = 0; i < 10; i++) {
            orderScreen(Wire.Page.open(port, USERS.get(i % 3), route), 42);
        }
        int customers = calls.count("customer");

        HttpResponse<String> response = Wire.Page.open(port, "alice", route).bundle("/api/orders/42");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/x-ndjson");
        assertThat(response.headers().firstValue("Leapwire")).hasValue("bundle");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().allValues("Vary")).containsExactly("Leapwire-Accept");
        List<JsonNode> lines = Wire.lines(response);
        assertThat(lines).extracting(line -> line.get("t").asString())
                .hasSize(7).startsWith("main", "plan").endsWith("part", "end")
                .containsOnly("main", "plan", "part", "end");
        assertThat(types(lines, "plan")).hasSize(2);
        assertThat(types(lines, "part")).hasSize(3);
        assertPlannedBeforeParts(lines);

        JsonNode main = lines.get(0);
        assertThat(main.get("status").asInt()).isEqualTo(200);
        assertThat(main.get("headers").get("content-type").asString()).isEqualTo("application/json");
        assertThat(main.get("body")).isEqualTo(Wire.json(Wire.get(port, "/api/orders/42").body()));
        assertThat(urls(lines.get(1))).containsExactly("/api/customers/142", "/api/orders/42/lines");
        assertThat(urls(types(lines, "plan").get(1))).containsExactly("/api/products?ids=4201,4202");

        JsonNode customer = part(lines, "/api/customers/142");
        assertThat(customer.get("status").asInt()).isEqualTo(200);
        assertThat(customer.get("headers").get("content-type").asString()).isEqualTo("application/json");
        assertThat(customer.get("body").get("id").asLong()).isEqualTo(142);
        assertThat(customer.get("body").get("seenBy").asString()).isEqualTo("alice");
        assertThat(customer.get("body").get("language").asString()).isEqualTo("ta-IN");
        assertThat(customer.get("body").get("speculative").asString()).isEqualTo("1");
        assertThat(customer.get("body").get("leapwireAccept").isNull()).isTrue();
        assertThat(part(lines, "/api/orders/42/lines").get("body"))
                .isEqualTo(Wire.json(Wire.get(port, "/api/orders/42/lines").body()));
        assertThat(part(lines, "/api/products?ids=4201,4202").get("body"))
                .isEqualTo(Wire.json(Wire.get(port, "/api/products?ids=4201,4202").body()));
        JsonNode end = lines.get(6);
        assertThat(end.get("parts").asInt()).isEqualTo(3);
        assertThat(end.get("skipped").asInt()).isZero();
        assertThat(calls.count("customer") - customers).isEqualTo(1);

        HttpResponse<String> plain = Wire.Page.open(port, "alice", route).get("/api/orders/42");
        assertThat(Wire.bundle(plain)).isFalse();
        assertThat(plain.headers().allValues("Vary")).isEmpty();
        assertThat(plain.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void followsTheChainFourLevelsDeep() {
        String route = "/home";
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            JsonNode session = page.json("/api/session");
            JsonNode user = page.json("/api/users/" + session.get("userId").asString());
            orderScreen(page, user.get("latestOrderId").asLong());
        }

        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "bob", route).bundle("/api/session"));

        List<JsonNode> plans = types(lines, "plan");
        assertThat(plans).hasSize(4);
        assertThat(urls(plans.get(0))).containsExactly("/api/users/7");
        assertThat(urls(plans.get(1))).containsExactly("/api/orders/42");
        assertThat(urls(plans.get(2))).containsExactlyInAnyOrder("/api/customers/142", "/api/orders/42/lines");
        assertThat(urls(plans.get(3))).singleElement().asString().startsWith("/api/products?ids=");
        assertThat(types(lines, "part")).extracting(part -> part.get("status").asInt()).containsOnly(200);
        assertThat(lines.get(lines.size() - 1).get("parts").asInt()).isEqualTo(5);
        assertPlannedBeforeParts(lines);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ids=4201,4202", "ids=4201&ids=4202", "ids=4201%2C4202"})
    void bindsListsInTheJoinModeTheClientUses(String query) {
        String route = "/lists/" + query;
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            page.json("/api/orders/42/lines");
            page.json("/api/products?" + query);
        }

        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "carol", route).bundle("/api/orders/42/lines"));

        assertThat(urls(lines.get(1))).containsExactly("/api/products?" + query);
        JsonNode products = part(lines, "/api/products?" + query);
        assertThat(products.get("status").asInt()).isEqualTo(200);
        assertThat(products.get("body")).isEqualTo(Wire.json(Wire.get(port, "/api/products?" + query).body()));
        assertThat(products.get("body").size()).isEqualTo(2);
    }

    @Test
    void staysOffWithFewerThanMinUsers() {
        String route = "/solo/42";
        for (int i = 0; i < 15; i++) {
            orderScreen(Wire.Page.open(port, "alice", route), 42);
        }

        HttpResponse<String> response = Wire.Page.open(port, "alice", route).bundle("/api/orders/42");

        assertThat(Wire.bundle(response)).isFalse();
        assertThat(Wire.json(response.body())).isEqualTo(Wire.json(Wire.get(port, "/api/orders/42").body()));
    }

    @Test
    void staysOffBelowMinConfidence() {
        String route = "/noisy/42";
        for (int i = 0; i < 12; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            page.json("/api/orders/42");
            page.json(i < 7 ? "/api/customers/142" : "/api/customers/999");
        }

        HttpResponse<String> response = Wire.Page.open(port, "bob", route).bundle("/api/orders/42");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Wire.bundle(response)).isFalse();
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void neverExecutesSkippedEndpoints() {
        String route = "/skips/42";
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            JsonNode order = page.json("/api/orders/42");
            page.json("/api/audit/" + order.get("auditId").asString());
            page.json("/api/hidden/" + order.get("hiddenId").asString());
            page.json("/api/customers/" + order.get("customerId").asString());
        }
        int audits = calls.count("audit");
        int hidden = calls.count("hidden");

        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "alice", route).bundle("/api/orders/42"));
        HttpResponse<String> skippedPrimary = Wire.Page.open(port, "alice", route).bundle("/api/audit/242");
        HttpResponse<String> speculative = Wire.get(port, "/api/audit/242", Protocol.PAGE, "probe",
                Protocol.SPECULATIVE, "1");

        assertThat(urls(lines.get(1))).containsExactly("/api/customers/142");
        assertThat(response(lines)).doesNotContain("/api/audit", "/api/hidden");
        assertThat(Wire.bundle(skippedPrimary)).isFalse();
        assertThat(speculative.statusCode()).isEqualTo(409);
        assertThat(calls.count("audit")).isEqualTo(audits + 1);
        assertThat(calls.count("hidden")).isEqualTo(hidden);
    }

    @Test
    void returnsNonJsonAndNon200PrimariesUnchanged() {
        String route = "/variant/5";
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            JsonNode variant = page.json("/api/variant/5");
            page.json("/api/customers/" + variant.get("customerId").asString());
        }
        assertThat(Wire.bundle(Wire.Page.open(port, "alice", route).bundle("/api/variant/5"))).isTrue();

        for (String path : List.of("/api/variant/5?as=text", "/api/variant/5?as=missing")) {
            HttpResponse<String> response = Wire.Page.open(port, "alice", route).bundle(path);
            HttpResponse<String> plain = Wire.get(port, path);

            assertThat(Wire.bundle(response)).isFalse();
            assertThat(response.statusCode()).isEqualTo(plain.statusCode());
            assertThat(response.headers().firstValue("Content-Type")).isEqualTo(plain.headers()
                    .firstValue("Content-Type"));
            assertThat(response.body()).isEqualTo(plain.body());
        }
    }

    @Test
    void runsIndependentTargetsConcurrently() {
        String route = "/parallel/42";
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            JsonNode order = page.json("/api/orders/42");
            page.json("/api/slow-a/" + order.get("slowA").asString());
            page.json("/api/slow-b/" + order.get("slowB").asString());
        }
        delays.set("a", 600);
        delays.set("b", 600);

        long started = System.nanoTime();
        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "alice", route).bundle("/api/orders/42"));
        long elapsed = (System.nanoTime() - started) / 1_000_000;

        assertThat(types(lines, "part")).extracting(part -> part.get("status").asInt()).containsExactly(200, 200);
        assertThat(elapsed).isBetween(600L, 1100L);
    }

    @Test
    void neverLearnsFromOrBundlesSpeculativeRequests() {
        String route = "/ghost/42";
        for (int i = 0; i < 12; i++) {
            orderScreen(Wire.Page.open(port, USERS.get(i % 3), route, Protocol.SPECULATIVE, "1"), 42);
        }
        assertThat(Wire.bundle(Wire.Page.open(port, "alice", route).bundle("/api/orders/42"))).isFalse();

        for (int i = 0; i < 10; i++) {
            orderScreen(Wire.Page.open(port, USERS.get(i % 3), route), 42);
        }
        HttpResponse<String> marked = Wire.Page.open(port, "alice", route, Protocol.SPECULATIVE, "1")
                .bundle("/api/orders/42");

        assertThat(Wire.bundle(Wire.Page.open(port, "alice", route).bundle("/api/orders/42"))).isTrue();
        assertThat(Wire.bundle(marked)).isFalse();
        assertThat(Wire.json(marked.body())).isEqualTo(Wire.json(Wire.get(port, "/api/orders/42").body()));
    }

    @Test
    void movesValidatorsIntoTheMainLineAndKeepsEveryLineCompact() {
        String route = "/tagged/7";
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            JsonNode tagged = page.json("/api/tagged/7");
            page.json("/api/customers/" + tagged.get("customerId").asString());
        }

        HttpResponse<String> response = Wire.Page.open(port, "alice", route).bundle("/api/tagged/7");

        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/x-ndjson");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(response.headers().firstValue("Last-Modified")).isEmpty();
        assertThat(response.headers().firstValue("Content-Length")).isEmpty();
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        List<JsonNode> lines = Wire.lines(response);
        assertThat(lines).hasSize(4);
        JsonNode headers = lines.get(0).get("headers");
        assertThat(headers.get("content-type").asString()).startsWith("application/json");
        assertThat(headers.get("etag").asString()).isEqualTo("\"v7\"");
        assertThat(headers.get("last-modified").asString()).isEqualTo("Tue, 14 Nov 2023 22:13:20 GMT");
        assertThat(headers.get("cache-control").asString()).isEqualTo("no-cache");
        assertThat(response.body().split("\n", -1)[0]).endsWith(",\"body\":{\"id\":7,\"customerId\":107,\"price\":1.50}}");
    }

    private void orderScreen(Wire.Page page, long id) {
        JsonNode order = page.json("/api/orders/" + id);
        page.json("/api/customers/" + order.get("customerId").asString());
        JsonNode lines = page.json("/api/orders/" + order.get("id").asString() + "/lines");
        page.json("/api/products?ids=" + lines.get(0).get("productId").asString() + ","
                + lines.get(1).get("productId").asString());
    }

    private static List<JsonNode> types(List<JsonNode> lines, String type) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode line : lines) {
            if (line.get("t").asString().equals(type)) {
                found.add(line);
            }
        }
        return found;
    }

    private static List<String> urls(JsonNode plan) {
        assertThat(plan.get("t").asString()).isEqualTo("plan");
        List<String> urls = new ArrayList<>();
        plan.get("urls").values().forEach(url -> urls.add(url.asString()));
        return urls;
    }

    private static JsonNode part(List<JsonNode> lines, String url) {
        for (JsonNode line : types(lines, "part")) {
            if (line.get("url").asString().equals(url)) {
                return line;
            }
        }
        throw new AssertionError("No part for " + url + " in " + lines);
    }

    private static String response(List<JsonNode> lines) {
        return lines.toString();
    }

    static void assertPlannedBeforeParts(List<JsonNode> lines) {
        Set<String> planned = new HashSet<>();
        Set<String> delivered = new HashSet<>();
        for (JsonNode line : lines) {
            String type = line.get("t").asString();
            if (type.equals("plan")) {
                line.get("urls").values().forEach(url -> assertThat(planned.add(url.asString())).isTrue());
            } else if (type.equals("part")) {
                String url = line.get("url").asString();
                assertThat(planned).contains(url);
                assertThat(delivered.add(url)).isTrue();
            }
        }
        assertThat(delivered).isEqualTo(planned);
    }
}
