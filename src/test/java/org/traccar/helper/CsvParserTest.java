package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvParserTest {

    @Test
    void testSimpleRows() {
        assertEquals(
                List.of(List.of("a", "b", "c"), List.of("1", "2", "3")),
                CsvParser.parse("a,b,c\n1,2,3\n"));
    }

    @Test
    void testQuotedFields() {
        assertEquals(
                List.of(List.of("x, y", "say \"hi\"", "line\nbreak")),
                CsvParser.parse("\"x, y\",\"say \"\"hi\"\"\",\"line\nbreak\""));
    }

    @Test
    void testLineEndingsBlankLinesAndBom() {
        assertEquals(
                List.of(List.of("a", ""), List.of("", "b")),
                CsvParser.parse((char) 0xFEFF + "a,\r\n\r\n,b\r\n"));
    }

    @Test
    void testEmpty() {
        assertTrue(CsvParser.parse("").isEmpty());
    }

    @Test
    void testUnterminatedQuote() {
        assertThrows(IllegalArgumentException.class, () -> CsvParser.parse("\"abc"));
    }

}
