package com.rotation.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Aligned daily open/close matrices across the whole universe.
 *
 * <p>Rows are trading dates (union of all symbols, ascending); columns are
 * symbols (alphabetical). Missing observations are {@link Double#NaN}. The
 * {@code eligibility} matrix holds the cumulative count of non-null closes for
 * each symbol up to and including each date, which drives the minimum-history
 * gate in the engine.
 *
 * <p>For every configured period the bars also carry a simple moving average of the
 * adjusted close (DMA). A DMA is defined on a session only when the symbol has
 * {@code period} consecutive non-NaN closes ending on it, so a gap restarts the window.
 */
public final class DailyBars {

    /** Default DMA periods stored alongside the prices. */
    public static final List<Integer> DEFAULT_DMA_PERIODS = List.of(10, 20, 50, 100, 200);

    private final List<LocalDate> dates;
    private final List<String> symbols;
    private final Map<LocalDate, Integer> dateIndex;
    private final Map<String, Integer> symbolIndex;
    private final double[][] opens;   // [dateIdx][symbolIdx]
    private final double[][] highs;   // [dateIdx][symbolIdx]
    private final double[][] lows;    // [dateIdx][symbolIdx]
    private final double[][] closes;  // [dateIdx][symbolIdx]
    private final double[][] volumes; // [dateIdx][symbolIdx]
    private final double[][] rawCloses; // [dateIdx][symbolIdx]
    private final double[][] adjustmentFactors; // [dateIdx][symbolIdx]
    private final boolean[][] sourceBars; // source row exists, even when its prices are invalid
    private final boolean[][] validBars; // [dateIdx][symbolIdx], false for absent/invalid source bars
    private final int[][] eligibility; // [dateIdx][symbolIdx]
    private final List<Integer> dmaPeriods;
    private final double[][][] dmas; // [periodIdx][dateIdx][symbolIdx], adjusted-close SMA

    private DailyBars(List<LocalDate> dates, List<String> symbols, Map<String, Integer> symbolIndex,
                      double[][] opens, double[][] highs, double[][] lows, double[][] closes,
                      double[][] volumes, double[][] rawCloses, double[][] adjustmentFactors,
                      boolean[][] sourceBars, boolean[][] validBars, int[][] eligibility,
                      List<Integer> dmaPeriods, double[][][] dmas) {
        this.dates = List.copyOf(dates);
        this.symbols = List.copyOf(symbols);
        Map<LocalDate, Integer> dateIndex = new HashMap<>();
        for (int i = 0; i < this.dates.size(); i++) {
            dateIndex.put(this.dates.get(i), i);
        }
        this.dateIndex = Map.copyOf(dateIndex);
        this.symbolIndex = Map.copyOf(symbolIndex);
        this.opens = opens;
        this.highs = highs;
        this.lows = lows;
        this.closes = closes;
        this.volumes = volumes;
        this.rawCloses = rawCloses;
        this.adjustmentFactors = adjustmentFactors;
        this.sourceBars = sourceBars;
        this.validBars = validBars;
        this.eligibility = eligibility;
        this.dmaPeriods = List.copyOf(dmaPeriods);
        this.dmas = dmas;
    }

    /**
     * Build aligned matrices from per-symbol daily candles.
     *
     * @param seriesList  candles per symbol
     * @param forwardFill when true, forward-fill gaps down each column (used for
     *                    minute-history input where a symbol may skip a session);
     *                    tick input is left with genuine NaN gaps.
     */
    public static DailyBars build(List<SymbolDailyCandles> seriesList, boolean forwardFill) {
        return build(seriesList, forwardFill, DEFAULT_DMA_PERIODS);
    }

    /** As {@link #build(List, boolean)} with explicit DMA periods (positive, distinct; may be empty). */
    public static DailyBars build(List<SymbolDailyCandles> seriesList, boolean forwardFill,
                                  List<Integer> dmaPeriods) {
        dmaPeriods = validatedPeriods(dmaPeriods);
        TreeSet<LocalDate> dateSet = new TreeSet<>();
        TreeSet<String> symbolSet = new TreeSet<>();
        for (SymbolDailyCandles series : seriesList) {
            if (series.candles().isEmpty()) {
                continue;
            }
            symbolSet.add(series.symbol());
            for (DailyCandle candle : series.candles()) {
                dateSet.add(candle.date());
            }
        }
        if (dateSet.isEmpty() || symbolSet.isEmpty()) {
            throw new IllegalArgumentException("No usable daily bars were produced from the input data.");
        }

        List<LocalDate> dates = new ArrayList<>(dateSet);
        List<String> symbols = new ArrayList<>(symbolSet);
        Map<LocalDate, Integer> dateIndex = new HashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            dateIndex.put(dates.get(i), i);
        }
        Map<String, Integer> symbolIndex = new HashMap<>();
        for (int j = 0; j < symbols.size(); j++) {
            symbolIndex.put(symbols.get(j), j);
        }

        int rows = dates.size();
        int cols = symbols.size();
        double[][] opens = new double[rows][cols];
        double[][] highs = new double[rows][cols];
        double[][] lows = new double[rows][cols];
        double[][] closes = new double[rows][cols];
        double[][] volumes = new double[rows][cols];
        double[][] rawCloses = new double[rows][cols];
        double[][] adjustmentFactors = new double[rows][cols];
        boolean[][] sourceBars = new boolean[rows][cols];
        boolean[][] validBars = new boolean[rows][cols];
        for (double[] row : opens) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (double[] row : highs) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (double[] row : lows) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (double[] row : closes) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (double[] row : volumes) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (double[] row : rawCloses) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (double[] row : adjustmentFactors) {
            java.util.Arrays.fill(row, Double.NaN);
        }

        for (SymbolDailyCandles series : seriesList) {
            Integer col = symbolIndex.get(series.symbol());
            if (col == null) {
                continue;
            }
            for (DailyCandle candle : series.candles()) {
                Integer row = dateIndex.get(candle.date());
                if (row == null) {
                    continue;
                }
                opens[row][col] = candle.open();
                highs[row][col] = candle.high();
                lows[row][col] = candle.low();
                closes[row][col] = candle.close();
                volumes[row][col] = candle.volume();
                rawCloses[row][col] = candle.rawClose();
                adjustmentFactors[row][col] = candle.adjustmentFactor();
                sourceBars[row][col] = true;
                validBars[row][col] = candle.validBar();
            }
        }

        if (forwardFill) {
            forwardFill(opens);
            forwardFill(highs);
            forwardFill(lows);
            forwardFill(closes);
        }

        int[][] eligibility = new int[rows][cols];
        for (int j = 0; j < cols; j++) {
            int running = 0;
            for (int i = 0; i < rows; i++) {
                if (!Double.isNaN(closes[i][j])) {
                    running++;
                }
                eligibility[i][j] = running;
            }
        }

        double[][][] dmas = new double[dmaPeriods.size()][][];
        for (int p = 0; p < dmas.length; p++) {
            dmas[p] = movingAverage(closes, dmaPeriods.get(p));
        }

        return new DailyBars(dates, symbols, symbolIndex, opens, highs, lows, closes,
            volumes, rawCloses, adjustmentFactors, sourceBars, validBars, eligibility, dmaPeriods, dmas);
    }

    private static List<Integer> validatedPeriods(List<Integer> periods) {
        if (periods == null) {
            throw new IllegalArgumentException("DMA periods must not be null.");
        }
        List<Integer> copy = List.copyOf(periods);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) <= 0) {
                throw new IllegalArgumentException("DMA periods must be positive: " + copy);
            }
            if (copy.indexOf(copy.get(i)) != i) {
                throw new IllegalArgumentException("DMA periods must be distinct: " + copy);
            }
        }
        return copy;
    }

    /** Trailing simple average per column; NaN until {@code period} consecutive values exist. */
    private static double[][] movingAverage(double[][] values, int period) {
        int rows = values.length;
        int cols = rows == 0 ? 0 : values[0].length;
        double[][] result = new double[rows][cols];
        for (double[] row : result) {
            Arrays.fill(row, Double.NaN);
        }
        for (int j = 0; j < cols; j++) {
            double sum = 0.0;
            int run = 0;
            for (int i = 0; i < rows; i++) {
                double value = values[i][j];
                if (Double.isNaN(value)) {
                    sum = 0.0;
                    run = 0;
                    continue;
                }
                sum += value;
                run++;
                if (run > period) {
                    sum -= values[i - period][j];
                }
                if (run >= period) {
                    result[i][j] = sum / period;
                }
            }
        }
        return result;
    }

    private static void forwardFill(double[][] matrix) {
        int rows = matrix.length;
        int cols = rows == 0 ? 0 : matrix[0].length;
        for (int j = 0; j < cols; j++) {
            double last = Double.NaN;
            for (int i = 0; i < rows; i++) {
                if (Double.isNaN(matrix[i][j])) {
                    if (!Double.isNaN(last)) {
                        matrix[i][j] = last;
                    }
                } else {
                    last = matrix[i][j];
                }
            }
        }
    }

    /** Return a copy filtered to the given symbols (universe restriction). */
    public DailyBars restrictTo(List<String> requestedSymbols) {
        List<SymbolDailyCandles> rebuilt = new ArrayList<>();
        for (String symbol : requestedSymbols) {
            Integer col = symbolIndex.get(symbol);
            if (col == null) {
                continue;
            }
            List<DailyCandle> candles = new ArrayList<>();
            for (int i = 0; i < dates.size(); i++) {
                double open = opens[i][col];
                double close = closes[i][col];
                if (Double.isNaN(open) && Double.isNaN(close)
                        && !sourceBars[i][col]) {
                    continue;
                }
                candles.add(new DailyCandle(dates.get(i), open, highs[i][col], lows[i][col], close,
                    volumes[i][col], rawCloses[i][col], adjustmentFactors[i][col], validBars[i][col]));
            }
            rebuilt.add(new SymbolDailyCandles(symbol, candles));
        }
        if (rebuilt.isEmpty()) {
            throw new IllegalArgumentException("None of the requested symbols are present in the data source.");
        }
        return build(rebuilt, false, dmaPeriods);
    }

    public DailyBars filterFrom(LocalDate startDate) {
        if (startDate == null) {
            return this;
        }
        List<LocalDate> filteredDates = new ArrayList<>();
        for (LocalDate date : dates) {
            if (!date.isBefore(startDate)) {
                filteredDates.add(date);
            }
        }
        if (filteredDates.isEmpty()) {
            throw new IllegalArgumentException("No data available on/after start date: " + startDate);
        }

        int startIdx = dates.indexOf(filteredDates.get(0));
        int rows = filteredDates.size();
        int cols = symbols.size();
        double[][] opens = new double[rows][cols];
        double[][] highs = new double[rows][cols];
        double[][] lows = new double[rows][cols];
        double[][] closes = new double[rows][cols];
        double[][] volumes = new double[rows][cols];
        double[][] rawCloses = new double[rows][cols];
        double[][] adjustmentFactors = new double[rows][cols];
        boolean[][] sourceBars = new boolean[rows][cols];
        boolean[][] validBars = new boolean[rows][cols];
        int[][] eligibility = new int[rows][cols];
        double[][][] dmas = new double[dmaPeriods.size()][rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                int sourceIdx = startIdx + i;
                for (int p = 0; p < dmas.length; p++) {
                    dmas[p][i][j] = this.dmas[p][sourceIdx][j];
                }
                opens[i][j] = this.opens[sourceIdx][j];
                highs[i][j] = this.highs[sourceIdx][j];
                lows[i][j] = this.lows[sourceIdx][j];
                closes[i][j] = this.closes[sourceIdx][j];
                volumes[i][j] = this.volumes[sourceIdx][j];
                rawCloses[i][j] = this.rawCloses[sourceIdx][j];
                adjustmentFactors[i][j] = this.adjustmentFactors[sourceIdx][j];
                sourceBars[i][j] = this.sourceBars[sourceIdx][j];
                validBars[i][j] = this.validBars[sourceIdx][j];
                eligibility[i][j] = this.eligibility[sourceIdx][j];
            }
        }

        return new DailyBars(filteredDates, symbols, symbolIndex,
            opens, highs, lows, closes, volumes, rawCloses, adjustmentFactors, sourceBars, validBars, eligibility,
            dmaPeriods, dmas);
    }

    public DailyBars filterTo(LocalDate endDate) {
        if (endDate == null) {
            return this;
        }
        List<LocalDate> filteredDates = new ArrayList<>();
        for (LocalDate date : dates) {
            if (!date.isAfter(endDate)) {
                filteredDates.add(date);
            }
        }
        if (filteredDates.isEmpty()) {
            throw new IllegalArgumentException("No data available on/before end date: " + endDate);
        }

        int rows = filteredDates.size();
        int cols = symbols.size();
        double[][] opens = new double[rows][cols];
        double[][] highs = new double[rows][cols];
        double[][] lows = new double[rows][cols];
        double[][] closes = new double[rows][cols];
        double[][] volumes = new double[rows][cols];
        double[][] rawCloses = new double[rows][cols];
        double[][] adjustmentFactors = new double[rows][cols];
        boolean[][] sourceBars = new boolean[rows][cols];
        boolean[][] validBars = new boolean[rows][cols];
        int[][] eligibility = new int[rows][cols];
        double[][][] dmas = new double[dmaPeriods.size()][rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                for (int p = 0; p < dmas.length; p++) {
                    dmas[p][i][j] = this.dmas[p][i][j];
                }
                opens[i][j] = this.opens[i][j];
                highs[i][j] = this.highs[i][j];
                lows[i][j] = this.lows[i][j];
                closes[i][j] = this.closes[i][j];
                volumes[i][j] = this.volumes[i][j];
                rawCloses[i][j] = this.rawCloses[i][j];
                adjustmentFactors[i][j] = this.adjustmentFactors[i][j];
                sourceBars[i][j] = this.sourceBars[i][j];
                validBars[i][j] = this.validBars[i][j];
                eligibility[i][j] = this.eligibility[i][j];
            }
        }

        return new DailyBars(filteredDates, symbols, symbolIndex,
            opens, highs, lows, closes, volumes, rawCloses, adjustmentFactors, sourceBars, validBars, eligibility,
            dmaPeriods, dmas);
    }

    /** Ascending trading dates; unmodifiable. */
    public List<LocalDate> dates() {
        return dates;
    }

    /** Alphabetical symbols; unmodifiable. */
    public List<String> symbols() {
        return symbols;
    }

    /** Row index for a trading date, or -1 when the date is not a session. */
    public int indexOfDate(LocalDate date) {
        Integer idx = dateIndex.get(date);
        return idx == null ? -1 : idx;
    }

    /** Column index for a symbol, or -1 when the symbol is not in the universe. */
    public int indexOfSymbol(String symbol) {
        Integer idx = symbolIndex.get(symbol);
        return idx == null ? -1 : idx;
    }

    public int symbolCount() {
        return symbols.size();
    }

    public int dateCount() {
        return dates.size();
    }

    public double openAt(int dateIdx, int symbolIdx) {
        return opens[dateIdx][symbolIdx];
    }

    public double highAt(int dateIdx, int symbolIdx) {
        return highs[dateIdx][symbolIdx];
    }

    public double lowAt(int dateIdx, int symbolIdx) {
        return lows[dateIdx][symbolIdx];
    }

    public double closeAt(int dateIdx, int symbolIdx) {
        return closes[dateIdx][symbolIdx];
    }

    public double volumeAt(int dateIdx, int symbolIdx) {
        return volumes[dateIdx][symbolIdx];
    }

    public double rawCloseAt(int dateIdx, int symbolIdx) {
        return rawCloses[dateIdx][symbolIdx];
    }

    public double adjustmentFactorAt(int dateIdx, int symbolIdx) {
        return adjustmentFactors[dateIdx][symbolIdx];
    }

    public boolean validBarAt(int dateIdx, int symbolIdx) {
        return validBars[dateIdx][symbolIdx];
    }

    public boolean hasSourceBarAt(int dateIdx, int symbolIdx) {
        return sourceBars[dateIdx][symbolIdx];
    }

    public int eligibilityAt(int dateIdx, int symbolIdx) {
        return eligibility[dateIdx][symbolIdx];
    }

    /** Configured DMA periods in configuration order; unmodifiable. */
    public List<Integer> dmaPeriods() {
        return dmaPeriods;
    }

    public boolean hasDma(int period) {
        return dmaPeriods.contains(period);
    }

    /** Adjusted-close simple moving average ending on the session; NaN while the window is incomplete. */
    public double dmaAt(int period, int dateIdx, int symbolIdx) {
        int periodIdx = dmaPeriods.indexOf(period);
        if (periodIdx < 0) {
            throw new IllegalArgumentException("DMA period " + period + " is not configured: " + dmaPeriods);
        }
        return dmas[periodIdx][dateIdx][symbolIdx];
    }
}
