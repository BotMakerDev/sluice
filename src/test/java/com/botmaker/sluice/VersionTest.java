package com.botmaker.sluice;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void parsesWithAndWithoutV() {
        assertEquals(Optional.of(new Version(1, 2, 3)), Version.parse("v1.2.3"));
        assertEquals(Optional.of(new Version(1, 2, 3)), Version.parse(" 1.2.3 "));
        assertTrue(Version.parse("1.2").isEmpty());
        assertTrue(Version.parse("1.2.3-SNAPSHOT").isEmpty());
        assertTrue(Version.parse(null).isEmpty());
    }

    @Test
    void bumpZeroesWhatIsBelow() {
        Version v = new Version(1, 2, 3);
        assertEquals(new Version(1, 2, 4), v.bump(Level.PATCH));
        assertEquals(new Version(1, 3, 0), v.bump(Level.MINOR));
        assertEquals(new Version(2, 0, 0), v.bump(Level.MAJOR));
    }

    @Test
    void tagAndSnapshotSpelling() {
        Version v = new Version(0, 7, 0);
        assertEquals("v0.7.0", v.tag("v{version}"));
        assertEquals("release-0.7.0", v.tag("release-{version}"));
        assertEquals("0.7.1-SNAPSHOT", v.nextSnapshot());
    }

    @Test
    void ordersNumerically() {
        assertTrue(new Version(0, 10, 0).compareTo(new Version(0, 9, 9)) > 0);
    }
}
