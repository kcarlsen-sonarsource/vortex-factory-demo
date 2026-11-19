package org.traccar.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.helper.ReflectionCache;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Group;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskDeleteHistoryTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    /**
     * Storage keeping any number of positions per device and evaluating the conditions used by the task.
     */
    private static final class HistoryStorage extends Storage {

        private final Map<Class<?>, List<Object>> objects = new HashMap<>();
        private long nextId = 1;

        <T extends BaseModel> T put(T object) {
            if (object.getId() == 0) {
                object.setId(nextId++);
            }
            objects.computeIfAbsent(object.getClass(), k -> new ArrayList<>()).add(object);
            return object;
        }

        <T> List<T> list(Class<T> clazz) {
            return objects.getOrDefault(clazz, List.of()).stream().map(clazz::cast).toList();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Comparable value(Object object, String column) {
            try {
                Object value = ReflectionCache.getProperties(object.getClass(), "get")
                        .get(column).handle().invokeExact(object);
                return (Comparable) value;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private static boolean matches(Object object, Condition condition) {
            if (condition == null) {
                return true;
            } else if (condition instanceof Condition.And and) {
                return matches(object, and.getFirst()) && matches(object, and.getSecond());
            } else if (condition instanceof Condition.Compare compare) {
                Comparable value = value(object, compare.getColumn());
                if (value instanceof Date date) {
                    value = date.toInstant();
                }
                int result = value.compareTo(compare.getValue());
                return switch (compare.getOperator()) {
                    case "=" -> result == 0;
                    case "<>" -> result != 0;
                    case "<" -> result < 0;
                    default -> throw new UnsupportedOperationException(compare.getOperator());
                };
            }
            throw new UnsupportedOperationException(condition.getClass().getName());
        }

        @Override
        public <T> List<T> getObjects(Class<T> clazz, Request request) {
            return getObjectsStream(clazz, request).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) {
            Stream<T> stream = list(clazz).stream().filter(object -> matches(object, request.getCondition()));
            Order order = request.getOrder();
            if (order != null) {
                Comparator<T> comparator = Comparator.comparing(object -> value(object, order.getColumn()));
                stream = stream.sorted(order.getDescending() ? comparator.reversed() : comparator);
                if (order.getLimit() > 0) {
                    stream = stream.limit(order.getLimit());
                }
            }
            return stream;
        }

        @Override
        public <T> long addObject(T entity, Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void updateObject(T entity, Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int removeObject(Class<?> clazz, Request request) {
            List<Object> items = objects.getOrDefault(clazz, new ArrayList<>());
            int sizeBefore = items.size();
            items.removeIf(object -> matches(object, request.getCondition()));
            return sizeBefore - items.size();
        }

        @Override
        public List<Permission> getPermissions(
                Class<? extends BaseModel> ownerClass, long ownerId,
                Class<? extends BaseModel> propertyClass, long propertyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPermission(Permission permission) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePermission(Permission permission) {
            throw new UnsupportedOperationException();
        }

    }

    private HistoryStorage storage;
    private TaskDeleteHistory task;

    @BeforeEach
    void setUp() {
        storage = new HistoryStorage();
        task = new TaskDeleteHistory(storage);
    }

    private Group group(long parentId, Integer retentionDays) {
        Group group = new Group();
        group.setGroupId(parentId);
        group.setRetentionDays(retentionDays);
        return storage.put(group);
    }

    private Device device(long groupId, Integer retentionDays) {
        Device device = new Device();
        device.setGroupId(groupId);
        device.setRetentionDays(retentionDays);
        return storage.put(device);
    }

    private Position position(Device device, long ageMillis) {
        Position position = new Position();
        position.setDeviceId(device.getId());
        position.setFixTime(Date.from(NOW.minusMillis(ageMillis)));
        return storage.put(position);
    }

    private Event event(Device device, long ageMillis) {
        Event event = new Event();
        event.setDeviceId(device.getId());
        event.setEventTime(Date.from(NOW.minusMillis(ageMillis)));
        return storage.put(event);
    }

    private List<Position> positions(Device device) {
        return storage.list(Position.class).stream().filter(p -> p.getDeviceId() == device.getId()).toList();
    }

    private List<Event> events(Device device) {
        return storage.list(Event.class).stream().filter(e -> e.getDeviceId() == device.getId()).toList();
    }

    @Test
    void testCutoffBoundary() {
        Device device = device(0, 10);
        position(device, 10 * DAY + 1);
        Position exact = position(device, 10 * DAY);
        Position newer = position(device, DAY);
        device.setPositionId(newer.getId());
        event(device, 10 * DAY + 1);
        Event exactEvent = event(device, 10 * DAY);

        task.deleteHistory(NOW);

        assertEquals(List.of(exact, newer), positions(device));
        assertEquals(List.of(exactEvent), events(device));
    }

    @Test
    void testLatestPositionKept() {
        Device device = device(0, 30);
        position(device, 100 * DAY);
        Position latest = position(device, 60 * DAY);
        device.setPositionId(latest.getId());
        event(device, 60 * DAY);

        task.deleteHistory(NOW);

        assertEquals(List.of(latest), positions(device));
        assertEquals(List.of(), events(device));
    }

    @Test
    void testLatestPositionKeptWithoutPositionId() {
        Device device = device(0, 30);
        position(device, 100 * DAY);
        Position latest = position(device, 60 * DAY);

        task.deleteHistory(NOW);

        assertEquals(List.of(latest), positions(device));
    }

    @Test
    void testInheritanceAndOverride() {
        Group root = group(0, 90);
        Group middle = group(root.getId(), null);
        Group leaf = group(middle.getId(), null);

        Device inherited = device(leaf.getId(), null);
        Device overridden = device(leaf.getId(), 365);
        Device unlimited = device(0, null);
        for (Device device : List.of(inherited, overridden, unlimited)) {
            position(device, 400 * DAY);
            position(device, 200 * DAY);
            device.setPositionId(position(device, 0).getId());
        }

        task.deleteHistory(NOW);

        assertEquals(1, positions(inherited).size());
        assertEquals(2, positions(overridden).size());
        assertEquals(3, positions(unlimited).size());
    }

}
