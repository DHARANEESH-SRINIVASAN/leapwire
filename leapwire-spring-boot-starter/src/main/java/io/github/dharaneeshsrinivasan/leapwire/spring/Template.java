package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.ArrayList;
import java.util.List;

record Template(List<String> literals, List<String> names) {

    static Template parse(String pattern) {
        if (pattern == null || !pattern.startsWith("/")) {
            return null;
        }
        List<String> literals = new ArrayList<>();
        List<String> names = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int depth = 1;
                int end = i + 1;
                while (end < pattern.length() && depth > 0) {
                    char inner = pattern.charAt(end);
                    if (inner == '{') {
                        depth++;
                    } else if (inner == '}') {
                        depth--;
                    }
                    end++;
                }
                if (depth != 0) {
                    return null;
                }
                String variable = pattern.substring(i + 1, end - 1);
                int colon = variable.indexOf(':');
                String name = colon < 0 ? variable : variable.substring(0, colon);
                if (name.isEmpty() || name.startsWith("*") || names.contains(name)) {
                    return null;
                }
                literals.add(literal.toString());
                literal.setLength(0);
                names.add(name);
                i = end;
            } else if (c == '*' || c == '?' || c == '}') {
                return null;
            } else {
                literal.append(c);
                i++;
            }
        }
        literals.add(literal.toString());
        return new Template(List.copyOf(literals), List.copyOf(names));
    }
}
