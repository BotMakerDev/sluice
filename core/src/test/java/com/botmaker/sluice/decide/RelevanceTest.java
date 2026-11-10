package com.botmaker.sluice.decide;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevanceTest {

    @Test
    void defaultsIgnoreMarkdownAndDocs() {
        Relevance relevance = Relevance.of(Config.defaults());

        assertFalse(relevance.relevant("README.md"));
        assertFalse(relevance.relevant("src/main/notes/deep.md"));
        assertFalse(relevance.relevant("docs/img/a.png"));
        assertTrue(relevance.relevant("pom.xml"));
        assertTrue(relevance.relevant("src/docs/a.png"));
        assertTrue(relevance.relevant("src/main/java/A.mdx"));
        assertTrue(relevance.relevant(".github/workflows/ci.yml"));
    }

    @Test
    void globsStayInsideOneNameUnlessDoubled() {
        Relevance relevance = Relevance.of(ConfigLoader.parse("""
                relevance:
                  ignore: ["*.txt", ".idea/**", "a/**/z.?"]
                """, "t"));

        assertFalse(relevance.relevant("notes.txt"));
        assertTrue(relevance.relevant("src/notes.txt"));
        assertFalse(relevance.relevant(".idea/x/y.xml"));
        assertFalse(relevance.relevant("a/z.c"));
        assertFalse(relevance.relevant("a/b/c/z.c"));
        assertTrue(relevance.relevant("a/b/z.cc"));
        assertTrue(relevance.relevant("README.md"));
    }

    @Test
    void classifiesByTheFilter() {
        Relevance relevance = Relevance.of(Config.defaults());

        assertEquals(ChangeKind.NONE, ChangeKind.classify(List.of(), relevance));
        assertEquals(ChangeKind.IGNORED_ONLY, ChangeKind.classify(List.of("README.md", "docs/a"), relevance));
        assertEquals(ChangeKind.CHANGED, ChangeKind.classify(List.of("README.md", "pom.xml"), relevance));
    }

    @Test
    void tagNamesReadByThePattern() {
        assertEquals(Optional.of(new Version(1, 10, 0)), Tags.version("v1.10.0", "v{version}"));
        assertEquals(Optional.of(new Version(1, 2, 3)), Tags.version("sdk-1.2.3-final", "sdk-{version}-final"));
        assertEquals(Optional.empty(), Tags.version("1.2.3", "v{version}"));
        assertEquals(Optional.empty(), Tags.version("vv1.2.3", "v{version}"));
        assertEquals(Optional.empty(), Tags.version("demo-2026", "v{version}"));
    }
}
