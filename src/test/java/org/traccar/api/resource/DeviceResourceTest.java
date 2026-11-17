package org.traccar.api.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceResourceTest {

    @Test
    void testDocumentExtension() {
        assertEquals("pdf", DeviceResource.documentExtension("application/pdf"));
        assertEquals("jpg", DeviceResource.documentExtension("image/jpeg"));
        assertEquals("png", DeviceResource.documentExtension("IMAGE/PNG; charset=binary"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentExtension("text/html"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentExtension("image/svg+xml"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentExtension(null));
    }

    @Test
    void testDocumentName() {
        assertEquals("registration.pdf", DeviceResource.documentName("registration", "pdf"));
        assertEquals("Registration.PDF", DeviceResource.documentName("Registration.PDF", "pdf"));
        assertEquals("insurance (2024).pdf.jpg", DeviceResource.documentName("insurance (2024).pdf", "jpg"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName(null, "pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName("", "pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName("../device", "pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName("a/b", "pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName(".hidden", "pdf"));
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName("a\"b", "pdf"));
        String longName = "x".repeat(129);
        assertThrows(IllegalArgumentException.class, () -> DeviceResource.documentName(longName, "pdf"));
    }

    @Test
    void testDocumentType() {
        assertEquals("application/pdf", DeviceResource.documentType("registration.pdf"));
        assertEquals("image/jpeg", DeviceResource.documentType("photo.JPG"));
        assertEquals("application/octet-stream", DeviceResource.documentType("unknown.bin"));
    }

}
