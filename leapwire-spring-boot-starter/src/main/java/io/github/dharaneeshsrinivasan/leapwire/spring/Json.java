package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final List<String> FORWARDED = List.of("content-type", "etag", "last-modified", "cache-control");

    private Json() {
    }

    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return "application".equals(type.getType())
                    && ("json".equals(type.getSubtype()) || "json".equals(type.getSubtypeSuffix()));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    static JsonNode parse(byte[] body, String contentType) {
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            JsonNode node = charset == null || StandardCharsets.UTF_8.equals(charset)
                    ? MAPPER.readTree(body)
                    : MAPPER.readTree(new String(body, charset));
            return node == null || node.isMissingNode() ? null : node;
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    static Map<String, String> headers(Function<String, String> lookup) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED) {
            String value = lookup.apply(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }
}
