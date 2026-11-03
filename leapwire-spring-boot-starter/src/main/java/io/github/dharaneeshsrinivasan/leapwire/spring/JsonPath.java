package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class JsonPath {

    static final JsonPath ROOT = new JsonPath(new String[0], false);

    private static final int MAX_TEXT = 128;

    private final String[] steps;
    private final boolean list;
    private final int hash;

    private JsonPath(String[] steps, boolean list) {
        this.steps = steps;
        this.list = list;
        this.hash = Arrays.hashCode(steps);
    }

    JsonPath field(String name) {
        return append(name, list);
    }

    JsonPath any() {
        return append(null, true);
    }

    boolean list() {
        return list;
    }

    JsonNode single(JsonNode root) {
        if (list) {
            return null;
        }
        JsonNode node = root;
        for (String step : steps) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(step);
        }
        return node;
    }

    List<String> values(JsonNode root) {
        List<String> values = new ArrayList<>();
        collect(root, 0, values);
        return values;
    }

    static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        String text = node.isString() ? node.stringValue() : node.isIntegralNumber() ? node.asString() : null;
        return text == null || text.isEmpty() || text.length() > MAX_TEXT ? null : text;
    }

    private void collect(JsonNode node, int index, List<String> values) {
        if (node == null) {
            return;
        }
        if (index == steps.length) {
            String text = text(node);
            if (text != null) {
                values.add(text);
            }
            return;
        }
        String step = steps[index];
        if (step == null) {
            if (node.isArray()) {
                for (JsonNode element : node.values()) {
                    collect(element, index + 1, values);
                }
            }
        } else if (node.isObject()) {
            collect(node.get(step), index + 1, values);
        }
    }

    private JsonPath append(String step, boolean list) {
        String[] next = Arrays.copyOf(steps, steps.length + 1);
        next[steps.length] = step;
        return new JsonPath(next, list);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof JsonPath path && hash == path.hash && Arrays.equals(steps, path.steps);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("$");
        for (String step : steps) {
            if (step == null) {
                text.append("[*]");
            } else {
                text.append('.').append(step);
            }
        }
        return text.toString();
    }
}
