package com.botmaker.sluice.model;

/** A Maven artifact's identity, without its version. */
public record Coordinate(String groupId, String artifactId) {

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
