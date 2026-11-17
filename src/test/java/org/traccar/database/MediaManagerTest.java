package org.traccar.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.DeviceDocument;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaManagerTest {

    private static final String UNIQUE_ID = "123456";
    private static final String DOCUMENT_NAME = "registration.pdf";

    @TempDir
    private Path mediaPath;

    private MediaManager mediaManager;

    @BeforeEach
    void setUp() {
        Config config = mock(Config.class);
        when(config.getString(Keys.MEDIA_PATH)).thenReturn(mediaPath.toString());
        mediaManager = new MediaManager(config);
    }

    private static ByteArrayInputStream content(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testWriteListAndGetDocument() throws IOException {
        DeviceDocument written = mediaManager.writeDocument(UNIQUE_ID, DOCUMENT_NAME, content("pdf data"), 100);
        assertEquals(DOCUMENT_NAME, written.getName());
        assertEquals(8, written.getSize());
        assertNotNull(written.getUploadTime());

        assertTrue(Files.isRegularFile(
                mediaPath.resolve(UNIQUE_ID).resolve(MediaManager.DOCUMENTS_DIRECTORY).resolve(DOCUMENT_NAME)));

        mediaManager.writeDocument(UNIQUE_ID, "insurance.png", content("png"), 100);
        List<DeviceDocument> documents = mediaManager.listDocuments(UNIQUE_ID);
        assertEquals(2, documents.size());
        assertEquals("insurance.png", documents.get(0).getName());
        assertEquals(3, documents.get(0).getSize());
        assertEquals(DOCUMENT_NAME, documents.get(1).getName());

        File file = mediaManager.getDocument(UNIQUE_ID, DOCUMENT_NAME);
        assertNotNull(file);
        assertEquals("pdf data", Files.readString(file.toPath()));
    }

    @Test
    void testListDocumentsEmpty() throws IOException {
        assertTrue(mediaManager.listDocuments(UNIQUE_ID).isEmpty());
        assertNull(mediaManager.getDocument(UNIQUE_ID, DOCUMENT_NAME));
    }

    @Test
    void testReplaceDocument() throws IOException {
        mediaManager.writeDocument(UNIQUE_ID, DOCUMENT_NAME, content("old"), 100);
        mediaManager.writeDocument(UNIQUE_ID, DOCUMENT_NAME, content("new content"), 100);
        List<DeviceDocument> documents = mediaManager.listDocuments(UNIQUE_ID);
        assertEquals(1, documents.size());
        assertEquals(11, documents.get(0).getSize());
    }

    @Test
    void testSizeLimitKeepsPreviousDocument() throws IOException {
        mediaManager.writeDocument(UNIQUE_ID, DOCUMENT_NAME, content("old"), 100);
        var oversized = content("this content is too long");
        assertThrows(IllegalArgumentException.class,
                () -> mediaManager.writeDocument(UNIQUE_ID, DOCUMENT_NAME, oversized, 10));

        List<DeviceDocument> documents = mediaManager.listDocuments(UNIQUE_ID);
        assertEquals(1, documents.size());
        assertEquals(3, documents.get(0).getSize());
        try (var files = Files.list(mediaPath.resolve(UNIQUE_ID).resolve(MediaManager.DOCUMENTS_DIRECTORY))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void testDeleteDocument() throws IOException {
        mediaManager.writeDocument(UNIQUE_ID, DOCUMENT_NAME, content("data"), 100);
        assertTrue(mediaManager.deleteDocument(UNIQUE_ID, DOCUMENT_NAME));
        assertFalse(mediaManager.deleteDocument(UNIQUE_ID, DOCUMENT_NAME));
        assertTrue(mediaManager.listDocuments(UNIQUE_ID).isEmpty());
    }

    @Test
    void testPathTraversalRejected() throws IOException {
        Files.createDirectories(mediaPath.resolve(UNIQUE_ID));
        Path image = Files.writeString(mediaPath.resolve(UNIQUE_ID).resolve("device.png"), "image");
        Path other = Files.createDirectories(mediaPath.resolve("other").resolve(MediaManager.DOCUMENTS_DIRECTORY));
        Files.writeString(other.resolve(DOCUMENT_NAME), "secret");

        assertThrows(IOException.class, () -> mediaManager.getDocument(UNIQUE_ID, "../device.png"));
        assertThrows(IOException.class, () -> mediaManager.deleteDocument(UNIQUE_ID, "../device.png"));
        assertThrows(IOException.class,
                () -> mediaManager.getDocument(UNIQUE_ID, "../../other/documents/" + DOCUMENT_NAME));
        assertThrows(IOException.class, () -> mediaManager.getDocument(UNIQUE_ID, "sub/" + DOCUMENT_NAME));
        assertThrows(IOException.class, () -> mediaManager.listDocuments(".."));
        var input = content("data");
        assertThrows(IOException.class, () -> mediaManager.writeDocument(UNIQUE_ID, "../x.pdf", input, 100));

        assertTrue(Files.exists(image));
        assertTrue(Files.exists(other.resolve(DOCUMENT_NAME)));
    }

}
