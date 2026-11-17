package org.traccar.api.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.MediaManager;
import org.traccar.model.Device;
import org.traccar.model.DeviceDocument;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceResourceDocumentTest {

    private static final long USER_ID = 1;
    private static final long DEVICE_ID = 1;
    private static final long OTHER_DEVICE_ID = 2;

    private static final byte[] PDF = "%PDF-1.7\n%test document\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};

    @TempDir
    private Path mediaPath;

    private User user;
    private DeviceResource resource;

    private static Device device(long id, String uniqueId) {
        Device device = new Device();
        device.setId(id);
        device.setName(uniqueId);
        device.setUniqueId(uniqueId);
        return device;
    }

    private static InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    @BeforeEach
    void setUp() throws Exception {
        ResourceTestStorage storage = new ResourceTestStorage();

        user = new User();
        user.setId(USER_ID);
        storage.put(user, false);
        storage.put(new Server(), false);
        storage.put(device(DEVICE_ID, "truck"), true);
        storage.put(device(OTHER_DEVICE_ID, "hidden"), false);

        Config config = new Config();
        config.setString(Keys.MEDIA_PATH, mediaPath.toString());

        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(new UserPrincipal(USER_ID, null));

        resource = new DeviceResource();
        ResourceTestStorage.inject(resource, SecurityContext.class, securityContext);
        ResourceTestStorage.inject(resource, Storage.class, storage);
        ResourceTestStorage.inject(resource, PermissionsService.class, new PermissionsService(storage));
        ResourceTestStorage.inject(resource, MediaManager.class, new MediaManager(config));
    }

    @Test
    void testUploadListDownloadDelete() throws Exception {
        DeviceDocument uploaded = resource.uploadDocument(
                DEVICE_ID, "Insurance 2026.pdf", stream(PDF), "application/pdf");
        assertEquals("Insurance 2026.pdf", uploaded.getName());
        assertEquals(PDF.length, uploaded.getSize());
        assertNotNull(uploaded.getUploadTime());
        assertTrue(Files.isRegularFile(
                mediaPath.resolve("truck").resolve(MediaManager.DOCUMENTS_DIRECTORY).resolve("Insurance 2026.pdf")));

        resource.uploadDocument(DEVICE_ID, "photo.PNG", stream(PNG), "image/png; charset=binary");

        List<DeviceDocument> documents = resource.getDocuments(DEVICE_ID);
        assertEquals(List.of("Insurance 2026.pdf", "photo.png"),
                documents.stream().map(DeviceDocument::getName).toList());
        assertEquals(PNG.length, documents.get(1).getSize());

        try (Response response = resource.downloadDocument(DEVICE_ID, "Insurance 2026.pdf")) {
            assertEquals(200, response.getStatus());
            assertEquals("application/pdf", response.getMediaType().toString());
            assertEquals("attachment; filename=\"Insurance 2026.pdf\"",
                    response.getHeaderString(HttpHeaders.CONTENT_DISPOSITION));
            assertArrayEquals(PDF, Files.readAllBytes(((File) response.getEntity()).toPath()));
        }

        try (Response response = resource.deleteDocument(DEVICE_ID, "Insurance 2026.pdf")) {
            assertEquals(204, response.getStatus());
        }
        assertEquals(List.of("photo.png"),
                resource.getDocuments(DEVICE_ID).stream().map(DeviceDocument::getName).toList());
        assertThrows(NotFoundException.class, () -> resource.downloadDocument(DEVICE_ID, "Insurance 2026.pdf"));
        assertThrows(NotFoundException.class, () -> resource.deleteDocument(DEVICE_ID, "Insurance 2026.pdf"));
    }

    @Test
    void testEmptyList() throws Exception {
        assertTrue(resource.getDocuments(DEVICE_ID).isEmpty());
    }

    @Test
    void testDuplicateAndUnsafeNames() throws Exception {
        assertEquals("registration.pdf",
                resource.uploadDocument(DEVICE_ID, "registration.pdf", stream(PDF), "application/pdf").getName());
        assertEquals("registration-1.pdf",
                resource.uploadDocument(DEVICE_ID, "registration.pdf", stream(PDF), "application/pdf").getName());
        assertEquals("passwd.pdf",
                resource.uploadDocument(DEVICE_ID, "../../etc/passwd", stream(PDF), "application/pdf").getName());
        assertEquals("evil.pdf",
                resource.uploadDocument(DEVICE_ID, "..\\evil.exe", stream(PDF), "application/pdf").getName());
        assertEquals("a_b_.pdf",
                resource.uploadDocument(DEVICE_ID, "a\"b<.pdf", stream(PDF), "application/pdf").getName());
        assertEquals("document.pdf",
                resource.uploadDocument(DEVICE_ID, null, stream(PDF), "application/pdf").getName());
        assertEquals(6, resource.getDocuments(DEVICE_ID).size());

        assertThrows(NotFoundException.class, () -> resource.downloadDocument(DEVICE_ID, ".."));
        assertThrows(NotFoundException.class, () -> resource.downloadDocument(DEVICE_ID, "../hidden/x.pdf"));
        assertThrows(NotFoundException.class, () -> resource.deleteDocument(DEVICE_ID, ".."));
    }

    @Test
    void testInvalidContent() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> resource.uploadDocument(DEVICE_ID, "a.txt", stream(PDF), "text/plain"));
        assertThrows(IllegalArgumentException.class,
                () -> resource.uploadDocument(DEVICE_ID, "a.svg", stream(PDF), "image/svg+xml"));
        assertThrows(IllegalArgumentException.class,
                () -> resource.uploadDocument(DEVICE_ID, "a.pdf", stream(PNG), "application/pdf"));
        assertThrows(IllegalArgumentException.class,
                () -> resource.uploadDocument(DEVICE_ID, "a.png", stream(new byte[0]), "image/png"));
        assertTrue(resource.getDocuments(DEVICE_ID).isEmpty());
    }

    @Test
    void testSizeLimit() throws Exception {
        byte[] limit = Arrays.copyOf(PDF, 10 * 1024 * 1024);
        assertEquals(limit.length,
                resource.uploadDocument(DEVICE_ID, "max.pdf", stream(limit), "application/pdf").getSize());

        byte[] tooLarge = Arrays.copyOf(PDF, limit.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> resource.uploadDocument(DEVICE_ID, "big.pdf", stream(tooLarge), "application/pdf"));

        try (Stream<Path> files = Files.list(mediaPath.resolve("truck").resolve(MediaManager.DOCUMENTS_DIRECTORY))) {
            assertEquals(List.of("max.pdf"), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    @Test
    void testReadonlyUserCanOnlyView() throws Exception {
        resource.uploadDocument(DEVICE_ID, "manual.pdf", stream(PDF), "application/pdf");
        user.setDeviceReadonly(true);

        assertEquals(1, resource.getDocuments(DEVICE_ID).size());
        try (Response response = resource.downloadDocument(DEVICE_ID, "manual.pdf")) {
            assertEquals(200, response.getStatus());
        }
        assertThrows(SecurityException.class,
                () -> resource.uploadDocument(DEVICE_ID, "other.pdf", stream(PDF), "application/pdf"));
        assertThrows(SecurityException.class, () -> resource.deleteDocument(DEVICE_ID, "manual.pdf"));
        assertEquals(1, resource.getDocuments(DEVICE_ID).size());

        user.setDeviceReadonly(false);
        user.setReadonly(true);
        assertThrows(SecurityException.class, () -> resource.deleteDocument(DEVICE_ID, "manual.pdf"));
    }

    @Test
    void testInaccessibleDevice() throws Exception {
        Path documents = mediaPath.resolve("hidden").resolve(MediaManager.DOCUMENTS_DIRECTORY);
        Files.createDirectories(documents);
        Files.write(documents.resolve("secret.pdf"), PDF);

        assertThrows(SecurityException.class, () -> resource.getDocuments(OTHER_DEVICE_ID));
        assertThrows(SecurityException.class, () -> resource.downloadDocument(OTHER_DEVICE_ID, "secret.pdf"));
        assertThrows(SecurityException.class,
                () -> resource.uploadDocument(OTHER_DEVICE_ID, "x.pdf", stream(PDF), "application/pdf"));
        assertThrows(SecurityException.class, () -> resource.deleteDocument(OTHER_DEVICE_ID, "secret.pdf"));
        assertTrue(Files.exists(documents.resolve("secret.pdf")));
    }

}
