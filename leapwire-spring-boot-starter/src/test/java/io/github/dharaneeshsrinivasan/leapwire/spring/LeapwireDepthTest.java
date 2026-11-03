package io.github.dharaneeshsrinivasan.leapwire.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "leapwire.max-depth=2")
class LeapwireDepthTest {

    private static final List<String> USERS = List.of("alice", "bob", "carol");

    @LocalServerPort
    int port;

    @Test
    void stopsAtMaxDepthAndTheNextRequestCarriesTheRestOfTheChain() {
        String route = "/depth";
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, USERS.get(i % 3), route);
            JsonNode session = page.json("/api/session");
            JsonNode user = page.json("/api/users/" + session.get("userId").asString());
            JsonNode order = page.json("/api/orders/" + user.get("latestOrderId").asString());
            page.json("/api/customers/" + order.get("customerId").asString());
            JsonNode lines = page.json("/api/orders/" + order.get("id").asString() + "/lines");
            page.json("/api/products?ids=" + lines.get(0).get("productId").asString() + ","
                    + lines.get(1).get("productId").asString());
        }

        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "bob", route).bundle("/api/session"));

        List<String> planned = new ArrayList<>();
        for (JsonNode line : lines) {
            if (line.get("t").asString().equals("plan")) {
                line.get("urls").values().forEach(url -> planned.add(url.asString()));
            }
        }
        assertThat(planned).containsExactly("/api/users/7", "/api/orders/42");
        assertThat(lines.get(lines.size() - 1).get("parts").asInt()).isEqualTo(2);

        List<JsonNode> rest = Wire.lines(Wire.Page.open(port, "bob", route).bundle("/api/orders/42/lines"));

        List<String> next = new ArrayList<>();
        for (JsonNode line : rest) {
            if (line.get("t").asString().equals("plan")) {
                line.get("urls").values().forEach(url -> next.add(url.asString()));
            }
        }
        assertThat(next).containsExactly("/api/products?ids=4201,4202");
        assertThat(rest.get(rest.size() - 1).get("parts").asInt()).isEqualTo(1);
    }
}
