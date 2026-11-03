package io.github.dharaneeshsrinivasan.leapwire.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeapwirePassthroughTest {

    private static final String ROUTE = "/pass/42";

    @LocalServerPort
    int port;

    @Autowired
    Learner learner;

    private ConfigurableApplicationContext plain;
    private int plainPort;

    @BeforeAll
    void startTheSameAppWithoutLeapwireAndLearnARule() {
        plain = new SpringApplicationBuilder(TestApplication.class)
                .properties("server.port=0", "leapwire.enabled=false")
                .run();
        plainPort = plain.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        for (int i = 0; i < 10; i++) {
            Wire.Page page = Wire.Page.open(port, List.of("alice", "bob", "carol").get(i % 3), ROUTE);
            page.json("/api/orders/42");
            page.json("/api/customers/142");
        }
    }

    @AfterAll
    void stopThePlainApp() {
        plain.close();
    }

    @Test
    void theRuleIsActiveForClientsThatAskForBundles() {
        assertThat(plain.getBeanNamesForType(Learner.class)).isEmpty();
        assertThat(learner.hasActiveRules("/pass/{}", "/api/orders/{id}")).isTrue();
        assertThat(Wire.bundle(Wire.Page.open(port, "alice", ROUTE).bundle("/api/orders/42"))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("requests")
    void servesRequestsWithoutAPageIdByteForByteAsWithoutLeapwire(String request) throws IOException {
        String withLeapwire = raw(port, request);
        String without = raw(plainPort, request);

        assertThat(withLeapwire).startsWith("HTTP/1.1 ");
        assertThat(withLeapwire).isEqualTo(without);
        assertThat(withLeapwire).doesNotContainIgnoringCase("leapwire: bundle").doesNotContain("x-ndjson");
    }

    Stream<String> requests() {
        return Stream.of(
                "GET /api/orders/42 HTTP/1.1\r\n",
                "GET /api/orders/42 HTTP/1.1\r\nLeapwire-Accept: 1\r\nLeapwire-Route: " + ROUTE + "\r\n",
                "GET /api/orders/42 HTTP/1.1\r\nLeapwire-Accept: 1\r\nLeapwire-Route: " + ROUTE
                        + "\r\nLeapwire-Page: not a page id!\r\n",
                "GET /api/orders/42 HTTP/1.1\r\nLeapwire-Accept: 1\r\nLeapwire-Route: " + ROUTE
                        + "\r\nLeapwire-Page: p1\r\nLeapwire-Speculative: 1\r\n",
                "HEAD /api/orders/42 HTTP/1.1\r\nLeapwire-Accept: 1\r\nLeapwire-Route: " + ROUTE
                        + "\r\nLeapwire-Page: p2\r\n",
                "GET /api/orders/42/lines HTTP/1.1\r\nAccept: application/json\r\n",
                "GET /api/products?ids=4201,4202 HTTP/1.1\r\n",
                "GET /api/tagged/7 HTTP/1.1\r\n",
                "GET /api/text/3 HTTP/1.1\r\n",
                "GET /api/variant/5?as=missing HTTP/1.1\r\n");
    }

    private static String raw(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write((request + "Host: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            return response.replaceFirst("(?i)\r\nDate: [^\r\n]*", "");
        }
    }
}
