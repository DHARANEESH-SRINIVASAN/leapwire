package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Candidates {

    static final int MAX_TARGETS = 16;

    private static final int MAX_PATHS = 4;

    private Candidates() {
    }

    static List<Target> of(Observation observation, ValueIndex index) {
        Template template = Template.parse(observation.endpoint());
        if (template == null) {
            return List.of();
        }
        List<List<JsonPath>> variableOptions = new ArrayList<>();
        for (String name : template.names()) {
            String value = observation.variables().get(name);
            List<JsonPath> paths = value == null ? List.of() : index.paths(value);
            if (paths.isEmpty()) {
                return List.of();
            }
            variableOptions.add(first(paths));
        }
        boolean bound = !template.names().isEmpty();
        List<List<Target.Param>> queryOptions = new ArrayList<>();
        for (QueryParam param : observation.query()) {
            List<Target.Param> choices = bindings(param, index);
            bound |= !choices.isEmpty();
            choices.add(new Target.Constant(String.join("&", param.pieces())));
            queryOptions.add(choices);
        }
        if (!bound) {
            return List.of();
        }
        List<Target> targets = new ArrayList<>();
        for (List<JsonPath> paths : product(variableOptions)) {
            Map<String, JsonPath> variables = new HashMap<>();
            for (int i = 0; i < paths.size(); i++) {
                variables.put(template.names().get(i), paths.get(i));
            }
            for (List<Target.Param> query : product(queryOptions)) {
                if (targets.size() == MAX_TARGETS) {
                    return targets;
                }
                if (variables.isEmpty() && query.stream().allMatch(Target.Constant.class::isInstance)) {
                    continue;
                }
                targets.add(new Target(observation.endpoint(), variables, query));
            }
        }
        return targets;
    }

    private static List<Target.Param> bindings(QueryParam param, ValueIndex index) {
        List<Target.Param> choices = new ArrayList<>();
        String name = param.rawName();
        if (param.values().size() > 1) {
            for (JsonPath path : first(index.lists(param.values()))) {
                choices.add(new Target.Listed(name, path, true, null));
            }
            return choices;
        }
        String value = param.values().get(0);
        for (JsonPath path : first(index.paths(value))) {
            choices.add(new Target.Scalar(name, path));
        }
        if (value.indexOf(',') < 0) {
            for (JsonPath path : first(index.lists(List.of(value)))) {
                choices.add(new Target.Listed(name, path, false, ","));
                choices.add(new Target.Listed(name, path, true, null));
            }
            return choices;
        }
        String separator = separator(param.rawValues().get(0));
        if (separator != null) {
            for (JsonPath path : first(index.lists(List.of(value.split(",", -1))))) {
                choices.add(new Target.Listed(name, path, false, separator));
            }
        }
        return choices;
    }

    private static String separator(String raw) {
        if (raw.indexOf(',') >= 0) {
            return ",";
        }
        int at = raw.toUpperCase(Locale.ROOT).indexOf("%2C");
        return at < 0 ? null : raw.substring(at, at + 3);
    }

    private static <T> List<T> first(List<T> values) {
        return values.size() > MAX_PATHS ? values.subList(0, MAX_PATHS) : values;
    }

    private static <T> List<List<T>> product(List<List<T>> options) {
        List<List<T>> combinations = new ArrayList<>();
        combinations.add(List.of());
        for (List<T> choices : options) {
            List<List<T>> next = new ArrayList<>();
            for (List<T> prefix : combinations) {
                for (T choice : choices) {
                    if (next.size() == MAX_TARGETS) {
                        break;
                    }
                    List<T> combination = new ArrayList<>(prefix);
                    combination.add(choice);
                    next.add(combination);
                }
            }
            combinations = next;
        }
        return combinations;
    }
}
