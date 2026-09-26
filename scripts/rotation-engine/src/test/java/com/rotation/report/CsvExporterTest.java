package com.rotation.report;

import com.rotation.model.BacktestResult;
import com.rotation.model.EquityRow;
import com.rotation.model.LookbackRow;
import com.rotation.model.TradebookRow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvExporterTest {

    @Test
    void exportsYearlyReport() throws IOException {
        Path dir = Files.createTempDirectory("rotation-export");

        BacktestResult result = new BacktestResult(
                List.of(),
                List.of(
                        new EquityRow(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 10), 1000.0, 1000.0, 50.0, 500.0, "AAA", LocalDate.of(2023, 12, 15), null, 0.0),
                        new EquityRow(LocalDate.of(2024, 2, 15), LocalDate.of(2024, 2, 10), 1100.0, 1100.0, 100.0, 500.0, "BBB", LocalDate.of(2023, 12, 15), LocalDate.of(2024, 1, 15), 0.0),
                        new EquityRow(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 10), 1200.0, 1200.0, 100.0, 500.0, "CCC", LocalDate.of(2024, 12, 15), LocalDate.of(2024, 2, 15), 0.0)
                ),
                List.of(),
            List.of(
                new TradebookRow(LocalDate.of(2024, 2, 15), "ENTRY", "BBB", 5.0, 110.0, 550.0,
                    110.0, null, null, 550.0, 0.0, LocalDate.of(2024, 2, 10), 2)
            ),
            List.of(
                new LookbackRow(LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 15),
                    LocalDate.of(2024, 12, 15), 1, "CCC", 100.0, 120.0,
                    20.0, 30, true)
            ),
                List.of(),
                1000.0
        );

        new CsvExporter().export(result, dir, "rotation");

        Path yearly = dir.resolve("rotation_yearly.csv");
        assertTrue(Files.exists(yearly));

        String content = Files.readString(yearly);
        assertTrue(content.contains("year,start_date,end_date,start_equity,end_equity,contributions,period_pnl,yearly_return_pct,cagr_pct,rebalance_count"));
        assertTrue(content.contains("2024,2024-01-15,2024-02-15,1000.00,1100.00,0.00,150.00,10.00,207.40,2"));
        assertTrue(content.contains("2025,2024-02-15,2025-01-15,1100.00,1200.00,0.00,100.00,9.09,19.96,1"));

        Path tradebook = dir.resolve("rotation_tradebook.csv");
        assertTrue(Files.exists(tradebook));
        assertTrue(Files.readString(tradebook).contains("2024-02-15,ENTRY,BBB,5.00,110.00,550.00,110.00,,,550.00,0.00,2024-02-10,2"));

        Path lookback = dir.resolve("rotation_lookback.csv");
        assertTrue(Files.exists(lookback));
        assertTrue(Files.readString(lookback).contains("2025-01-10,2025-01-15,2024-12-15,1,CCC,100.00,120.00,20.00,30,true"));
    }
}
