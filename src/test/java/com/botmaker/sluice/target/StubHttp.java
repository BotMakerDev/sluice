package com.botmaker.sluice.target;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A server that answers what a test says, url by url; a url it was not told about does not answer. */
class StubHttp implements Http {

    private final Map<String, Deque<Integer>> heads = new HashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    /** {@code HEAD url} answers each status in turn, the last one ever after. */
    StubHttp head(String url, Integer... statuses) {
        heads.put(url, new ArrayDeque<>(List.of(statuses)));
        return this;
    }

    StubHttp body(String url, String body) {
        bodies.put(url, body);
        return this;
    }

    @Override
    public Optional<Integer> head(String url) {
        asked.add("HEAD " + url);
        Deque<Integer> statuses = heads.get(url);
        if (statuses == null) {
            return Optional.empty();
        }
        return Optional.of(statuses.size() > 1 ? statuses.removeFirst() : statuses.getFirst());
    }

    @Override
    public Optional<String> get(String url) {
        asked.add("GET " + url);
        return Optional.ofNullable(bodies.get(url));
    }
}
