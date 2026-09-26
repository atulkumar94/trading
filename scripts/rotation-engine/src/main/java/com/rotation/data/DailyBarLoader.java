package com.rotation.data;

import com.rotation.model.SymbolDailyCandles;

import java.nio.file.Path;
import java.util.List;

/** Loads raw market input from a directory and produces per-symbol daily candles. */
public interface DailyBarLoader {

    List<SymbolDailyCandles> load(Path dataDir);

    /**
     * Whether the produced series should be forward-filled when aligned into the
     * universe matrix (true for sparse minute history, false for tick input).
     */
    boolean forwardFill();
}
