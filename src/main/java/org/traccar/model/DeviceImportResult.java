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
package org.traccar.model;

import java.util.ArrayList;
import java.util.List;

public class DeviceImportResult {

    public record SkippedRow(int row, String uniqueId, String reason) {
    }

    private final List<Device> imported = new ArrayList<>();

    public List<Device> getImported() {
        return imported;
    }

    private final List<SkippedRow> skipped = new ArrayList<>();

    public List<SkippedRow> getSkipped() {
        return skipped;
    }

    public void addImported(Device device) {
        imported.add(device);
    }

    public void addSkipped(int row, String uniqueId, String reason) {
        skipped.add(new SkippedRow(row, uniqueId, reason));
    }

}
