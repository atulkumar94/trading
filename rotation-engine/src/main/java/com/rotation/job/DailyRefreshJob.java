package com.rotation.job;

import java.nio.file.Path;

import com.rotation.config.RotationConfig;
import com.rotation.pipeline.BacktestPipeline;

/** Compatibility entry point for scheduled refreshes; work is shared with the main pipeline. */
public final class DailyRefreshJob {

    public void run(RotationConfig config, Path projectRoot) {
        new BacktestPipeline().run(config, true);
    }
}
