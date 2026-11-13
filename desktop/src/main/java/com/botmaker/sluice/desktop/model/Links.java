package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.model.Module;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * A module's pages on GitHub, from the remote its checkout names: none when that remote is not on GitHub.
 *
 * @param repo the module's repository
 */
public record Links(Repo repo) {

    /** One page, for a button. */
    public record Link(String label, String url) {
    }

    public static Optional<Links> of(Module module) {
        return Repo.ofCheckout(module.dir().dir()).map(Links::new);
    }

    public String home() {
        return "https://github.com/" + repo.owner() + "/" + repo.name();
    }

    public String release(String tag) {
        return home() + "/releases/tag/" + tag;
    }

    public String tree(String tag) {
        return home() + "/tree/" + tag;
    }

    public String actions(String branch) {
        return home() + "/actions?query=" + URLEncoder.encode("branch:" + branch, StandardCharsets.UTF_8);
    }

    public String compare(String from, String branch) {
        return home() + "/compare/" + from + "..." + branch;
    }

    /** The pages the Modules tab offers. */
    public List<Link> forModule(String branch) {
        return List.of(new Link("Repo", home()), new Link("CI", actions(branch)),
                new Link("Releases", home() + "/releases"));
    }
}
