package com.rotation.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the symbols belonging to one sector from a CSV with 'symbol' and 'sector' columns. */
public final class SectorSymbolsReader {

    private SectorSymbolsReader() {
    }

    /**
     * Full {@code symbol -> sector} map (reporting only). Returns an empty map when the
     * file is null or missing; rows with a blank symbol or sector are skipped.
     */
    public static Map<String, String> readSectorMap(Path sectorFile) {
        Map<String, String> map = new LinkedHashMap<>();
        if (sectorFile == null || !Files.exists(sectorFile)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(sectorFile)) {
            String header = reader.readLine();
            if (header == null) {
                return map;
            }
            String[] columns = header.split(",");
            int symbolCol = -1;
            int sectorCol = -1;
            for (int i = 0; i < columns.length; i++) {
                String col = columns[i].trim();
                if (col.equalsIgnoreCase("symbol")) {
                    symbolCol = i;
                } else if (col.equalsIgnoreCase("sector")) {
                    sectorCol = i;
                }
            }
            if (symbolCol < 0 || sectorCol < 0) {
                return map;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length > Math.max(symbolCol, sectorCol)) {
                    String symbol = parts[symbolCol].trim();
                    String sector = parts[sectorCol].trim();
                    if (!symbol.isEmpty() && !sector.isEmpty()) {
                        map.put(symbol, sector);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read sector file: " + sectorFile, e);
        }
        return map;
    }

    /** Sector names are matched case-insensitively; fails if the sector has no symbols. */
    public static List<String> readSymbolsInSector(Path sectorFile, String sector) {
        List<String> symbols = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(sectorFile)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("Sector file is empty: " + sectorFile);
            }
            String[] columns = header.split(",");
            int symbolCol = -1;
            int sectorCol = -1;
            for (int i = 0; i < columns.length; i++) {
                String col = columns[i].trim();
                if (col.equalsIgnoreCase("symbol")) {
                    symbolCol = i;
                } else if (col.equalsIgnoreCase("sector")) {
                    sectorCol = i;
                }
            }
            if (symbolCol < 0 || sectorCol < 0) {
                throw new IllegalArgumentException(
                        "Sector file must include 'symbol' and 'sector' columns: " + sectorFile);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length > Math.max(symbolCol, sectorCol)) {
                    String symbol = parts[symbolCol].trim();
                    if (!symbol.isEmpty() && parts[sectorCol].trim().equalsIgnoreCase(sector)) {
                        symbols.add(symbol);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read sector file: " + sectorFile, e);
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException(
                    "No symbols found for market.sector '" + sector + "' in: " + sectorFile);
        }
        return symbols;
    }
}
