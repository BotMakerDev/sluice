package com.botmaker.sluice.desktop.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** What JavaFX CSS cannot parse, kept out of the stylesheet. */
class StylesheetTest {

    /**
     * {@code inherit} is not a JavaFX CSS value: on a font property it reaches the {@code -fx-font} converter as a
     * bare string and throws on every layout of a combo-box popup.
     */
    @Test
    void noPropertyIsSetToInherit() throws IOException {
        String css;
        try (InputStream in = StylesheetTest.class.getResourceAsStream("/css/sluice.css")) {
            assertNotNull(in, "sluice.css is on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String code = css.replaceAll("(?s)/\\*.*?\\*/", "");
        assertFalse(Pattern.compile(":\\s*inherit\\s*;").matcher(code).find(), "a property set to inherit");
    }
}
