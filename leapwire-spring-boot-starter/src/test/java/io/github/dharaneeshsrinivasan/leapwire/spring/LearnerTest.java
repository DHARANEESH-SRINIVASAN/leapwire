package io.github.dharaneeshsrinivasan.leapwire.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LearnerTest {

    private static final List<String> USERS = List.of("alice", "bob", "carol");

    private final AtomicLong clock = new AtomicLong(100 * Learner.HOUR);

    @Test
    void normalisesRouteKeys() {
        assertThat(Protocol.routeKey("/orders/42")).isEqualTo("/orders/{}");
        assertThat(Protocol.routeKey("/orders/42/lines/7")).isEqualTo("/orders/{}/lines/{}");
        assertThat(Protocol.routeKey("/u/0f8fad5b-d9cb-469f-a165-70867728950e")).isEqualTo("/u/{}");
        assertThat(Protocol.routeKey("/t/abcdefghijklmnopqrstuvwxyz")).isEqualTo("/t/{}");
        assertThat(Protocol.routeKey("/t/abcdefghijklmnopqrstuvwx")).isEqualTo("/t/abcdefghijklmnopqrstuvwx");
        assertThat(Protocol.routeKey("OrderDetail")).isEqualTo("OrderDetail");
        assertThat(Protocol.routeKey("/orders/a42")).isEqualTo("/orders/a42");
        assertThat(Protocol.routeKey("/" + "a".repeat(256))).isNull();
        assertThat(Protocol.routeKey(null)).isEmpty();
        assertThat(Protocol.validPage("Ab_9-z")).isTrue();
        assertThat(Protocol.validPage("a".repeat(65))).isFalse();
        assertThat(Protocol.validPage("has space")).isFalse();
    }

    @Test
    void indexesScalarsAndEveryElementOfArrays() {
        ValueIndex index = ValueIndex.of(Wire.json("{\"customerId\":19,\"customer\":{\"id\":\"19\"},"
                + "\"lines\":[{\"productId\":501},{\"productId\":502}],\"ids\":[7,8],\"flag\":true,\"none\":null,"
                + "\"price\":1.5,\"empty\":\"\",\"long\":\"" + "x".repeat(129) + "\"}"));
        ValueIndex root = ValueIndex.of(Wire.json("[{\"productId\":501},{\"productId\":502}]"));

        assertThat(index.paths("19")).extracting(JsonPath::toString).containsExactly("$.customerId", "$.customer.id");
        assertThat(index.lists(List.of("501", "502"))).extracting(JsonPath::toString)
                .containsExactly("$.lines[*].productId");
        assertThat(index.lists(List.of("7", "8"))).extracting(JsonPath::toString).containsExactly("$.ids[*]");
        assertThat(index.paths("true")).isEmpty();
        assertThat(index.paths("1.5")).isEmpty();
        assertThat(index.size()).isEqualTo(6);
        assertThat(root.lists(List.of("501", "502"))).extracting(JsonPath::toString)
                .containsExactly("$[*].productId");
    }

    @Test
    void activatesAfterMinPagesFromMinUsersAndDecaysEveryHour() {
        Learner learner = new Learner(properties(10_000, 64, 1_000_000), clock::get);
        for (int i = 0; i < 9; i++) {
            visit(learner, "p" + i, USERS.get(i % 3), true);
        }
        assertThat(learner.activeRules("/orders/{}", "/api/orders/{id}")).isEmpty();

        visit(learner, "p9", "alice", true);
        List<Target> rules = learner.activeRules("/orders/{}", "/api/orders/{id}");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).url(Wire.json("{\"customerId\":7}"))).isEqualTo("/api/customers/7");
        assertThat(rules.get(0).url(Wire.json("{\"customerId\":null}"))).isNull();

        clock.addAndGet(Learner.HOUR);
        assertThat(learner.activeRules("/orders/{}", "/api/orders/{id}")).isEmpty();
        for (int i = 0; i < 5; i++) {
            visit(learner, "q" + i, USERS.get(i % 3), true);
        }
        assertThat(learner.activeRules("/orders/{}", "/api/orders/{id}")).hasSize(1);
    }

    @Test
    void needsMinUsersAndMinConfidence() {
        Learner solo = new Learner(properties(10_000, 64, 1_000_000), clock::get);
        Learner noisy = new Learner(properties(10_000, 64, 1_000_000), clock::get);
        for (int i = 0; i < 20; i++) {
            visit(solo, "s" + i, "alice", true);
            visit(noisy, "n" + i, USERS.get(i % 3), i % 5 != 0);
        }

        assertThat(solo.activeRules("/orders/{}", "/api/orders/{id}")).isEmpty();
        assertThat(noisy.activeRules("/orders/{}", "/api/orders/{id}")).isEmpty();
    }

    @Test
    void keepsQueryParametersOnlyWhenTheyAreBoundOrConstant() {
        Learner constant = new Learner(properties(10_000, 64, 1_000_000), clock::get);
        Learner changing = new Learner(properties(10_000, 64, 1_000_000), clock::get);
        for (int i = 0; i < 10; i++) {
            lines(constant, "c" + i, USERS.get(i % 3), "ids=501,502&sort=name");
            lines(changing, "v" + i, USERS.get(i % 3), "ids=501,502&nonce=" + i);
        }

        List<Target> rules = constant.activeRules("/orders/{}", "/api/orders/{id}/lines");
        assertThat(rules).extracting(rule -> rule.url(Wire.json("[{\"productId\":9},{\"productId\":8}]")))
                .contains("/api/products?ids=9,8&sort=name");
        assertThat(changing.activeRules("/orders/{}", "/api/orders/{id}/lines")).isEmpty();
    }

    @Test
    void evictsTheLeastRecentlyUsedPagesBeyondMaxPages() {
        Learner learner = new Learner(properties(5, 64, 1_000_000), clock::get);
        for (int i = 0; i < 12; i++) {
            visit(learner, "p" + i, "alice", true);
        }

        assertThat(learner.pages()).isEqualTo(5);
        assertThat(learner.recorded("p11")).isEqualTo(2);
        assertThat(learner.recorded("p0")).isZero();
        assertThat(learner.indexedValues()).isEqualTo(5 * 4);
    }

    @Test
    void forgetsPagesThirtySecondsAfterTheirLastRequest() {
        Learner learner = new Learner(properties(10_000, 64, 1_000_000), clock::get);
        for (int i = 0; i < 3; i++) {
            visit(learner, "p" + i, "alice", true);
        }
        clock.addAndGet(Duration.ofSeconds(31).toMillis());
        visit(learner, "late", "alice", true);

        assertThat(learner.pages()).isEqualTo(1);
        assertThat(learner.indexedValues()).isEqualTo(4);
    }

    @Test
    void keepsAtMostMaxPageRequestsPerPage() {
        Learner learner = new Learner(properties(10_000, 3, 1_000_000), clock::get);
        visit(learner, "p", "alice", true);
        visit(learner, "p", "alice", true);

        assertThat(learner.recorded("p")).isEqualTo(3);
    }

    @Test
    void keepsTheIndexWithinMaxIndexedValues() {
        Learner learner = new Learner(properties(10_000, 64, 10), clock::get);
        for (int i = 0; i < 10; i++) {
            visit(learner, "p" + i, "alice", true);
        }

        assertThat(learner.indexedValues()).isLessThanOrEqualTo(10);
        assertThat(learner.pages()).isLessThanOrEqualTo(3);
    }

    private void visit(Learner learner, String page, String user, boolean follow) {
        record(learner, page, user, "/api/orders/{id}", Map.of("id", "42"), null, "{\"id\":42,\"customerId\":142}");
        record(learner, page, user, "/api/customers/{id}", Map.of("id", follow ? "142" : "999"), null,
                "{\"id\":" + (follow ? 142 : 999) + ",\"name\":\"Acme\"}");
    }

    private void lines(Learner learner, String page, String user, String query) {
        record(learner, page, user, "/api/orders/{id}/lines", Map.of("id", "42"), null,
                "[{\"productId\":501},{\"productId\":502}]");
        record(learner, page, user, "/api/products", Map.of(), query, "[{\"id\":501},{\"id\":502}]");
    }

    private void record(Learner learner, String page, String user, String endpoint, Map<String, String> variables,
                        String query, String json) {
        long arrived = clock.incrementAndGet();
        learner.record(new Observation(page, "/orders/{}", endpoint, variables, QueryParam.parse(query),
                "principal:" + user, arrived, clock.incrementAndGet(), ValueIndex.of(Wire.json(json))));
    }

    static LeapwireProperties properties(int maxPages, int maxPageRequests, long maxIndexedValues) {
        return new LeapwireProperties(true, 10, 3, 0.9, 8, 3, Duration.ofSeconds(2), Duration.ofSeconds(30),
                maxPageRequests, maxPages, maxIndexedValues, 262_144);
    }
}
