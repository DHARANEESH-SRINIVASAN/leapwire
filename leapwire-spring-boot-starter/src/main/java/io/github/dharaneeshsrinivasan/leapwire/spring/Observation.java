package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.util.List;
import java.util.Map;

record Observation(String page, String route, String endpoint, Map<String, String> variables,
                   List<QueryParam> query, String user, long arrivedAt, long completedAt, ValueIndex index) {
}
