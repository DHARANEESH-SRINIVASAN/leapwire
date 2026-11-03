package io.github.dharaneeshsrinivasan.leapwire.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleApplicationTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ALICE = basic("alice");
    private static final String BOB = basic("bob");

    @LocalServerPort
    int port;

    @BeforeAll
    void learnTheScreensFromBothUsers() throws Exception {
        long[][] orders = {{42, 43}, {52, 53}};
        for (int i = 0; i < 12; i++) {
            String user = i % 2 == 0 ? ALICE : BOB;
            orderScreen(user, "/orders/" + orders[i % 2][(i / 2) % 2], orders[i % 2][(i / 2) % 2], page());
            String home = page();
            JsonNode session = json(get(user, "/api/session", home, "/", false));
            JsonNode account = json(get(user, "/api/users/" + session.get("userId").asString(), home, "/", false));
            orderScreen(user, "/", account.get("latestOrderId").asLong(), home);
        }
    }

    @Test
    void bundlesTheOrderScreenWithDataTheUserCouldFetchThemselves() throws Exception {
        int views = json(get(ALICE, "/api/orders/42/views", null, null, false)).get("views").asInt();

        HttpResponse<String> response = get(ALICE, "/api/orders/42", page(), "/orders/42", true);

        assertThat(response.headers().firstValue("Leapwire")).hasValue("bundle");
        List<JsonNode> lines = lines(response);
        assertThat(lines.get(0).get("body")).isEqualTo(json(get(ALICE, "/api/orders/42", null, null, false)));
        List<JsonNode> parts = type(lines, "part");
        assertThat(parts).extracting(part -> part.get("url").asString()).containsExactlyInAnyOrder(
                "/api/customers/19", "/api/orders/42/lines", "/api/products?ids=501,502");
        for (JsonNode part : parts) {
            assertThat(part.get("status").asInt()).isEqualTo(200);
            assertThat(part.get("body")).isEqualTo(json(get(ALICE, part.get("url").asString(), null, null, false)));
        }
        assertThat(lines.get(lines.size() - 1).get("parts").asInt()).isEqualTo(3);
        assertThat(response.body()).doesNotContain("/views");
        assertThat(json(get(ALICE, "/api/orders/42/views", null, null, false)).get("views").asInt())
                .isEqualTo(views + 1);
    }

    @Test
    void sendsA403PartWithoutDataWhenTheUserMayNotReadATarget() throws Exception {
        HttpResponse<String> response = get(BOB, "/api/orders/54", page(), "/orders/54", true);

        assertThat(response.headers().firstValue("Leapwire")).hasValue("bundle");
        List<JsonNode> lines = lines(response);
        JsonNode customer = part(lines, "/api/customers/19");
        assertThat(customer.get("status").asInt()).isEqualTo(403);
        assertThat(customer.has("body")).isFalse();
        assertThat(customer.has("headers")).isFalse();
        assertThat(get(BOB, "/api/customers/19", null, null, false).statusCode()).isEqualTo(403);
        assertThat(part(lines, "/api/orders/54/lines").get("body"))
                .isEqualTo(json(get(BOB, "/api/orders/54/lines", null, null, false)));
        assertThat(part(lines, "/api/products?ids=502").get("status").asInt()).isEqualTo(200);
        JsonNode end = lines.get(lines.size() - 1);
        assertThat(end.get("parts").asInt()).isEqualTo(2);
        assertThat(end.get("skipped").asInt()).isEqualTo(1);
        assertThat(response.body()).doesNotContain("Acme Corp", "Chennai", "Alice");
    }

    @Test
    void neverBundlesARequestTheUserMayNotMake() throws Exception {
        HttpResponse<String> forbidden = get(BOB, "/api/orders/42", page(), "/orders/42", true);
        HttpResponse<String> anonymous = get(null, "/api/orders/42", page(), "/orders/42", true);

        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("Leapwire")).isEmpty();
        assertThat(forbidden.body()).doesNotContain("customerId", "SHIPPED");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Leapwire")).isEmpty();
    }

    @Test
    void followsTheScreenChainFromTheSession() throws Exception {
        List<JsonNode> lines = lines(get(ALICE, "/api/session", page(), "/", true));

        assertThat(type(lines, "plan")).extracting(plan -> plan.get("urls").toString()).containsExactly(
                "[\"/api/users/1\"]", "[\"/api/orders/43\"]", "[\"/api/customers/20\",\"/api/orders/43/lines\"]",
                "[\"/api/products?ids=503\"]");
        assertThat(type(lines, "part")).extracting(part -> part.get("status").asInt()).containsOnly(200);
        assertThat(lines.get(lines.size() - 1).get("parts").asInt()).isEqualTo(5);
    }

    private void orderScreen(String user, String route, long id, String page) throws Exception {
        JsonNode order = json(get(user, "/api/orders/" + id, page, route, false));
        json(get(user, "/api/customers/" + order.get("customerId").asString(), page, route, false));
        JsonNode lines = json(get(user, "/api/orders/" + id + "/lines", page, route, false));
        List<String> ids = new ArrayList<>();
        lines.values().forEach(line -> ids.add(line.get("productId").asString()));
        json(get(user, "/api/products?ids=" + String.join(",", ids), page, route, false));
        json(get(user, "/api/orders/" + id + "/views", page, route, false));
    }

    private HttpResponse<String> get(String authorization, String path, String page, String route, boolean accept)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (page != null) {
            request.header("Leapwire-Page", page).header("Leapwire-Route", route);
        }
        if (accept) {
            request.header("Leapwire-Accept", "1");
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<JsonNode> lines(HttpResponse<String> response) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            lines.add(JSON.readTree(line));
        }
        assertThat(lines.get(0).get("t").asString()).isEqualTo("main");
        assertThat(lines.get(lines.size() - 1).get("t").asString()).isEqualTo("end");
        return lines;
    }

    private static List<JsonNode> type(List<JsonNode> lines, String type) {
        return lines.stream().filter(line -> line.get("t").asString().equals(type)).toList();
    }

    private static JsonNode part(List<JsonNode> lines, String url) {
        return type(lines, "part").stream().filter(line -> line.get("url").asString().equals(url)).findFirst()
                .orElseThrow(() -> new AssertionError("No part for " + url + " in " + lines));
    }

    private static String page() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
    }
}
