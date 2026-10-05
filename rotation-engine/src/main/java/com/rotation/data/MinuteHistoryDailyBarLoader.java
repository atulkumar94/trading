package com.rotation.data;

import java.nio.file.Path;
import java.util.List;

import com.rotation.model.SymbolDailyCandles;

/** @deprecated Use {@link DailyFileBarLoader}; retained as a source-compatible adapter. */
@Deprecated
public final class MinuteHistoryDailyBarLoader implements DailyBarLoader {

    private final DailyFileBarLoader delegate = new DailyFileBarLoader();

    @Override
    public boolean forwardFill() {
        return delegate.forwardFill();
    }

    @Override
    public List<SymbolDailyCandles> load(Path dataDir) {
        return delegate.load(dataDir);
    }
}
