/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api.resource;

import org.traccar.api.BaseObjectResource;
import org.traccar.database.MediaManager;
import org.traccar.helper.CsvParser;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.DeviceAccumulators;
import org.traccar.model.DeviceImportResult;
import org.traccar.model.Group;
import org.traccar.model.LinkedDevice;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Path("devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceResource extends BaseObjectResource<Device> {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int IMAGE_SIZE_LIMIT = 500000;

    private static final String UNIQUE_ID = "uniqueId";

    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_UNIQUE_ID = "uniqueid";
    private static final String COLUMN_GROUP = "group";

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private MediaManager mediaManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public DeviceResource() {
        super(Device.class);
    }

    @GET
    public Stream<Device> get(
            @QueryParam("all") boolean all, @QueryParam("userId") long userId,
            @QueryParam("uniqueId") List<String> uniqueIds,
            @QueryParam("id") List<Long> deviceIds,
            @QueryParam("deviceId") long linkedFromDeviceId,
            @QueryParam("excludeAttributes") boolean excludeAttributes,
            @QueryParam("limit") int limit, @QueryParam("offset") int offset,
            @QueryParam("keyword") String keyword) throws StorageException {

        Columns columns = excludeAttributes ? new Columns.Exclude("attributes") : new Columns.All();

        if (!uniqueIds.isEmpty() || !deviceIds.isEmpty()) {

            List<Device> result = new LinkedList<>();
            for (String uniqueId : uniqueIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals(UNIQUE_ID, uniqueId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            for (Long deviceId : deviceIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("id", deviceId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            return withRetention(result.stream());

        } else {

            var conditions = new LinkedList<Condition>();

            if (all) {
                if (permissionsService.notAdmin(getUserId())) {
                    conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                }
            } else {
                if (userId == 0) {
                    conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                } else {
                    permissionsService.checkUser(getUserId(), userId);
                    conditions.add(new Condition.Permission(User.class, userId, baseClass).excludeGroups());
                }
            }

            if (linkedFromDeviceId > 0) {
                permissionsService.checkPermission(Device.class, getUserId(), linkedFromDeviceId);
                conditions.add(new Condition.Permission(
                        Device.class, linkedFromDeviceId, LinkedDevice.class).excludeGroups());
            }

            if (keyword != null && !keyword.isEmpty()) {
                conditions.add(new Condition.Contains(
                        List.of("name", UNIQUE_ID, "phone", "model", "contact"), keyword));
            }

            return withRetention(storage.getObjectsStream(baseClass, new Request(
                    columns, Condition.merge(conditions), new Order("name", false, limit, offset))));

        }
    }

    @Path("{id}/accumulators")
    @PUT
    public Response updateAccumulators(DeviceAccumulators entity) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
        permissionsService.checkEdit(getUserId(), Device.class, false, false);

        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(entity.getDeviceId())));
        if (position != null) {
            if (entity.getTotalDistance() != null) {
                position.getAttributes().put(Position.KEY_TOTAL_DISTANCE, entity.getTotalDistance());
            }
            if (entity.getHours() != null) {
                position.getAttributes().put(Position.KEY_HOURS, entity.getHours());
            }
            position.setServerTime(new Date());
            position.setId(storage.addObject(position, new Request(new Columns.Exclude("id"))));

            Device device = new Device();
            device.setId(position.getDeviceId());
            device.setPositionId(position.getId());
            storage.updateObject(device, new Request(
                    new Columns.Include("positionId"),
                    new Condition.Equals("id", device.getId())));

            var key = new Object();
            try {
                cacheManager.addDevice(position.getDeviceId(), key);
                cacheManager.updatePosition(position);
                connectionManager.updatePosition(true, position);
            } finally {
                cacheManager.removeDevice(position.getDeviceId(), key);
            }
        } else {
            throw new IllegalArgumentException();
        }

        actionLogger.resetAccumulators(request, getUserId(), entity.getDeviceId());
        return Response.noContent().build();
    }

    private static String normalizeHeader(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index >= row.size()) {
            return null;
        }
        String value = row.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    private Map<String, List<Group>> getAccessibleGroups() throws StorageException {
        Condition condition = permissionsService.notAdmin(getUserId())
                ? new Condition.Permission(User.class, getUserId(), Group.class) : null;
        return storage.getObjects(Group.class, new Request(new Columns.Include("id", "name"), condition))
                .stream()
                .filter(group -> group.getName() != null)
                .collect(Collectors.groupingBy(group -> group.getName().trim()));
    }

    @Path("import")
    @POST
    @Consumes("text/csv")
    public DeviceImportResult importCsv(String content) throws Exception {
        permissionsService.checkEdit(getUserId(), Device.class, true, false);

        List<List<String>> rows = CsvParser.parse(content);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Missing header row");
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> headers = rows.get(0);
        for (int i = 0; i < headers.size(); i++) {
            String header = normalizeHeader(headers.get(i));
            columns.putIfAbsent("groupname".equals(header) ? COLUMN_GROUP : header, i);
        }
        if (!columns.containsKey(COLUMN_NAME) || !columns.containsKey(COLUMN_UNIQUE_ID)) {
            throw new IllegalArgumentException("Header must contain name and uniqueId columns");
        }

        Map<String, List<Group>> groups = getAccessibleGroups();
        Set<String> seenUniqueIds = new HashSet<>();
        DeviceImportResult result = new DeviceImportResult();

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String uniqueId = cell(row, columns.get(COLUMN_UNIQUE_ID));
            String reason = importRow(row, columns, groups, seenUniqueIds, result);
            if (reason != null) {
                result.addSkipped(i + 1, uniqueId, reason);
            }
        }
        return result;
    }

    private String importRow(
            List<String> row, Map<String, Integer> columns, Map<String, List<Group>> groups,
            Set<String> seenUniqueIds, DeviceImportResult result) throws Exception {

        String name = cell(row, columns.get(COLUMN_NAME));
        String uniqueId = cell(row, columns.get(COLUMN_UNIQUE_ID));
        if (name == null) {
            return "Missing name";
        }
        if (uniqueId == null) {
            return "Missing unique ID";
        }

        Device device = new Device();
        try {
            device.setUniqueId(uniqueId);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (!seenUniqueIds.add(device.getUniqueId()) || storage.getObject(Device.class, new Request(
                new Columns.Include("id"), new Condition.Equals(UNIQUE_ID, device.getUniqueId()))) != null) {
            return "Duplicate unique ID";
        }
        device.setName(name);
        device.setPhone(cell(row, columns.get("phone")));
        device.setModel(cell(row, columns.get("model")));
        device.setCategory(cell(row, columns.get("category")));

        String groupName = cell(row, columns.get(COLUMN_GROUP));
        if (groupName != null) {
            List<Group> matches = groups.getOrDefault(groupName, List.of());
            if (matches.isEmpty()) {
                return "Unknown group";
            } else if (matches.size() > 1) {
                return "Ambiguous group";
            }
            device.setGroupId(matches.get(0).getId());
        }

        try {
            permissionsService.checkEdit(getUserId(), device, true, false);
        } catch (SecurityException e) {
            return "Device limit exceeded";
        }

        createObject(device);
        result.addImported(device);
        return null;
    }

    private String imageExtension(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
    }

    @Path("{id}/image")
    @POST
    @Consumes("image/*")
    public Response uploadImage(
            @PathParam("id") long deviceId, File file,
            @HeaderParam(HttpHeaders.CONTENT_TYPE) String type) throws StorageException, IOException {

        permissionsService.checkEdit(getUserId(), Device.class, false, false);

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, getUserId(), Device.class))));
        if (device != null) {
            String name = "device";
            String extension = imageExtension(type);
            try (var input = new FileInputStream(file);
                    var output = mediaManager.createFileStream(device.getUniqueId(), name, extension)) {

                long transferred = 0;
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer, 0, buffer.length)) >= 0) {
                    output.write(buffer, 0, read);
                    transferred += read;
                    if (transferred > IMAGE_SIZE_LIMIT) {
                        throw new IllegalArgumentException("Image size limit exceeded");
                    }
                }
            }
            return Response.ok(name + "." + extension).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

}
