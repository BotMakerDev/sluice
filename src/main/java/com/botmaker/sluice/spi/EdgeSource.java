package com.botmaker.sluice.spi;

import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;

import java.util.List;

/** Something that knows which module depends on which: the poms, a text pin, an extra edge. Internal in v1. */
public interface EdgeSource {

    /** The edges it finds among {@code modules}; a pair may come back more than once, the graph joins them. */
    List<Edge> edges(List<Module> modules);
}
