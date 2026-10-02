package com.rotation.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectorSymbolsReaderTest {

    private static Path writeSectorFile() throws IOException {
        Path file = Files.createTempFile("sectors", ".csv");
        Files.writeString(file, String.join(System.lineSeparator(),
                "Symbol,Sector",
                "CIPLA,Healthcare",
                "TCS,Information Technology",
                "LUPIN,Healthcare",
                "EMPTY,"));
        return file;
    }

    @Test
    void returnsOnlySymbolsInSectorIgnoringCase() throws IOException {
        Path file = writeSectorFile();

        assertEquals(List.of("CIPLA", "LUPIN"), SectorSymbolsReader.readSymbolsInSector(file, "healthcare"));
    }

    @Test
    void failsWhenSectorHasNoSymbols() throws IOException {
        Path file = writeSectorFile();

        assertThrows(IllegalArgumentException.class,
                () -> SectorSymbolsReader.readSymbolsInSector(file, "Realty"));
    }
}
