package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.model.PomFile.Parent;
import com.botmaker.sluice.model.PomFile.Reference;

import java.util.Optional;
import java.util.regex.Matcher;

/** What a pin says: the version text it holds in a given text of its file. */
public final class PinReader {

    private PinReader() {
    }

    /**
     * The text {@code pin} holds in {@code fileText}, its file as it is now or as a tag has it; empty when that
     * text holds no such pin or cannot be read.
     */
    public static Optional<String> value(Pin pin, String fileText) {
        try {
            return switch (pin) {
                case Pin.Property property -> Optional.ofNullable(
                        PomReader.read(property.pom(), fileText).properties().get(property.name()));
                case Pin.Literal literal -> PomReader.read(literal.pom(), fileText).references().stream()
                        .filter(r -> r.coordinate().equals(literal.dep()))
                        .map(Reference::version)
                        .flatMap(Optional::stream)
                        .findFirst();
                case Pin.Parent parent -> PomReader.read(parent.pom(), fileText).parent().map(Parent::version);
                case Pin.Text text -> {
                    Matcher matcher = text.match().matcher(fileText);
                    yield matcher.find() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
                }
            };
        } catch (Refusal unreadable) {
            return Optional.empty();
        }
    }
}
