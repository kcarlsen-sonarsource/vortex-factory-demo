/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.model.Geofence;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Converts KML and GeoJSON documents into geofences. Every polygon, line string and point with a radius becomes a
 * separate geofence; anything that can't be represented is reported as skipped.
 */
public class GeofenceImporter {

    public static final int MAX_SIZE = 10 * 1024 * 1024;

    private static final String DEFAULT_NAME = "Imported geofence";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String RADIUS = "radius";
    private static final String POLYGON = "Polygon";
    private static final String LINE_STRING = "LineString";
    private static final String POINT = "Point";
    private static final String COORDINATES = "coordinates";

    public record Skipped(int index, String name, String reason) {
    }

    public record Result(List<Geofence> geofences, List<Skipped> skipped) {
    }

    @FunctionalInterface
    private interface AreaSource {
        String area();
    }

    private final ObjectMapper objectMapper;

    public GeofenceImporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result parse(byte[] data) throws IOException {
        int start = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < data.length && Character.isWhitespace(data[start])) {
            start += 1;
        }
        if (start >= data.length) {
            throw new IllegalArgumentException("Empty file");
        }
        Collector collector = new Collector();
        if (data[start] == '<') {
            parseKml(data, collector);
        } else if (data[start] == '{') {
            parseGeoJson(data, collector);
        } else {
            throw new IllegalArgumentException("Unsupported file format, expected KML or GeoJSON");
        }
        return new Result(collector.geofences, collector.skipped);
    }

    private static final class Collector {

        private final List<Geofence> geofences = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();
        private int index;
        private String baseName = DEFAULT_NAME;

        void setBaseName(String baseName) {
            if (baseName != null && !baseName.isBlank()) {
                this.baseName = baseName.trim();
            }
        }

        void skip(String name, String reason) {
            skipped.add(new Skipped(index++, name, reason));
        }

        void addFeature(String name, String description, List<AreaSource> parts) {
            String featureName = trimToNull(name);
            if (parts.isEmpty()) {
                skip(featureName, "No geometry");
                return;
            }
            for (int i = 0; i < parts.size(); i++) {
                String partName;
                if (featureName == null) {
                    partName = baseName + " " + (index + 1);
                } else {
                    partName = parts.size() > 1 ? featureName + " " + (i + 1) : featureName;
                }
                addPart(partName, trimToNull(description), parts.get(i));
            }
        }

        private void addPart(String name, String description, AreaSource source) {
            try {
                Geofence geofence = new Geofence();
                geofence.setName(name);
                geofence.setDescription(description);
                geofence.setArea(source.area());
                geofence.getGeometry();
                geofences.add(geofence);
                index += 1;
            } catch (RuntimeException e) {
                skip(name, e.getMessage() != null ? e.getMessage() : "Invalid geometry");
            }
        }

        private static String trimToNull(String value) {
            return value != null && !value.isBlank() ? value.trim() : null;
        }

    }

    private static String formatPoint(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Coordinates out of range: " + longitude + "," + latitude);
        }
        return latitude + " " + longitude;
    }

    private static String polygonWkt(List<String> points) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("Polygon requires at least 3 points");
        }
        return "POLYGON ((" + String.join(", ", points) + "))";
    }

    private static String lineWkt(List<String> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("Line requires at least 2 points");
        }
        return "LINESTRING (" + String.join(", ", points) + ")";
    }

    private static String circleWkt(String point, double radius) {
        if (!Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Point requires a positive radius to be imported as a circle");
        }
        return "CIRCLE (" + point + ", " + radius + ")";
    }

    private static String holesNotSupported() {
        throw new IllegalArgumentException("Polygons with holes are not supported");
    }

    // KML

    private static DocumentBuilderFactory createDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private void parseKml(byte[] data, Collector collector) throws IOException {
        Document document;
        try {
            document = createDocumentBuilderFactory().newDocumentBuilder().parse(new ByteArrayInputStream(data));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (SAXException e) {
            throw new IllegalArgumentException("Invalid KML: " + e.getMessage(), e);
        }

        NodeList documents = document.getElementsByTagNameNS("*", "Document");
        if (documents.getLength() > 0) {
            collector.setBaseName(childText((Element) documents.item(0), NAME));
        }

        NodeList placemarks = document.getElementsByTagNameNS("*", "Placemark");
        for (int i = 0; i < placemarks.getLength(); i++) {
            Element placemark = (Element) placemarks.item(i);
            double radius = kmlRadius(placemark);
            List<AreaSource> parts = new ArrayList<>();
            collectKmlGeometries(placemark, radius, parts);
            collector.addFeature(childText(placemark, NAME), childText(placemark, DESCRIPTION), parts);
        }
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element childElement(Element parent, String localName) {
        for (Element element : childElements(parent)) {
            if (localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        Element element = childElement(parent, localName);
        return element != null ? element.getTextContent().trim() : null;
    }

    private static double kmlRadius(Element placemark) {
        Element extendedData = childElement(placemark, "ExtendedData");
        if (extendedData == null) {
            return 0;
        }
        NodeList nodes = extendedData.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (RADIUS.equals(element.getAttribute(NAME))) {
                Element value = childElement(element, "value");
                String text = value != null ? value.getTextContent() : element.getTextContent();
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void collectKmlGeometries(Element parent, double radius, List<AreaSource> parts) {
        for (Element element : childElements(parent)) {
            String type = element.getLocalName();
            switch (type) {
                case "MultiGeometry" -> collectKmlGeometries(element, radius, parts);
                case POLYGON -> parts.add(() -> kmlPolygon(element));
                case "LinearRing" -> parts.add(() -> polygonWkt(kmlCoordinates(element)));
                case LINE_STRING -> parts.add(() -> lineWkt(kmlCoordinates(element)));
                case POINT -> parts.add(() -> kmlCircle(element, radius));
                case "Model", "Track", "MultiTrack" -> parts.add(() -> {
                    throw new IllegalArgumentException("Unsupported geometry type: " + type);
                });
                default -> {
                    // not a geometry element
                }
            }
        }
    }

    private static String kmlPolygon(Element polygon) {
        if (childElement(polygon, "innerBoundaryIs") != null) {
            return holesNotSupported();
        }
        Element outer = childElement(polygon, "outerBoundaryIs");
        Element ring = outer != null ? childElement(outer, "LinearRing") : null;
        if (ring == null) {
            throw new IllegalArgumentException("Polygon has no outer boundary");
        }
        return polygonWkt(kmlCoordinates(ring));
    }

    private static String kmlCircle(Element point, double radius) {
        List<String> points = kmlCoordinates(point);
        if (points.size() != 1) {
            throw new IllegalArgumentException("Point requires exactly one coordinate");
        }
        return circleWkt(points.get(0), radius);
    }

    private static List<String> kmlCoordinates(Element geometry) {
        String text = childText(geometry, COORDINATES);
        List<String> points = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return points;
        }
        for (String tuple : text.split("\\s+")) {
            String[] values = tuple.split(",");
            if (values.length < 2) {
                throw new IllegalArgumentException("Invalid coordinate: " + tuple);
            }
            try {
                points.add(formatPoint(Double.parseDouble(values[1]), Double.parseDouble(values[0])));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid coordinate: " + tuple, e);
            }
        }
        return points;
    }

    // GeoJSON

    private void parseGeoJson(byte[] data, Collector collector) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid GeoJSON: " + e.getOriginalMessage(), e);
        }
        collector.setBaseName(root.path(NAME).asText(null));
        String type = root.path("type").asText();
        switch (type) {
            case "FeatureCollection" -> {
                JsonNode features = root.path("features");
                if (!features.isArray()) {
                    throw new IllegalArgumentException("Invalid GeoJSON: missing features");
                }
                for (JsonNode feature : features) {
                    parseGeoJsonFeature(feature, collector);
                }
            }
            case "Feature" -> parseGeoJsonFeature(root, collector);
            default -> {
                List<AreaSource> parts = new ArrayList<>();
                collectGeoJsonGeometries(root, 0, parts);
                collector.addFeature(null, null, parts);
            }
        }
    }

    private static String textProperty(JsonNode properties, String key) {
        JsonNode value = properties.get(key);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static void parseGeoJsonFeature(JsonNode feature, Collector collector) {
        JsonNode properties = feature.path("properties");
        String name = textProperty(properties, NAME);
        double radius = properties.path(RADIUS).asDouble(0);
        List<AreaSource> parts = new ArrayList<>();
        JsonNode geometry = feature.get("geometry");
        if (geometry != null && geometry.isObject()) {
            collectGeoJsonGeometries(geometry, radius, parts);
        }
        collector.addFeature(name, textProperty(properties, DESCRIPTION), parts);
    }

    private static void collectGeoJsonGeometries(JsonNode geometry, double radius, List<AreaSource> parts) {
        String type = geometry.path("type").asText();
        JsonNode coordinates = geometry.path(COORDINATES);
        switch (type) {
            case POLYGON -> parts.add(() -> geoJsonPolygon(coordinates));
            case LINE_STRING -> parts.add(() -> lineWkt(geoJsonPositions(coordinates)));
            case POINT -> parts.add(() -> circleWkt(geoJsonPosition(coordinates), radius));
            case "MultiPolygon" -> coordinates.forEach(item -> parts.add(() -> geoJsonPolygon(item)));
            case "MultiLineString" -> coordinates.forEach(item -> parts.add(() -> lineWkt(geoJsonPositions(item))));
            case "MultiPoint" -> coordinates.forEach(item -> parts.add(() -> circleWkt(geoJsonPosition(item), radius)));
            case "GeometryCollection" -> geometry.path("geometries").forEach(
                    item -> collectGeoJsonGeometries(item, radius, parts));
            default -> parts.add(() -> {
                throw new IllegalArgumentException("Unsupported geometry type: " + type);
            });
        }
    }

    private static String geoJsonPolygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new IllegalArgumentException("Polygon has no outer boundary");
        }
        if (rings.size() > 1) {
            return holesNotSupported();
        }
        return polygonWkt(geoJsonPositions(rings.get(0)));
    }

    private static List<String> geoJsonPositions(JsonNode positions) {
        if (!positions.isArray()) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        List<String> points = new ArrayList<>();
        for (JsonNode position : positions) {
            points.add(geoJsonPosition(position));
        }
        return points;
    }

    private static String geoJsonPosition(JsonNode position) {
        if (!position.isArray() || position.size() < 2
                || !position.get(0).isNumber() || !position.get(1).isNumber()) {
            throw new IllegalArgumentException("Invalid position: " + position);
        }
        return formatPoint(position.get(1).asDouble(), position.get(0).asDouble());
    }

}
