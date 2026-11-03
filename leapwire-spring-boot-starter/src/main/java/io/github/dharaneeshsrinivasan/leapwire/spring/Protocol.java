package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class Protocol {

    static final String ACCEPT = "Leapwire-Accept";
    static final String PAGE = "Leapwire-Page";
    static final String ROUTE = "Leapwire-Route";
    static final String SPECULATIVE = "Leapwire-Speculative";
    static final String BUNDLE = "Leapwire";
    static final String NDJSON = "application/x-ndjson";

    private static final int MAX_ROUTE = 256;
    private static final Pattern PAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LONG_ID = Pattern.compile("[A-Za-z0-9_-]{25,}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Protocol() {
    }

    static boolean validPage(String page) {
        return page != null && PAGE_ID.matcher(page).matches();
    }

    static String routeKey(String route) {
        if (route == null) {
            return "";
        }
        if (route.length() > MAX_ROUTE) {
            return null;
        }
        String[] segments = route.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (DIGITS.matcher(segment).matches() || UUID.matcher(segment).matches()
                    || LONG_ID.matcher(segment).matches()) {
                segments[i] = "{}";
            }
        }
        return String.join("/", segments);
    }

    static String encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || "-_.!~*'()".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return out.toString();
    }
}
