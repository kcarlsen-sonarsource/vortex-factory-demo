package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.helper.LogAction;
import org.traccar.model.Geofence;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.SecurityContext;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GeofenceResourceTest {

    private static final long USER_ID = 7;

    private static final String GEOJSON = """
            {"type": "FeatureCollection", "features": [
              {"type": "Feature", "properties": {"name": "A"},
               "geometry": {"type": "LineString", "coordinates": [[1, 2], [3, 4]]}},
              {"type": "Feature", "properties": {"name": "B", "radius": 25},
               "geometry": {"type": "Point", "coordinates": [1, 2]}},
              {"type": "Feature", "properties": {"name": "C"},
               "geometry": {"type": "Point", "coordinates": [1, 2]}}
            ]}
            """;

    private final Storage storage = mock(Storage.class);
    private final PermissionsService permissionsService = mock(PermissionsService.class);
    private final CacheManager cacheManager = mock(CacheManager.class);
    private final ConnectionManager connectionManager = mock(ConnectionManager.class);
    private final LogAction actionLogger = mock(LogAction.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private GeofenceResource resource;

    private static void inject(Object target, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> clazz = target.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // check parent class
            }
        }
        throw new NoSuchFieldException(name);
    }

    @BeforeEach
    void setUp() throws Exception {
        resource = new GeofenceResource(new ObjectMapper());
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(new UserPrincipal(USER_ID, null));
        inject(resource, "securityContext", securityContext);
        inject(resource, "storage", storage);
        inject(resource, "permissionsService", permissionsService);
        inject(resource, "cacheManager", cacheManager);
        inject(resource, "connectionManager", connectionManager);
        inject(resource, "actionLogger", actionLogger);
        inject(resource, "request", request);
    }

    private GeofenceResource.ImportResult importGeoJson() throws Exception {
        var response = resource.importFile(new ByteArrayInputStream(GEOJSON.getBytes(StandardCharsets.UTF_8)));
        return (GeofenceResource.ImportResult) response.getEntity();
    }

    @Test
    void testImportLinksGeofencesToUser() throws Exception {
        when(storage.addObject(any(Geofence.class), any(Request.class))).thenReturn(101L, 102L);

        var result = importGeoJson();

        assertEquals(2, result.geofences().size());
        assertEquals(101L, result.geofences().get(0).getId());
        assertEquals("A", result.geofences().get(0).getName());
        assertEquals(102L, result.geofences().get(1).getId());
        assertEquals(1, result.skipped().size());
        assertEquals("C", result.skipped().get(0).name());

        verify(permissionsService).checkEdit(USER_ID, Geofence.class, true, false);
        verify(permissionsService, times(2)).checkEdit(eq(USER_ID), any(Geofence.class), eq(true), eq(false));

        ArgumentCaptor<Permission> permissions = ArgumentCaptor.forClass(Permission.class);
        verify(storage, times(2)).addPermission(permissions.capture());
        for (int i = 0; i < 2; i++) {
            Permission permission = permissions.getAllValues().get(i);
            assertEquals(User.class, permission.getOwnerClass());
            assertEquals(USER_ID, permission.getOwnerId());
            assertEquals(Geofence.class, permission.getPropertyClass());
            assertEquals(101L + i, permission.getPropertyId());
            verify(cacheManager).invalidatePermission(
                    true, User.class, USER_ID, Geofence.class, 101L + i, true);
            verify(connectionManager).invalidatePermission(
                    true, User.class, USER_ID, Geofence.class, 101L + i, true);
            verify(actionLogger).link(request, USER_ID, User.class, USER_ID, Geofence.class, 101L + i);
        }
        verify(actionLogger, times(2)).create(eq(request), eq(USER_ID), any(Geofence.class));
    }

    @Test
    void testImportDeniedForReadonlyUser() throws Exception {
        doThrow(new SecurityException("Write access denied"))
                .when(permissionsService).checkEdit(USER_ID, Geofence.class, true, false);

        assertThrows(SecurityException.class, this::importGeoJson);
        verify(storage, never()).addObject(any(), any());
    }

}
