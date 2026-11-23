// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A release's {@code checksums_sha256.txt}: lines of {@code <sha256 hex>  <asset name>}, as {@code sha256sum} writes
 * them. Nothing is installed unchecked: a download whose sha256 differs, or that the file lists nothing for, is
 * deleted and refused with why.
 */
public final class Checksums {

    /** The release asset that lists every other asset's sha256. */
    public static final String FILE = "checksums_sha256.txt";

    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private Checksums() {
    }

    /** The sha256 {@code checksums} lists for {@code asset}, lowercase, or empty when it lists none. */
    public static Optional<String> expected(String checksums, String asset) {
        return checksums.lines()
                .map(String::strip)
                .map(line -> line.split("\\s+\\*?", 2))
                .filter(parts -> parts.length == 2 && parts[1].strip().equals(asset) && HEX.matcher(parts[0]).matches())
                .map(parts -> parts[0].toLowerCase(Locale.ROOT))
                .findFirst();
    }

    /** Passes when {@code file}'s sha256 is the one {@code checksums} lists for {@code asset}; else deletes it, throws. */
    public static void verify(Path file, String asset, String checksums) throws IOException {
        Optional<String> want = expected(checksums, asset);
        String refusal;
        if (want.isEmpty()) {
            refusal = "the release's " + FILE + " lists no checksum for " + asset;
        } else if (!want.get().equals(sha256(file))) {
            refusal = asset + " does not match the release's checksum: the download is corrupt";
        } else {
            return;
        }
        Files.deleteIfExists(file);
        throw new IOException(refusal + "; it was deleted and nothing was installed");
    }

    /** {@code file}'s sha256, lowercase hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JDK has SHA-256
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
