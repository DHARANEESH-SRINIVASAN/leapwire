package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class Wire {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private Wire() {
    }

    static HttpResponse<String> get(int port, String path, String... headers) {
        return send(port, path, HttpResponse.BodyHandlers.ofString(), headers);
    }

    static <T> HttpResponse<T> send(int port, String path, HttpResponse.BodyHandler<T> handler, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return CLIENT.send(request.build(), handler);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    static List<JsonNode> lines(HttpResponse<String> response) {
        List<JsonNode> lines = new ArrayList<>();
        String body = response.body();
        if (!body.endsWith("\n")) {
            throw new AssertionError("A bundle ends with a newline: " + body);
        }
        for (String line : body.substring(0, body.length() - 1).split("\n", -1)) {
            lines.add(JSON.readTree(line));
        }
        return lines;
    }

    static boolean bundle(HttpResponse<String> response) {
        return response.headers().firstValue(Protocol.BUNDLE).isPresent();
    }

    record Page(int port, String id, String user, String route, String authorization, List<String> extra) {

        static Page open(int port, String user, String route, String... extra) {
            return new Page(port, UUID.randomUUID().toString().replace("-", ""), user, route,
                    user == null ? null : "Test " + user, List.of(extra));
        }

        static Page basic(int port, String authorization, String route) {
            return new Page(port, UUID.randomUUID().toString().replace("-", ""), null, route, authorization,
                    List.of());
        }

        HttpResponse<String> get(String path) {
            return Wire.get(port, path, headers(false));
        }

        HttpResponse<String> bundle(String path) {
            return Wire.get(port, path, headers(true));
        }

        HttpResponse<Stream<String>> stream(String path) {
            return Wire.send(port, path, HttpResponse.BodyHandlers.ofLines(), headers(true));
        }

        JsonNode json(String path) {
            HttpResponse<String> response = get(path);
            if (response.statusCode() != 200) {
                throw new AssertionError(path + " answered " + response.statusCode());
            }
            return Wire.json(response.body());
        }

        private String[] headers(boolean accept) {
            List<String> headers = new ArrayList<>(List.of(Protocol.PAGE, id, Protocol.ROUTE, route,
                    "Accept-Language", "ta-IN"));
            if (authorization != null) {
                headers.add("Authorization");
                headers.add(authorization);
            }
            if (accept) {
                headers.add(Protocol.ACCEPT);
                headers.add("1");
            }
            headers.addAll(extra);
            return headers.toArray(String[]::new);
        }
    }
}
