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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Singleton
public class MediaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaManager.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());

    public static final String DOCUMENTS_DIRECTORY = "documents";

    private static final Pattern DOCUMENT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._ -]{0,127}");
    private static final int DOCUMENT_BASE_NAME_LENGTH = 100;
    private static final int DOCUMENT_NAME_ATTEMPTS = 1000;
    private static final int BUFFER_SIZE = 8192;

    private final Path path;

    @Inject
    public MediaManager(Config config) {
        String configuredPath = config.getString(Keys.MEDIA_PATH);
        path = configuredPath != null ? Paths.get(configuredPath).toAbsolutePath().normalize() : null;
    }

    private File createFile(String uniqueId, String name) throws IOException {
        Path filePath = path.resolve(uniqueId).resolve(name).normalize();
        if (!filePath.startsWith(path)) {
            throw new IOException("Invalid media path");
        }
        Path directoryPath = filePath.getParent();
        if (directoryPath != null) {
            Files.createDirectories(directoryPath);
        }
        return filePath.toFile();
    }

    public OutputStream createFileStream(String uniqueId, String name, String extension) throws IOException {
        return new FileOutputStream(createFile(uniqueId, name + "." + extension));
    }

    private Path documentsPath(String uniqueId) throws IOException {
        if (path == null) {
            throw new IllegalStateException("Media path is not configured");
        }
        Path directoryPath = path.resolve(uniqueId).resolve(DOCUMENTS_DIRECTORY).normalize();
        if (!directoryPath.startsWith(path)) {
            throw new IOException("Invalid media path");
        }
        return directoryPath;
    }

    private Path documentPath(String uniqueId, String name) throws IOException {
        if (name == null || !DOCUMENT_NAME_PATTERN.matcher(name).matches()) {
            return null;
        }
        Path directoryPath = documentsPath(uniqueId);
        Path filePath = directoryPath.resolve(name).normalize();
        return directoryPath.equals(filePath.getParent()) ? filePath : null;
    }

    private static DeviceDocument createDocument(Path filePath) throws IOException {
        DeviceDocument document = new DeviceDocument();
        document.setName(filePath.getFileName().toString());
        document.setSize(Files.size(filePath));
        document.setUploadTime(new Date(Files.getLastModifiedTime(filePath).toMillis()));
        return document;
    }

    /**
     * Turns a client supplied file name into a safe base name (without extension) for a stored document.
     */
    static String documentBaseName(String name) {
        String baseName = name != null ? name : "";
        baseName = baseName.substring(Math.max(baseName.lastIndexOf('/'), baseName.lastIndexOf('\\')) + 1);
        int extensionIndex = baseName.lastIndexOf('.');
        if (extensionIndex >= 0) {
            baseName = baseName.substring(0, extensionIndex);
        }
        baseName = baseName.replaceAll("[^A-Za-z0-9._ -]", "_").replaceFirst("^[. ]+", "").trim();
        if (baseName.length() > DOCUMENT_BASE_NAME_LENGTH) {
            baseName = baseName.substring(0, DOCUMENT_BASE_NAME_LENGTH).trim();
        }
        return baseName.isEmpty() ? "document" : baseName;
    }

    public List<DeviceDocument> listDocuments(String uniqueId) throws IOException {
        Path directoryPath = documentsPath(uniqueId);
        List<DeviceDocument> documents = new ArrayList<>();
        if (Files.isDirectory(directoryPath)) {
            try (Stream<Path> files = Files.list(directoryPath)) {
                for (Path filePath : files.sorted().toList()) {
                    if (Files.isRegularFile(filePath)
                            && DOCUMENT_NAME_PATTERN.matcher(filePath.getFileName().toString()).matches()) {
                        documents.add(createDocument(filePath));
                    }
                }
            }
        }
        return documents;
    }

    /**
     * Returns the stored document file or {@code null} if there is no document with the given name.
     */
    public File getDocument(String uniqueId, String name) throws IOException {
        Path filePath = documentPath(uniqueId, name);
        return filePath != null && Files.isRegularFile(filePath) ? filePath.toFile() : null;
    }

    public boolean deleteDocument(String uniqueId, String name) throws IOException {
        Path filePath = documentPath(uniqueId, name);
        return filePath != null && Files.isRegularFile(filePath) && Files.deleteIfExists(filePath);
    }

    /**
     * Stores a document, adding a numeric suffix to the name if a document with the same name already exists.
     *
     * @throws IllegalArgumentException if the content is larger than {@code sizeLimit} bytes
     */
    public DeviceDocument writeDocument(
            String uniqueId, String name, String extension, InputStream input, long sizeLimit) throws IOException {

        Path directoryPath = documentsPath(uniqueId);
        Files.createDirectories(directoryPath);
        Path temporaryPath = Files.createTempFile(directoryPath, ".upload-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryPath)) {
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
            String baseName = documentBaseName(name);
            for (int i = 0;; i++) {
                String fileName = (i == 0 ? baseName : baseName + "-" + i) + "." + extension;
                try {
                    Path filePath = Files.move(temporaryPath, directoryPath.resolve(fileName));
                    return createDocument(filePath);
                } catch (FileAlreadyExistsException e) {
                    if (i >= DOCUMENT_NAME_ATTEMPTS) {
                        throw e;
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
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

}
