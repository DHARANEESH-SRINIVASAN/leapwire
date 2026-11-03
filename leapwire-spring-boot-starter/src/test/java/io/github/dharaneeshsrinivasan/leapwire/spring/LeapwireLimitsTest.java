package io.github.dharaneeshsrinivasan.leapwire.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.context-path=/app", "leapwire.min-pages=3", "leapwire.min-users=1",
                "leapwire.max-requests=2", "leapwire.deadline=1s", "leapwire.max-pages=20"})
class LeapwireLimitsTest {

    @LocalServerPort
    int port;

    @Autowired
    Learner learner;

    @Autowired
    TestApplication.Delays delays;

    @AfterEach
    void clearDelays() {
        delays.clear();
    }

    @Test
    void plansAndFetchesUrlsWithTheContextPath() {
        String route = "/context/42";
        for (int i = 0; i < 3; i++) {
            Wire.Page page = Wire.Page.open(port, "alice", route);
            JsonNode order = page.json("/app/api/orders/42");
            page.json("/app/api/customers/" + order.get("customerId").asString());
        }

        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "alice", route).bundle("/app/api/orders/42"));

        assertThat(lines).hasSize(4);
        assertThat(lines.get(1).get("urls").get(0).asString()).isEqualTo("/app/api/customers/142");
        assertThat(lines.get(2).get("url").asString()).isEqualTo("/app/api/customers/142");
        assertThat(lines.get(2).get("status").asInt()).isEqualTo(200);
        assertThat(lines.get(2).get("body").get("seenBy").asString()).isEqualTo("alice");
    }

    @Test
    void capsSpeculativeRequestsAtMaxRequests() {
        String route = "/cap/42";
        for (int i = 0; i < 3; i++) {
            Wire.Page page = Wire.Page.open(port, "alice", route);
            JsonNode order = page.json("/app/api/orders/42");
            page.json("/app/api/customers/" + order.get("customerId").asString());
            page.json("/app/api/orders/" + order.get("id").asString() + "/lines");
            page.json("/app/api/slow-a/" + order.get("slowA").asString());
            page.json("/app/api/slow-b/" + order.get("slowB").asString());
        }

        List<JsonNode> lines = Wire.lines(Wire.Page.open(port, "alice", route).bundle("/app/api/orders/42"));

        assertThat(lines.get(1).get("urls").size()).isEqualTo(2);
        assertThat(lines).filteredOn(line -> line.get("t").asString().equals("part")).hasSize(2);
        JsonNode end = lines.get(lines.size() - 1);
        assertThat(end.get("parts").asInt() + end.get("skipped").asInt()).isEqualTo(2);
    }

    @Test
    void endsTheBundleAtTheDeadlineAndStreamsTheMainLineFirst() {
        String route = "/late/42";
        for (int i = 0; i < 3; i++) {
            Wire.Page page = Wire.Page.open(port, "alice", route);
            JsonNode order = page.json("/app/api/orders/42");
            page.json("/app/api/customers/" + order.get("customerId").asString());
            page.json("/app/api/slow-a/" + order.get("slowA").asString());
        }
        delays.set("a", 3000);

        long started = System.nanoTime();
        HttpResponse<Stream<String>> response = Wire.Page.open(port, "alice", route).stream("/app/api/orders/42");
        List<JsonNode> lines = new ArrayList<>();
        long firstLine = -1;
        try (Stream<String> body = response.body()) {
            Iterator<String> iterator = body.iterator();
            while (iterator.hasNext()) {
                lines.add(Wire.json(iterator.next()));
                if (firstLine < 0) {
                    firstLine = (System.nanoTime() - started) / 1_000_000;
                }
            }
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;

        assertThat(lines).extracting(line -> line.get("t").asString())
                .containsExactly("main", "plan", "part", "part", "end");
        assertThat(lines.get(2).get("url").asString()).isEqualTo("/app/api/customers/142");
        assertThat(lines.get(2).get("status").asInt()).isEqualTo(200);
        assertThat(lines.get(3).get("url").asString()).isEqualTo("/app/api/slow-a/442");
        assertThat(lines.get(3).get("status").asInt()).isZero();
        assertThat(lines.get(3).has("body")).isFalse();
        assertThat(lines.get(4).get("parts").asInt()).isEqualTo(1);
        assertThat(lines.get(4).get("skipped").asInt()).isEqualTo(1);
        assertThat(firstLine).isLessThan(700);
        assertThat(elapsed).isBetween(950L, 2500L);
    }

    @Test
    void keepsAtMostMaxPagesInMemory() {
        for (int i = 0; i < 60; i++) {
            Wire.Page.open(port, "user" + i, "/many/" + i).json("/app/api/orders/" + i);
        }

        assertThat(learner.pages()).isLessThanOrEqualTo(20);
        assertThat(learner.indexedValues()).isPositive();
    }
}
