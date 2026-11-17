package org.traccar.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.traccar.MainModule;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceDocumentTest {

    @Test
    void testJsonSerialization() throws JsonProcessingException {
        DeviceDocument document = new DeviceDocument();
        document.setName("registration.pdf");
        document.setSize(1024);
        document.setUploadTime(Instant.parse("2026-11-17T10:15:30Z"));

        assertEquals(
                "{\"name\":\"registration.pdf\",\"size\":1024,\"uploadTime\":\"2026-11-17T10:15:30Z\"}",
                MainModule.provideObjectMapper().writeValueAsString(document));
    }

}
