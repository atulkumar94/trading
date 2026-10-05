package com.rotation;

import java.nio.file.Files;
import java.nio.file.Path;

import com.rotation.pipeline.BacktestPipeline;

/** CLI parser; all config loading and run orchestration are delegated to the pipeline. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path configFile = projectRoot.resolve("config/rotation.properties");
        boolean dailyRefresh = false;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.equalsIgnoreCase("--daily-refresh")
                    || argument.equalsIgnoreCase("daily-refresh")) {
                dailyRefresh = true;
            } else if (argument.equalsIgnoreCase("--config") || argument.equalsIgnoreCase("-c")) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + argument
                            + ". Expected a config file path.");
                }
                configFile = resolvePath(projectRoot, args[++index]);
            } else if (argument.startsWith("--config=")) {
                configFile = resolvePath(projectRoot, argument.substring("--config=".length()));
            } else if (!argument.startsWith("--")) {
                configFile = resolvePath(projectRoot, argument);
            }
        }

        if (!Files.exists(configFile)) {
            System.err.println("Config file not found: " + configFile);
            System.exit(2);
        }
        new BacktestPipeline().run(configFile, projectRoot, dailyRefresh);
    }

    private static Path resolvePath(Path projectRoot, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate);
    }
}
