/*
 * Copyright 2017 - 2022 Anton Tananaev (anton@traccar.org)
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
package org.traccar.database;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.DeviceDocument;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Singleton
public class MediaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaManager.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());

    public static final String DOCUMENTS_DIRECTORY = "documents";

    private static final String TEMP_PREFIX = ".upload";
    private static final int BUFFER_SIZE = 8192;

    private final Path path;

    @Inject
    public MediaManager(Config config) {
        String configuredPath = config.getString(Keys.MEDIA_PATH);
        path = configuredPath != null ? Paths.get(configuredPath).toAbsolutePath().normalize() : null;
    }

    private Path resolvePath(String uniqueId, String name) throws IOException {
        Path filePath = path.resolve(uniqueId).resolve(name).normalize();
        if (!filePath.startsWith(path.resolve(uniqueId).normalize()) || !filePath.startsWith(path)) {
            throw new IOException("Invalid media path");
        }
        return filePath;
    }

    private File createFile(String uniqueId, String name) throws IOException {
        Path filePath = resolvePath(uniqueId, name);
        Path directoryPath = filePath.getParent();
        if (directoryPath != null) {
            Files.createDirectories(directoryPath);
        }
        return filePath.toFile();
    }

    public OutputStream createFileStream(String uniqueId, String name, String extension) throws IOException {
        return new FileOutputStream(createFile(uniqueId, name + "." + extension));
    }

    public String writeFile(String uniqueId, ByteBuf buf, String extension) {
        if (path != null) {
            int size = buf.readableBytes();
            String name = DATE_FORMAT.format(Instant.now()) + "." + extension;
            try (FileOutputStream output = new FileOutputStream(createFile(uniqueId, name));
                    FileChannel fileChannel = output.getChannel()) {
                ByteBuffer byteBuffer = buf.nioBuffer();
                int written = 0;
                while (written < size) {
                    written += fileChannel.write(byteBuffer);
                }
                fileChannel.force(false);
                return name;
            } catch (IOException e) {
                LOGGER.warn("Save media file error", e);
            }
        }
        return null;
    }

    private Path resolveDocument(String uniqueId, String name) throws IOException {
        Path directoryPath = resolvePath(uniqueId, DOCUMENTS_DIRECTORY);
        Path filePath = directoryPath.resolve(name).normalize();
        if (!filePath.startsWith(directoryPath) || !directoryPath.equals(filePath.getParent())) {
            throw new IOException("Invalid document path");
        }
        return filePath;
    }

    /**
     * Stores a device document, replacing any existing document with the same name. The content is written to a
     * temporary file first, so a failed or oversized upload leaves the previous document untouched.
     */
    public DeviceDocument writeDocument(
            String uniqueId, String name, InputStream input, long sizeLimit) throws IOException {
        Path filePath = resolveDocument(uniqueId, name);
        Files.createDirectories(filePath.getParent());
        Path tempPath = Files.createTempFile(filePath.getParent(), TEMP_PREFIX, null);
        try {
            try (OutputStream output = Files.newOutputStream(tempPath)) {
                long transferred = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    transferred += read;
                    if (transferred > sizeLimit) {
                        throw new IllegalArgumentException("Document size limit exceeded");
                    }
                    output.write(buffer, 0, read);
                }
            }
            Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempPath);
        }
        return createDocument(filePath);
    }

    private static DeviceDocument createDocument(Path filePath) throws IOException {
        DeviceDocument document = new DeviceDocument();
        document.setName(filePath.getFileName().toString());
        document.setSize(Files.size(filePath));
        document.setUploadTime(Files.getLastModifiedTime(filePath).toInstant());
        return document;
    }

    public List<DeviceDocument> listDocuments(String uniqueId) throws IOException {
        Path directoryPath = resolvePath(uniqueId, DOCUMENTS_DIRECTORY);
        if (!Files.isDirectory(directoryPath)) {
            return List.of();
        }
        List<DeviceDocument> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(directoryPath)) {
            for (Path filePath : (Iterable<Path>) files::iterator) {
                if (!filePath.getFileName().toString().startsWith(TEMP_PREFIX) && Files.isRegularFile(filePath)) {
                    result.add(createDocument(filePath));
                }
            }
        }
        result.sort(Comparator.comparing(DeviceDocument::getName));
        return result;
    }

    public File getDocument(String uniqueId, String name) throws IOException {
        Path filePath = resolveDocument(uniqueId, name);
        return Files.isRegularFile(filePath) ? filePath.toFile() : null;
    }

    public boolean deleteDocument(String uniqueId, String name) throws IOException {
        return Files.deleteIfExists(resolveDocument(uniqueId, name));
    }

}
