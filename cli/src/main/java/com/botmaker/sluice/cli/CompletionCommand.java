// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.cli;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code sluice completion}: the bash completion script on stdout, and nothing else, so the rpm and the deb generate
 * their file by running it ({@code packaging/linux/build-packages.sh}) instead of carrying a copy that would stop
 * offering a verb somebody added. picocli writes it from the annotations that parse; zsh runs it through
 * {@code bashcompinit}. A module's alias flag ({@code --sdk}) comes from {@code sluice.yml}, so it is not offered.
 */
@Command(name = "completion", mixinStandardHelpOptions = true,
        description = {"Print the bash completion script: verbs and options. The rpm and the deb install it "
                + "already. For a jar, or to try it in this shell only:",
            "",
            "  source <(sluice completion)",
            "",
            "zsh runs the same script through bashcompinit:",
            "",
            "  autoload -U +X compinit bashcompinit && compinit && bashcompinit",
            "  source <(sluice completion)"})
final class CompletionCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        CommandLine root = spec.root().commandLine();
        spec.commandLine().getOut().print(AutoComplete.bash(root.getCommandName(), root));
        return Main.OK;
    }
}
