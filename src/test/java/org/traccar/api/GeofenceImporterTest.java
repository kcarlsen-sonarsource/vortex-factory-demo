package org.traccar.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.model.Geofence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GeofenceImporterTest {

    private final GeofenceImporter importer = new GeofenceImporter(new ObjectMapper());

    private GeofenceImporter.Result parse(String content) throws IOException {
        return importer.parse(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testKml() throws IOException {
        var result = parse("""
                ﻿<?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2">
                  <Document>
                    <name>Depots</name>
                    <Folder>
                      <Placemark>
                        <name>Yard</name>
                        <description>Main yard</description>
                        <Polygon><outerBoundaryIs><LinearRing>
                          <coordinates>
                            37.0,55.0,0 37.1,55.0,0 37.1,55.1,0 37.0,55.0,0
                          </coordinates>
                        </LinearRing></outerBoundaryIs></Polygon>
                      </Placemark>
                    </Folder>
                    <Placemark>
                      <LineString><coordinates>37.0,55.0 37.2,55.2</coordinates></LineString>
                    </Placemark>
                    <Placemark>
                      <name>Gate</name>
                      <ExtendedData><Data name="radius"><value>150</value></Data></ExtendedData>
                      <Point><coordinates>37.5,55.5</coordinates></Point>
                    </Placemark>
                    <Placemark>
                      <name>Marker</name>
                      <Point><coordinates>37.5,55.5</coordinates></Point>
                    </Placemark>
                    <Placemark>
                      <name>Pond</name>
                      <Polygon>
                        <outerBoundaryIs><LinearRing>
                          <coordinates>0,0 0,1 1,1 0,0</coordinates>
                        </LinearRing></outerBoundaryIs>
                        <innerBoundaryIs><LinearRing>
                          <coordinates>0.1,0.1 0.1,0.2 0.2,0.2 0.1,0.1</coordinates>
                        </LinearRing></innerBoundaryIs>
                      </Polygon>
                    </Placemark>
                    <Placemark><name>Empty</name></Placemark>
                  </Document>
                </kml>
                """);

        assertEquals(3, result.geofences().size());

        Geofence polygon = result.geofences().get(0);
        assertEquals("Yard", polygon.getName());
        assertEquals("Main yard", polygon.getDescription());
        assertEquals("POLYGON ((55.0 37.0, 55.0 37.1, 55.1 37.1, 55.0 37.0))", polygon.getArea());

        Geofence line = result.geofences().get(1);
        assertEquals("Depots 2", line.getName());
        assertNull(line.getDescription());
        assertEquals("LINESTRING (55.0 37.0, 55.2 37.2)", line.getArea());

        Geofence circle = result.geofences().get(2);
        assertEquals("Gate", circle.getName());
        assertEquals("CIRCLE (55.5 37.5, 150.0)", circle.getArea());

        assertEquals(3, result.skipped().size());
        assertEquals(new GeofenceImporter.Skipped(3, "Marker",
                "Point requires a positive radius to be imported as a circle"), result.skipped().get(0));
        assertEquals(new GeofenceImporter.Skipped(4, "Pond",
                "Polygons with holes are not supported"), result.skipped().get(1));
        assertEquals(new GeofenceImporter.Skipped(5, "Empty", "No geometry"), result.skipped().get(2));
    }

    @Test
    void testKmlMultiGeometry() throws IOException {
        var result = parse("""
                <kml><Placemark><name>Route</name><MultiGeometry>
                  <LineString><coordinates>1,2 3,4</coordinates></LineString>
                  <LineString><coordinates>5,6 7,8</coordinates></LineString>
                  <LineString><coordinates>500,6 7,8</coordinates></LineString>
                </MultiGeometry></Placemark></kml>
                """);
        assertEquals(2, result.geofences().size());
        assertEquals("Route 1", result.geofences().get(0).getName());
        assertEquals("LINESTRING (2.0 1.0, 4.0 3.0)", result.geofences().get(0).getArea());
        assertEquals("Route 2", result.geofences().get(1).getName());
        assertEquals(1, result.skipped().size());
        assertEquals("Route 3", result.skipped().get(0).name());
        assertTrue(result.skipped().get(0).reason().startsWith("Coordinates out of range"));
    }

    @Test
    void testKmlRejectsDoctype() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE kml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <kml><Placemark><name>&xxe;</name></Placemark></kml>
                """));
    }

    @Test
    void testGeoJson() throws IOException {
        var result = parse("""
                {
                  "type": "FeatureCollection",
                  "name": "Zones",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {"name": "Field", "description": "North"},
                      "geometry": {"type": "Polygon", "coordinates": [[[10, 20], [11, 20], [11, 21], [10, 20]]]}
                    },
                    {
                      "type": "Feature",
                      "properties": {"radius": 50},
                      "geometry": {"type": "Point", "coordinates": [10.5, 20.5]}
                    },
                    {
                      "type": "Feature",
                      "properties": {"name": "Roads"},
                      "geometry": {"type": "MultiLineString", "coordinates": [[[1, 2], [3, 4]], [[5, 6], [7, 8]]]}
                    },
                    {
                      "type": "Feature",
                      "properties": {"name": "Spot"},
                      "geometry": {"type": "Point", "coordinates": [1, 2]}
                    },
                    {
                      "type": "Feature",
                      "properties": null,
                      "geometry": null
                    },
                    {
                      "type": "Feature",
                      "properties": {"name": "Tiny"},
                      "geometry": {"type": "Polygon", "coordinates": [[[1, 2], [3, 4]]]}
                    }
                  ]
                }
                """);

        assertEquals(4, result.geofences().size());
        assertEquals("Field", result.geofences().get(0).getName());
        assertEquals("North", result.geofences().get(0).getDescription());
        assertEquals("POLYGON ((20.0 10.0, 20.0 11.0, 21.0 11.0, 20.0 10.0))", result.geofences().get(0).getArea());
        assertEquals("Zones 2", result.geofences().get(1).getName());
        assertEquals("CIRCLE (20.5 10.5, 50.0)", result.geofences().get(1).getArea());
        assertEquals("Roads 1", result.geofences().get(2).getName());
        assertEquals("LINESTRING (2.0 1.0, 4.0 3.0)", result.geofences().get(2).getArea());
        assertEquals("Roads 2", result.geofences().get(3).getName());

        assertEquals(3, result.skipped().size());
        assertEquals("Spot", result.skipped().get(0).name());
        assertNull(result.skipped().get(1).name());
        assertEquals("No geometry", result.skipped().get(1).reason());
        assertEquals(new GeofenceImporter.Skipped(6, "Tiny", "Polygon requires at least 3 points"),
                result.skipped().get(2));
    }

    @Test
    void testGeoJsonBareGeometry() throws IOException {
        var result = parse("""
                {"type": "GeometryCollection", "geometries": [
                  {"type": "LineString", "coordinates": [[1, 2], [3, 4]]},
                  {"type": "Polygon", "coordinates": [[[0, 0], [0, 1], [1, 1], [0, 0]], [[0, 0], [0, 1], [1, 1]]]},
                  {"type": "Curve", "coordinates": []}
                ]}
                """);
        assertEquals(1, result.geofences().size());
        assertEquals("Imported geofence 1", result.geofences().get(0).getName());
        assertEquals(2, result.skipped().size());
        assertEquals("Polygons with holes are not supported", result.skipped().get(0).reason());
        assertEquals("Unsupported geometry type: Curve", result.skipped().get(1).reason());
    }

    @Test
    void testInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> parse("   "));
        assertThrows(IllegalArgumentException.class, () -> parse("name,lat,lon"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"type\": "));
        assertThrows(IllegalArgumentException.class, () -> parse("<kml><Placemark>"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"type\": \"FeatureCollection\"}"));
    }

}
