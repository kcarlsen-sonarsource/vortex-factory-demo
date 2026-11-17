/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.helper;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 parser: comma separated, double-quoted fields with escaped quotes,
 * CRLF or LF line endings. A leading byte order mark and blank lines are ignored.
 */
public final class CsvParser {

    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private final String content;
    private int position;

    private CsvParser(String content) {
        this.content = content;
        if (!content.isEmpty() && content.charAt(0) == BYTE_ORDER_MARK) {
            position = 1;
        }
    }

    public static List<List<String>> parse(String content) {
        return new CsvParser(content).parseRows();
    }

    private List<List<String>> parseRows() {
        List<List<String>> rows = new ArrayList<>();
        while (position < content.length()) {
            List<String> row = parseRow();
            if (row.size() > 1 || !row.get(0).isBlank()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<String> parseRow() {
        List<String> row = new ArrayList<>();
        while (true) {
            row.add(parseField());
            if (position >= content.length()) {
                return row;
            }
            char separator = content.charAt(position++);
            if (separator != ',') {
                if (separator == '\r' && peek('\n')) {
                    position++;
                }
                return row;
            }
        }
    }

    private String parseField() {
        StringBuilder field = new StringBuilder();
        if (peek('"')) {
            position++;
            while (true) {
                if (position >= content.length()) {
                    throw new IllegalArgumentException("Unterminated quoted field");
                }
                char c = content.charAt(position++);
                if (c != '"') {
                    field.append(c);
                } else if (peek('"')) {
                    field.append('"');
                    position++;
                } else {
                    break;
                }
            }
        }
        while (position < content.length() && !isSeparator(content.charAt(position))) {
            field.append(content.charAt(position++));
        }
        return field.toString();
    }

    private boolean peek(char expected) {
        return position < content.length() && content.charAt(position) == expected;
    }

    private static boolean isSeparator(char c) {
        return c == ',' || c == '\n' || c == '\r';
    }

}
