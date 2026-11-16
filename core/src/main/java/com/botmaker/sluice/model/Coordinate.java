// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.model;

/** A Maven artifact's identity, without its version. */
public record Coordinate(String groupId, String artifactId) {

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
