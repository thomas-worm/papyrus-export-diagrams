/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Path;

/**
 * Parsed command-line arguments for the headless export application.
 *
 * <p>Constructed by {@link #parse(String[])} from the raw String array
 * that {@code IApplicationContext} hands to
 * {@link ExportApplication#start} and never mutated thereafter.
 *
 * @param modelDirectory directory scanned recursively for {@code .di}
 *        and {@code .aird} files
 * @param outputDirectory directory written into; the export creates
 *        it if missing
 * @param format image format every diagram is rendered as
 * @param filenameStrategy how to derive each output filename stem
 */
record ExportArguments(
        Path modelDirectory,
        Path outputDirectory,
        ExportFormat format,
        FilenameStrategy filenameStrategy) {

    /**
     * Parses the arguments passed by {@code IApplicationContext}.
     *
     * <p>Tokens before a literal {@code --} separator are treated as
     * Eclipse launcher arguments and skipped. If the array contains no
     * {@code --} the parser falls back to interpreting it directly,
     * provided the first element looks like an application argument
     * (starts with {@code --}).
     *
     * @param rawArguments the array returned by
     *        {@code applicationContext.getArguments()}
     * @return the parsed arguments
     * @throws IllegalArgumentException if either {@code --modelDir} or
     *         {@code --outDir} is missing
     */
    static ExportArguments parse(String[] rawArguments) {
        String[] arguments = afterDoubleDash(rawArguments);
        Path modelDirectory = null;
        Path outputDirectory = null;
        ExportFormat format = ExportFormat.SVG;
        FilenameStrategy strategy = FilenameStrategy.XMI_ID;

        for (int index = 0; index < arguments.length; index++) {
            switch (arguments[index]) {
                case "--modelDir" -> modelDirectory = Path.of(arguments[++index]);
                case "--outDir"   -> outputDirectory = Path.of(arguments[++index]);
                case "--format"   -> format = ExportFormat.parse(arguments[++index]);
                case "--naming"   -> strategy = FilenameStrategy.parse(arguments[++index]);
                default -> { }
            }
        }

        if (modelDirectory == null || outputDirectory == null) {
            throw new IllegalArgumentException(
                    "Missing required arguments. Usage: "
                            + "--modelDir <dir> --outDir <dir> "
                            + "[--format SVG|PNG|JPEG|BMP|GIF|PDF] "
                            + "[--naming xmiId|name]");
        }

        return new ExportArguments(modelDirectory, outputDirectory, format, strategy);
    }

    /**
     * Returns the slice of {@code arguments} that comes after the
     * literal {@code --} separator. If no {@code --} is present the
     * full array is returned when its first element starts with
     * {@code --} (the launcher already trimmed for us), otherwise an
     * empty array.
     *
     * @param arguments raw argument array, possibly {@code null}
     * @return the application-facing slice, never {@code null}
     */
    private static String[] afterDoubleDash(String[] arguments) {
        if (arguments == null) return new String[0];
        for (int index = 0; index < arguments.length; index++) {
            if ("--".equals(arguments[index])) {
                String[] applicationArguments = new String[arguments.length - index - 1];
                System.arraycopy(arguments, index + 1, applicationArguments, 0, applicationArguments.length);
                return applicationArguments;
            }
        }
        if (arguments.length > 0 && arguments[0].startsWith("--")) {
            return arguments;
        }
        return new String[0];
    }
}
