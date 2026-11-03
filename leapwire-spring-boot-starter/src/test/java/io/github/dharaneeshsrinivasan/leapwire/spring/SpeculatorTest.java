package io.github.dharaneeshsrinivasan.leapwire.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class SpeculatorTest {

    @Test
    void speculatesOverPlainHttpOnTheMainServerPort() {
        try (Speculator speculator = new Speculator(new MockEnvironment(), LearnerTest.properties(10, 64, 100))) {
            assertThat(speculator.ready()).isFalse();
            speculator.onApplicationEvent(started(8080, null));

            assertThat(speculator.ready()).isTrue();
        }
    }

    @Test
    void ignoresTheManagementServer() {
        try (Speculator speculator = new Speculator(new MockEnvironment(), LearnerTest.properties(10, 64, 100))) {
            speculator.onApplicationEvent(started(9090, "management"));

            assertThat(speculator.ready()).isFalse();
        }
    }

    @Test
    void turnsOffWithOneClearWarningWhenTheServerUsesTls(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.bundle", "web");
        try (Speculator speculator = new Speculator(environment, LearnerTest.properties(10, 64, 100))) {
            speculator.onApplicationEvent(started(8443, null));

            assertThat(speculator.ready()).isFalse();
            assertThat(output).containsOnlyOnce("Leapwire is off: the server uses TLS (server.ssl)");
        }
    }

    @Test
    void staysOnWhenTlsIsExplicitlyDisabled() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.enabled", "false");
        try (Speculator speculator = new Speculator(environment, LearnerTest.properties(10, 64, 100))) {
            speculator.onApplicationEvent(started(8080, null));

            assertThat(speculator.ready()).isTrue();
        }
    }

    private static ServletWebServerInitializedEvent started(int port, String namespace) {
        ServletWebServerApplicationContext context = new ServletWebServerApplicationContext();
        context.setServerNamespace(namespace);
        WebServer server = new WebServer() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public int getPort() {
                return port;
            }
        };
        return new ServletWebServerInitializedEvent(server, context);
    }
}
