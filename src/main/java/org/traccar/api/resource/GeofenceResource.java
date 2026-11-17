/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.api.ExtendedObjectResource;
import org.traccar.api.GeofenceImporter;
import org.traccar.model.Geofence;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Path("geofences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeofenceResource extends ExtendedObjectResource<Geofence> {

    private final ObjectMapper objectMapper;

    @Inject
    public GeofenceResource(ObjectMapper objectMapper) {
        super(Geofence.class, "name", List.of("name"));
        this.objectMapper = objectMapper;
    }

    public record ImportResult(List<Geofence> geofences, List<GeofenceImporter.Skipped> skipped) {
    }

    @Path("import")
    @POST
    @Consumes("*/*")
    public Response importFile(InputStream input) throws Exception {
        permissionsService.checkEdit(getUserId(), Geofence.class, true, false);

        byte[] data = input.readNBytes(GeofenceImporter.MAX_SIZE + 1);
        if (data.length > GeofenceImporter.MAX_SIZE) {
            throw new WebApplicationException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
        }

        GeofenceImporter.Result result = new GeofenceImporter(objectMapper).parse(data);
        List<Geofence> created = new ArrayList<>();
        for (Geofence geofence : result.geofences()) {
            created.add(createObject(geofence));
        }
        return Response.ok(new ImportResult(created, result.skipped())).build();
    }

}
