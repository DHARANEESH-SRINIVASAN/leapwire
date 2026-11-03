package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record QueryParam(String rawName, List<String> pieces, List<String> rawValues, List<String> values) {

    static List<QueryParam> parse(String query) {
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        Map<String, QueryParam> params = new LinkedHashMap<>();
        for (String piece : query.split("&")) {
            if (piece.isEmpty()) {
                continue;
            }
            int equals = piece.indexOf('=');
            String rawName = equals < 0 ? piece : piece.substring(0, equals);
            String rawValue = equals < 0 ? "" : piece.substring(equals + 1);
            String name;
            String value;
            try {
                name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            QueryParam param = params.computeIfAbsent(name,
                    key -> new QueryParam(rawName, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            param.pieces().add(piece);
            param.rawValues().add(rawValue);
            param.values().add(value);
        }
        List<QueryParam> parsed = new ArrayList<>(params.size());
        for (QueryParam param : params.values()) {
            parsed.add(new QueryParam(param.rawName(), List.copyOf(param.pieces()), List.copyOf(param.rawValues()),
                    List.copyOf(param.values())));
        }
        return parsed;
    }
}
