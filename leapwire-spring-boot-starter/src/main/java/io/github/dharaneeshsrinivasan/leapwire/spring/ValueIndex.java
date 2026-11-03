package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

final class ValueIndex {

    static final int MAX_VALUES = 10_000;

    private final Map<String, List<JsonPath>> scalars = new HashMap<>();
    private final Map<JsonPath, List<String>> lists = new LinkedHashMap<>();
    private int size;

    private ValueIndex() {
    }

    static ValueIndex of(JsonNode root) {
        ValueIndex index = new ValueIndex();
        index.walk(root, JsonPath.ROOT);
        return index;
    }

    int size() {
        return size;
    }

    List<JsonPath> paths(String value) {
        return scalars.getOrDefault(value, List.of());
    }

    List<JsonPath> lists(List<String> values) {
        List<JsonPath> matches = new ArrayList<>();
        for (Map.Entry<JsonPath, List<String>> entry : lists.entrySet()) {
            if (entry.getValue().equals(values)) {
                matches.add(entry.getKey());
            }
        }
        return matches;
    }

    private void walk(JsonNode node, JsonPath path) {
        if (size >= MAX_VALUES) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                walk(property.getValue(), path.field(property.getKey()));
            }
        } else if (node.isArray()) {
            JsonPath element = path.any();
            for (JsonNode value : node.values()) {
                walk(value, element);
            }
        } else {
            String text = JsonPath.text(node);
            if (text == null) {
                return;
            }
            size++;
            if (path.list()) {
                lists.computeIfAbsent(path, key -> new ArrayList<>()).add(text);
            } else {
                scalars.computeIfAbsent(text, key -> new ArrayList<>(1)).add(path);
            }
        }
    }
}
