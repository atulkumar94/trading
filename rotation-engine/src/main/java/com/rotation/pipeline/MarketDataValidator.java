package com.rotation.pipeline;

import java.util.ArrayList;
import java.util.List;

import com.rotation.model.DailyBars;

/** Warns or fails on missing bars, large close jumps, and repeated valid OHLC runs. */
public final class MarketDataValidator {

    private static final double MAX_ABS_CLOSE_JUMP_PCT = 50.0;
    private static final int STALE_RUN_MIN_LENGTH = 3;

    private MarketDataValidator() {
    }

    public static List<String> inspect(DailyBars bars) {
        int gaps = 0;
        int jumps = 0;
        int staleRuns = 0;
        List<String> gapExamples = new ArrayList<>();
        List<String> jumpExamples = new ArrayList<>();
        List<String> staleExamples = new ArrayList<>();

        for (int symbolIndex = 0; symbolIndex < bars.symbolCount(); symbolIndex++) {
            String symbol = bars.symbols().get(symbolIndex);
            boolean seenValid = false;
            int identicalRun = 1;
            int runStart = -1;
            for (int dateIndex = 0; dateIndex < bars.dateCount(); dateIndex++) {
                boolean valid = bars.validBarAt(dateIndex, symbolIndex);
                if (!valid) {
                    if (seenValid) {
                        gaps++;
                        addExample(gapExamples, symbol + "@" + bars.dates().get(dateIndex));
                    }
                    if (identicalRun >= STALE_RUN_MIN_LENGTH && runStart >= 0) {
                        staleRuns++;
                        addExample(staleExamples, symbol + "@" + bars.dates().get(runStart));
                    }
                    identicalRun = 1;
                    runStart = -1;
                    continue;
                }

                double close = bars.closeAt(dateIndex, symbolIndex);
                if (dateIndex > 0 && bars.validBarAt(dateIndex - 1, symbolIndex)) {
                    double previousClose = bars.closeAt(dateIndex - 1, symbolIndex);
                    if (previousClose != 0.0 && Double.isFinite(close) && Double.isFinite(previousClose)) {
                        double jumpPct = Math.abs(close / previousClose - 1.0) * 100.0;
                        if (jumpPct > MAX_ABS_CLOSE_JUMP_PCT) {
                            jumps++;
                            addExample(jumpExamples, symbol + "@" + bars.dates().get(dateIndex)
                                    + " (" + String.format(java.util.Locale.US, "%.1f", jumpPct) + "%)");
                        }
                    }
                    if (sameBar(bars, dateIndex, symbolIndex)) {
                        if (identicalRun == 1) {
                            runStart = dateIndex - 1;
                        }
                        identicalRun++;
                    } else {
                        if (identicalRun >= STALE_RUN_MIN_LENGTH && runStart >= 0) {
                            staleRuns++;
                            addExample(staleExamples, symbol + "@" + bars.dates().get(runStart));
                        }
                        identicalRun = 1;
                        runStart = -1;
                    }
                } else {
                    identicalRun = 1;
                    runStart = -1;
                }
                seenValid = true;
            }
            if (identicalRun >= STALE_RUN_MIN_LENGTH && runStart >= 0) {
                staleRuns++;
                addExample(staleExamples, symbol + "@" + bars.dates().get(runStart));
            }
        }

        List<String> findings = new ArrayList<>();
        if (gaps > 0) {
            findings.add(gaps + " missing or invalid symbol-session bars after listing; examples: "
                    + String.join(", ", gapExamples));
        }
        if (jumps > 0) {
            findings.add(jumps + " valid close-to-close jumps exceed " + MAX_ABS_CLOSE_JUMP_PCT
                    + "%: " + String.join(", ", jumpExamples));
        }
        if (staleRuns > 0) {
            findings.add(staleRuns + " runs of at least " + STALE_RUN_MIN_LENGTH
                    + " identical valid OHLC bars: " + String.join(", ", staleExamples));
        }
        return List.copyOf(findings);
    }

    public static void validate(DailyBars bars, String mode) {
        List<String> findings = inspect(bars);
        if (findings.isEmpty()) {
            return;
        }
        if (mode.equals("fail")) {
            throw new IllegalArgumentException("Market data validation failed: " + String.join("; ", findings));
        }
        for (String finding : findings) {
            System.err.println("[data validation warning] " + finding);
        }
    }

    private static boolean sameBar(DailyBars bars, int dateIndex, int symbolIndex) {
        return bars.openAt(dateIndex, symbolIndex) == bars.openAt(dateIndex - 1, symbolIndex)
                && bars.highAt(dateIndex, symbolIndex) == bars.highAt(dateIndex - 1, symbolIndex)
                && bars.lowAt(dateIndex, symbolIndex) == bars.lowAt(dateIndex - 1, symbolIndex)
                && bars.closeAt(dateIndex, symbolIndex) == bars.closeAt(dateIndex - 1, symbolIndex);
    }

    private static void addExample(List<String> examples, String value) {
        if (examples.size() < 3) {
            examples.add(value);
        }
    }
}
