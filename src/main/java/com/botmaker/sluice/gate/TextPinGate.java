package com.botmaker.sluice.gate;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Tags;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A text pin a release would carry names a release of its upstream. The release moves the text only when the
 * upstream releases too; otherwise the tag carries the text as it is, and a text naming no tag ships a module
 * that asks for a version nobody can get.
 */
public final class TextPinGate implements Gate {

    @Override
    public String name() {
        return "text-pins";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        Set<Module> releasing = ctx.plan().releasing().keySet();
        String pattern = ctx.config().tagPattern();
        List<GateVerdict> verdicts = new ArrayList<>();
        int checked = 0;
        for (Edge edge : ctx.graph().edges()) {
            if (!releasing.contains(edge.downstream()) || releasing.contains(edge.upstream())) {
                continue;
            }
            for (Pin pin : edge.pins()) {
                if (!(pin instanceof Pin.Text text)) {
                    continue;
                }
                checked++;
                String where = ctx.said(text.file());
                Optional<String> value = value(text);
                if (value.isEmpty()) {
                    verdicts.add(refused(where + ": nothing matches " + text.match().pattern(),
                            "     The text pin of " + edge.upstream() + " in sluice.yml no longer finds its version."));
                    continue;
                }
                Optional<Version> version = text.spelling() == Spelling.TAG
                        ? Tags.version(value.get(), pattern) : Version.parse(value.get());
                if (version.filter(v -> Tags.resolves(edge.upstream(), v, pattern)).isEmpty()) {
                    verdicts.add(refused(where + " names " + edge.upstream() + " " + value.get()
                                    + ", which is no release of it",
                            "     Release " + edge.upstream() + " with " + edge.downstream()
                                    + " (the release moves the text), or write a released version there."));
                }
            }
        }
        if (verdicts.isEmpty() && checked > 0) {
            verdicts.add(ok("every text pin released names a release (" + checked + ")"));
        }
        return verdicts;
    }

    private static Optional<String> value(Pin.Text pin) {
        try {
            return PinReader.value(pin, Files.readString(pin.file()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
