package com.botmaker.sluice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcTest {

    @Test
    void windowsArgumentKeepsItsQuotes() {
        assertEquals("a b", Proc.windowsArg("a b"), "no quote inside: ProcessBuilder quotes it");
        assertEquals("\"'q' \\\"d\\\"\"", Proc.windowsArg("'q' \"d\""));
        assertEquals("\"a\\\\\\\"b\"", Proc.windowsArg("a\\\"b"), "a backslash before a quote is doubled");
        assertEquals("\"\\\"x\\\\\\\\\"", Proc.windowsArg("\"x\\\\"), "trailing backslashes are doubled");
        assertEquals("\"c:\\dir\\\"\"", Proc.windowsArg("c:\\dir\""), "a backslash before a letter stays one");
    }
}
