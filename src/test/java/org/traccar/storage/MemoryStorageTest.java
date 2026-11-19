package org.traccar.storage;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryStorageTest {

    private static Event event(Instant time) {
        Event event = new Event();
        event.setEventTime(Date.from(time));
        return event;
    }

    @Test
    void testRemoveWithInstantCondition() {
        MemoryStorage storage = new MemoryStorage();
        Instant cutoff = Instant.ofEpochSecond(1_000_000);
        storage.addObject(event(cutoff.minusSeconds(1)), new Request(new Columns.Exclude("id")));
        storage.addObject(event(cutoff), new Request(new Columns.Exclude("id")));

        int removed = storage.removeObject(Event.class, new Request(
                new Condition.Compare("eventTime", "<", cutoff)));

        assertEquals(1, removed);
        assertEquals(1, storage.getObjects(Event.class, new Request(new Columns.All())).size());
    }

}
