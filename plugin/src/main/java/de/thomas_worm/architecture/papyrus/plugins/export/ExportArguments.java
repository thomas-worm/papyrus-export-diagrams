package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Path;

/**
 * Parsed command-line arguments for the headless export application.
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
