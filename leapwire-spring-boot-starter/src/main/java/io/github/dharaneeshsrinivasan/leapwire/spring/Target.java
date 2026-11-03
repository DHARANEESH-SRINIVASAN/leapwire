package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;

record Target(String endpoint, Map<String, JsonPath> variables, List<Param> query) {

    Target {
        variables = Map.copyOf(variables);
        query = List.copyOf(query);
    }

    String url(JsonNode body) {
        Template template = Template.parse(endpoint);
        if (template == null) {
            return null;
        }
        StringBuilder url = new StringBuilder();
        for (int i = 0; i < template.names().size(); i++) {
            url.append(template.literals().get(i));
            JsonPath path = variables.get(template.names().get(i));
            String text = path == null ? null : JsonPath.text(path.single(body));
            if (text == null || text.equals(".") || text.equals("..")) {
                return null;
            }
            url.append(Protocol.encode(text));
        }
        url.append(template.literals().get(template.names().size()));
        StringJoiner pieces = new StringJoiner("&");
        for (Param param : query) {
            String piece = param.render(body);
            if (piece == null) {
                return null;
            }
            pieces.add(piece);
        }
        if (pieces.length() > 0) {
            url.append('?').append(pieces);
        }
        return url.toString();
    }

    sealed interface Param permits Constant, Scalar, Listed {

        String render(JsonNode body);
    }

    record Constant(String raw) implements Param {

        @Override
        public String render(JsonNode body) {
            return raw;
        }
    }

    record Scalar(String name, JsonPath path) implements Param {

        @Override
        public String render(JsonNode body) {
            String text = JsonPath.text(path.single(body));
            return text == null ? null : name + "=" + Protocol.encode(text);
        }
    }

    record Listed(String name, JsonPath path, boolean repeat, String separator) implements Param {

        @Override
        public String render(JsonNode body) {
            List<String> values = path.values(body);
            if (values.isEmpty()) {
                return null;
            }
            StringJoiner out = new StringJoiner(repeat ? "&" : separator, repeat ? "" : name + "=", "");
            for (String value : values) {
                out.add(repeat ? name + "=" + Protocol.encode(value) : Protocol.encode(value));
            }
            return out.toString();
        }
    }
}
