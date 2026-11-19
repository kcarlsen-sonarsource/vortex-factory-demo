package org.traccar.api.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.ResourceErrorHandler;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.config.Config;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.GroupedModel;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.MemoryStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetentionResourceTest {

    private MemoryStorage storage;
    private GroupResource groupResource;
    private DeviceResource deviceResource;

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

    private void setUpResource(Object resource, long userId) throws IllegalAccessException {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(new UserPrincipal(userId, null));
        inject(resource, SecurityContext.class, securityContext);
        inject(resource, Storage.class, storage);
        inject(resource, PermissionsService.class, new PermissionsService(storage));
        inject(resource, LogAction.class, mock(LogAction.class));
        inject(resource, CacheManager.class, mock(CacheManager.class));
        inject(resource, ConnectionManager.class, mock(ConnectionManager.class));
        inject(resource, HttpServletRequest.class, mock(HttpServletRequest.class));
    }

    @BeforeEach
    void setUp() throws Exception {
        storage = new MemoryStorage();
        User user = new User();
        user.setAdministrator(true);
        user.setId(storage.addObject(user, new Request(new Columns.Exclude("id"))));

        groupResource = new GroupResource();
        setUpResource(groupResource, user.getId());
        deviceResource = new DeviceResource();
        setUpResource(deviceResource, user.getId());
    }

    private Group addGroup(long parentId, Integer retentionDays) throws Exception {
        Group group = new Group();
        group.setName("group");
        group.setGroupId(parentId);
        group.setRetentionDays(retentionDays);
        return (Group) groupResource.add(group).getEntity();
    }

    @Test
    void testRejectsNonPositiveRetention() throws Exception {
        Group zero = new Group();
        zero.setRetentionDays(0);
        Exception error = assertThrows(IllegalArgumentException.class, () -> groupResource.add(zero));
        Response response = new ResourceErrorHandler(mock(Config.class)).toResponse(error);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        Group group = addGroup(0, 30);
        group.setRetentionDays(-1);
        assertThrows(IllegalArgumentException.class, () -> groupResource.update(group));

        Device device = new Device();
        device.setName("device");
        device.setUniqueId("negative");
        device.setRetentionDays(-10);
        assertThrows(IllegalArgumentException.class, () -> deviceResource.add(device));
    }

    @Test
    void testEffectiveRetentionInResponses() throws Exception {
        Group root = addGroup(0, 90);
        Group middle = addGroup(root.getId(), null);
        Group leaf = addGroup(middle.getId(), null);

        assertEquals(GroupedModel.RETENTION_SOURCE_OWN, root.getRetentionSource());
        assertEquals(90, leaf.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_GROUP, leaf.getRetentionSource());
        assertEquals(root.getId(), leaf.getRetentionGroupId());

        Group single = (Group) groupResource.getSingle(middle.getId()).getEntity();
        assertEquals(90, single.getEffectiveRetentionDays());

        Device device = new Device();
        device.setName("device");
        device.setUniqueId("inherited");
        device.setGroupId(leaf.getId());
        device = (Device) deviceResource.add(device).getEntity();
        assertEquals(90, device.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_GROUP, device.getRetentionSource());

        device.setRetentionDays(7);
        device = (Device) deviceResource.update(device).getEntity();
        assertEquals(7, device.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_OWN, device.getRetentionSource());

        Device standalone = new Device();
        standalone.setName("standalone");
        standalone.setUniqueId("standalone");
        deviceResource.add(standalone);

        List<Device> devices = deviceResource.get(
                true, 0, List.of(), List.of(), 0, false, 0, 0, null).toList();
        Device listed = devices.stream().filter(d -> d.getId() == standalone.getId()).findFirst().orElseThrow();
        assertNull(listed.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_NONE, listed.getRetentionSource());

        List<Group> groups = groupResource.get(true, 0, false, 0, 0, null).toList();
        assertEquals(3, groups.size());
        groups.forEach(group -> assertEquals(90, group.getEffectiveRetentionDays()));
    }

}
