package org.traccar.api.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.helper.LogAction;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.DeviceImportResult;
import org.traccar.model.Group;
import org.traccar.model.Permission;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.SecurityContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceResourceImportTest {

    private static final long USER_ID = 1;

    private static final String HEADER = "name,uniqueId,phone,model,category,group\n";

    /**
     * In-memory storage holding objects of a single user. Supports the conditions used by the import flow.
     */
    private static final class MemoryStorage extends Storage {

        private final Map<Class<?>, List<Object>> objects = new HashMap<>();
        private final Map<Class<?>, Set<Long>> linked = new HashMap<>();
        private long nextId = 100;

        void put(Object object, boolean link) {
            objects.computeIfAbsent(object.getClass(), k -> new ArrayList<>()).add(object);
            if (link) {
                linked.computeIfAbsent(object.getClass(), k -> new HashSet<>()).add(((BaseModel) object).getId());
            }
        }

        private boolean matches(Object object, Condition condition) {
            if (condition == null) {
                return true;
            } else if (condition instanceof Condition.And and) {
                return matches(object, and.getFirst()) && matches(object, and.getSecond());
            } else if (condition instanceof Condition.Equals equals) {
                Object value = switch (equals.getColumn()) {
                    case "id" -> ((BaseModel) object).getId();
                    case "uniqueId" -> ((Device) object).getUniqueId();
                    default -> throw new UnsupportedOperationException(equals.getColumn());
                };
                return equals.getValue().equals(value);
            } else if (condition instanceof Condition.Permission permission) {
                return linked.getOrDefault(permission.getPropertyClass(), Set.of())
                        .contains(((BaseModel) object).getId());
            }
            throw new UnsupportedOperationException(condition.getClass().getName());
        }

        @Override
        public <T> List<T> getObjects(Class<T> clazz, Request request) {
            return getObjectsStream(clazz, request).toList();
        }

        @Override
        public <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) {
            return objects.getOrDefault(clazz, List.of()).stream()
                    .filter(object -> matches(object, request.getCondition()))
                    .map(clazz::cast);
        }

        @Override
        public <T> long addObject(T entity, Request request) {
            long id = nextId++;
            ((BaseModel) entity).setId(id);
            put(entity, false);
            return id;
        }

        @Override
        public <T> void updateObject(T entity, Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeObject(Class<?> clazz, Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Permission> getPermissions(
                Class<? extends BaseModel> ownerClass, long ownerId,
                Class<? extends BaseModel> propertyClass, long propertyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPermission(Permission permission) {
            linked.computeIfAbsent(permission.getPropertyClass(), k -> new HashSet<>()).add(permission.getPropertyId());
        }

        @Override
        public void removePermission(Permission permission) {
            throw new UnsupportedOperationException();
        }

    }

    private MemoryStorage storage;
    private User user;
    private LogAction actionLogger;
    private DeviceResource resource;

    private static void inject(Object target, Class<?> type, Object value) throws IllegalAccessException {
        for (Class<?> clazz = target.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType().equals(type)) {
                    field.setAccessible(true);
                    field.set(target, value);
                }
            }
        }
    }

    private static Group group(long id, String name) {
        Group group = new Group();
        group.setId(id);
        group.setName(name);
        return group;
    }

    private static Device device(long id, String uniqueId) {
        Device device = new Device();
        device.setId(id);
        device.setName("existing");
        device.setUniqueId(uniqueId);
        return device;
    }

    @BeforeEach
    void setUp() throws Exception {
        storage = new MemoryStorage();

        user = new User();
        user.setId(USER_ID);
        user.setDeviceLimit(-1);
        storage.put(user, false);
        storage.put(new Server(), false);
        storage.put(group(10, "Trucks"), true);
        storage.put(group(11, "Hidden"), false);
        storage.put(device(1, "existing"), true);

        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(new UserPrincipal(USER_ID, null));
        actionLogger = mock(LogAction.class);

        resource = new DeviceResource();
        inject(resource, SecurityContext.class, securityContext);
        inject(resource, Storage.class, storage);
        inject(resource, PermissionsService.class, new PermissionsService(storage));
        inject(resource, LogAction.class, actionLogger);
        inject(resource, CacheManager.class, mock(CacheManager.class));
        inject(resource, ConnectionManager.class, mock(ConnectionManager.class));
        inject(resource, HttpServletRequest.class, mock(HttpServletRequest.class));
    }

    @Test
    void testImportAndSkip() throws Exception {
        DeviceImportResult result = resource.importCsv(HEADER
                + "Truck 1,111,+100,M1,truck,Trucks\n"
                + "\"Car, blue\",222,,,car,\n"
                + "Dup existing,existing,,,,\n"
                + "Dup in file,111,,,,\n"
                + "Unknown,333,,,,Boats\n"
                + "No access,444,,,,Hidden\n"
                + ",555,,,,\n"
                + "No id,,,,,\n");

        assertEquals(2, result.getImported().size());
        Device truck = result.getImported().get(0);
        assertEquals("Truck 1", truck.getName());
        assertEquals("111", truck.getUniqueId());
        assertEquals("+100", truck.getPhone());
        assertEquals("M1", truck.getModel());
        assertEquals("truck", truck.getCategory());
        assertEquals(10, truck.getGroupId());
        Device car = result.getImported().get(1);
        assertEquals("Car, blue", car.getName());
        assertEquals(0, car.getGroupId());

        assertEquals(List.of(
                new DeviceImportResult.SkippedRow(4, "existing", "Duplicate unique ID"),
                new DeviceImportResult.SkippedRow(5, "111", "Duplicate unique ID"),
                new DeviceImportResult.SkippedRow(6, "333", "Unknown group"),
                new DeviceImportResult.SkippedRow(7, "444", "Unknown group"),
                new DeviceImportResult.SkippedRow(8, "555", "Missing name"),
                new DeviceImportResult.SkippedRow(9, null, "Missing unique ID")), result.getSkipped());

        for (Device device : result.getImported()) {
            verify(actionLogger).create(any(), eq(USER_ID), eq(device));
            verify(actionLogger).link(any(), eq(USER_ID), eq(User.class), eq(USER_ID),
                    eq(Device.class), eq(device.getId()));
        }
        assertEquals(3, storage.getObjects(Device.class, new Request(
                new Condition.Permission(User.class, USER_ID, Device.class))).size());
    }

    @Test
    void testDeviceLimit() throws Exception {
        user.setDeviceLimit(2);

        DeviceImportResult result = resource.importCsv(HEADER + "A,a1,,,,\nB,b1,,,,\n");

        assertEquals(1, result.getImported().size());
        assertEquals(List.of(new DeviceImportResult.SkippedRow(3, "b1", "Device limit exceeded")),
                result.getSkipped());
    }

    @Test
    void testLimitReachedRejectsRequest() {
        user.setDeviceLimit(1);

        assertThrows(SecurityException.class, () -> resource.importCsv(HEADER + "A,a1,,,,\n"));
        verify(actionLogger, never()).create(any(), eq(USER_ID), any());
    }

    @Test
    void testReadonlyRejectsRequest() {
        user.setDeviceReadonly(true);

        assertThrows(SecurityException.class, () -> resource.importCsv(HEADER + "A,a1,,,,\n"));
    }

    @Test
    void testHeaderVariantsAndColumnOrder() throws Exception {
        DeviceImportResult result = resource.importCsv("Unique ID,Group Name,Name\nz1,Trucks,Z\n");

        assertTrue(result.getSkipped().isEmpty());
        assertEquals("Z", result.getImported().get(0).getName());
        assertEquals(10, result.getImported().get(0).getGroupId());
    }

    @Test
    void testMissingRequiredHeader() {
        assertThrows(IllegalArgumentException.class, () -> resource.importCsv("name,phone\nA,1\n"));
        assertThrows(IllegalArgumentException.class, () -> resource.importCsv(""));
    }

}
