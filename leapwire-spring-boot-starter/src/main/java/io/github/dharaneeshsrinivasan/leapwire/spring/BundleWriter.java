package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class BundleWriter {

    private final OutputStream out;
    private int parts;
    private int skipped;

    private BundleWriter(OutputStream out) {
        this.out = out;
    }

    static BundleWriter open(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("ETag", null);
        response.setHeader("Last-Modified", null);
        response.setContentLengthLong(-1);
        response.setContentType(Protocol.NDJSON);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(Protocol.BUNDLE, "bundle");
        if (!varies(response)) {
            response.addHeader("Vary", Protocol.ACCEPT);
        }
        return new BundleWriter(response.getOutputStream());
    }

    void main(Map<String, String> headers, JsonNode body) throws IOException {
        ObjectNode line = Json.MAPPER.createObjectNode();
        line.put("t", "main");
        line.put("status", HttpServletResponse.SC_OK);
        line.set("headers", headers(headers));
        line.set("body", body);
        write(line);
    }

    void plan(List<String> urls) throws IOException {
        ObjectNode line = Json.MAPPER.createObjectNode();
        line.put("t", "plan");
        ArrayNode array = line.putArray("urls");
        urls.forEach(array::add);
        write(line);
    }

    void part(Speculator.Part part) throws IOException {
        ObjectNode line = Json.MAPPER.createObjectNode();
        line.put("t", "part");
        line.put("url", part.url());
        line.put("status", part.status());
        if (part.status() == HttpServletResponse.SC_OK) {
            line.set("headers", headers(part.headers()));
            line.set("body", part.body());
            parts++;
        } else {
            skipped++;
        }
        write(line);
    }

    void end() throws IOException {
        ObjectNode line = Json.MAPPER.createObjectNode();
        line.put("t", "end");
        line.put("parts", parts);
        line.put("skipped", skipped);
        write(line);
    }

    private static ObjectNode headers(Map<String, String> headers) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        headers.forEach(node::put);
        return node;
    }

    private void write(ObjectNode line) throws IOException {
        out.write(Json.MAPPER.writeValueAsBytes(line));
        out.write('\n');
        out.flush();
    }

    private static boolean varies(HttpServletResponse response) {
        for (String value : response.getHeaders("Vary")) {
            for (String token : value.split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (name.equals("*") || name.equals(Protocol.ACCEPT.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
