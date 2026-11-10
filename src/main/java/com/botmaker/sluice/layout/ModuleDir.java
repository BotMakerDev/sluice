package com.botmaker.sluice.layout;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One repository of the project as the layout finds it, before anything reads its pom.
 *
 * @param path   its path in the umbrella, {@code /}-separated on every system: the name a person types
 * @param dir    its directory on disk
 * @param remote the URL the umbrella records for it, when it records one
 */
public record ModuleDir(String path, Path dir, Optional<String> remote) {
}
