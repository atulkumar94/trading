package com.rotation.data;

import com.rotation.model.DailyCandle;
import com.rotation.model.SymbolDailyCandles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinuteHistoryDailyBarLoaderTest {

    @Test
    void loadsDailyCsvFilesWithHeader() throws IOException {
        Path dir = Files.createTempDirectory("rotation-loader");
        Path csv = dir.resolve("TEST.csv");
        Files.writeString(csv, String.join(System.lineSeparator(),
                "Date,Adj Close,Close,High,Low,Open,Volume",
                "2020-01-01,205.79,207.85,210.45,206.65,209.0,1553127",
                "2020-01-02,209.11,211.20,213.20,207.50,208.0,2991937"
        ));

        MinuteHistoryDailyBarLoader loader = new MinuteHistoryDailyBarLoader();
        List<SymbolDailyCandles> result = loader.load(dir);

        assertEquals(1, result.size());
        assertEquals("TEST", result.get(0).symbol());
        assertEquals(2, result.get(0).candles().size());
        DailyCandle first = result.get(0).candles().get(0);
        assertEquals(java.time.LocalDate.of(2020, 1, 1), first.date());
        // The whole bar is adjusted by the Adj Close / Close ratio; close becomes Adj Close.
        double factor = 205.79 / 207.85;
        assertEquals(209.0 * factor, first.open(), 1e-9);
        assertEquals(210.45 * factor, first.high(), 1e-9);
        assertEquals(206.65 * factor, first.low(), 1e-9);
        assertEquals(205.79, first.close(), 1e-9);
    }
}
