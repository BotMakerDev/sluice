package com.botmaker.sluice.layout;

import java.nio.file.Path;
import java.util.List;

/**
 * Where a project's repositories are. v1 has one layout, {@link UmbrellaLayout}; another (a directory of sibling
 * clones) would be another implementation, and nothing past this interface would change.
 */
public interface Layout {

    /** The directory that holds the configuration, the logs and the state of a release. */
    Path root();

    /** Every repository, in the order the layout lists them: the order ties are broken by. */
    List<ModuleDir> modules();
}
