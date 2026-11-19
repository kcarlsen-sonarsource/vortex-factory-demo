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
package org.traccar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.RetentionResolver;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes positions and events older than the effective retention of each device. The latest position of a
 * device is always kept. Devices without retention in their group chain keep their full history.
 */
public class TaskDeleteHistory extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDeleteHistory.class);

    private static final long CHECK_PERIOD_HOURS = 24;

    private static final String DEVICE_ID = "deviceId";

    private final Storage storage;

    @Inject
    public TaskDeleteHistory(Storage storage) {
        this.storage = storage;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, CHECK_PERIOD_HOURS, CHECK_PERIOD_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void run() {
        deleteHistory(Instant.now());
    }

    void deleteHistory(Instant now) {
        RetentionResolver resolver;
        Iterable<Device> devices;
        try {
            resolver = RetentionResolver.load(storage);
            devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "groupId", "retentionDays", "positionId")));
        } catch (StorageException e) {
            LOGGER.warn("Failed to load devices for history retention", e);
            return;
        }

        for (Device device : devices) {
            Integer retentionDays = resolver.resolve(device);
            if (retentionDays != null) {
                Instant cutoff = now.minus(Duration.ofDays(retentionDays));
                try {
                    int removed = deleteDeviceHistory(device, cutoff);
                    if (removed > 0) {
                        LOGGER.info("History retention removed {} rows for device {}", removed, device.getId());
                    }
                } catch (StorageException e) {
                    LOGGER.warn("Failed to delete history for device {}", device.getId(), e);
                }
            }
        }
    }

    private int deleteDeviceHistory(Device device, Instant cutoff) throws StorageException {
        Condition positionCondition = new Condition.And(
                new Condition.Equals(DEVICE_ID, device.getId()),
                new Condition.Compare("fixTime", "<", cutoff));
        long latestPositionId = getLatestPositionId(device);
        if (latestPositionId > 0) {
            positionCondition = new Condition.And(
                    positionCondition, new Condition.Compare("id", "<>", latestPositionId));
        }
        int removed = storage.removeObject(Position.class, new Request(positionCondition));

        removed += storage.removeObject(Event.class, new Request(new Condition.And(
                new Condition.Equals(DEVICE_ID, device.getId()),
                new Condition.Compare("eventTime", "<", cutoff))));

        return removed;
    }

    private long getLatestPositionId(Device device) throws StorageException {
        if (device.getPositionId() > 0) {
            return device.getPositionId();
        }
        Position latest = storage.getObject(Position.class, new Request(
                new Columns.Include("id"),
                new Condition.Equals(DEVICE_ID, device.getId()),
                new Order("fixTime", true, 1)));
        return latest != null ? latest.getId() : 0;
    }

}
