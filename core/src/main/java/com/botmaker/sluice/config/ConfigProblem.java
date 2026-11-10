package com.botmaker.sluice.config;

/**
 * One thing wrong with a {@code sluice.yml}, as {@link ConfigLoader#check} finds it.
 *
 * @param path    where it is, as a refusal names it ({@code modules.core.alias}, {@code gates[1].run}); empty for
 *                the file as a whole
 * @param message what is wrong, the whole sentence a refusal would say
 * @param line    the line it is on, from 1; 0 when the file has no line for it
 */
public record ConfigProblem(String path, String message, int line) {
}
