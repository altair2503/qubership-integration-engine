/*
 * Copyright 2024-2025 NetCracker Technology Corporation
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

package org.qubership.integration.platform.engine.mapper.atlasmap.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import io.atlasmap.json.core.JsonComplexTypeFactory;
import io.atlasmap.json.inspect.JsonInspectionException;
import io.atlasmap.json.inspect.JsonInspector;
import io.atlasmap.json.v2.*;
import io.atlasmap.v2.CollectionType;
import io.atlasmap.v2.FieldStatus;
import io.atlasmap.v2.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;

import static java.util.Objects.isNull;

/**
 * JSON instance document inspector. It consumes JSON instance document as an example
 * and build a AtlasMap Document model object from it.
 */
public class QipJsonInstanceInspector implements JsonInspector {


    private static final Logger LOG = LoggerFactory.getLogger(QipJsonInstanceInspector.class);

    public QipJsonInstanceInspector() {
    }

    public JsonDocument inspect(String instance) throws JsonInspectionException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Start JSON instance inspection: {}", instance);
        }
        if (instance == null || instance.isEmpty()) {
            throw new IllegalArgumentException("JSON instance cannot be null");
        }
        try {
            JsonDocument jsonDocument = AtlasJsonModelFactory.createJsonDocument();
            ObjectMapper objectMapper = new ObjectMapper()
                .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES);

            JsonNode rootNode = objectMapper.readTree(instance);
            if (rootNode.isObject()) {
                Iterator<Entry<String, JsonNode>> fields = rootNode.fields();
                while (fields.hasNext()) {
                    Entry<String, JsonNode> e = fields.next();
                    String key = e.getKey();
                    JsonNode node = e.getValue();
                    if (node.isObject()) {
                        handleObjectNode(jsonDocument, null, key, (ObjectNode) node, false);
                    } else if (node.isArray()) {
                        handleArrayNode(jsonDocument, null, key, (ArrayNode) node);
                    } else {
                        createChildJsonField(jsonDocument, null, key, (ValueNode) node, false);
                    }
                }
            } else if (rootNode.isArray()) {
                handleArrayNode(jsonDocument, null, "", (ArrayNode) rootNode);
            } else {
                throw new IllegalArgumentException("JSON root must be object or array");
            }
            return jsonDocument;
        } catch (IOException e) {
            throw new JsonInspectionException(e);
        }
    }

    private JsonComplexType handleObjectNode(JsonDocument rootDocument, JsonComplexType parent, String key, ObjectNode objectNode, boolean isArray) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Handling object node: {}", objectNode);
        }
        JsonComplexType complexType = createChildJsonComplexType(rootDocument, parent, key, isArray);
        Iterator<Entry<String, JsonNode>> subFields = objectNode.fields();
        while (subFields.hasNext()) {
            Entry<String, JsonNode> e = subFields.next();
            String subKey = e.getKey();
            JsonNode subNode = e.getValue();
            if (subNode.isObject()) {
                handleObjectNode(rootDocument, complexType, subKey, (ObjectNode) subNode, false);
            } else if (subNode.isArray()) {
                handleArrayNode(rootDocument, complexType, subKey, (ArrayNode) subNode);
            } else {
                createChildJsonField(rootDocument, complexType, subKey, (ValueNode) subNode, false);
            }
        }
        return complexType;
    }

    private void handleArrayNode(JsonDocument rootDocument, JsonComplexType parent, String key, ArrayNode arrayNode) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Handling array node: {}", arrayNode);
        }
        if (arrayNode.isEmpty()) {
            LOG.warn("Ignoring empty JSON array: {}", arrayNode);
            return;
        }
        JsonComplexType previous = null;
        for (int i = 0; i < arrayNode.size(); ++i) {
            JsonNode sample = arrayNode.get(i);
            if (sample.isObject()) {
                JsonComplexType node = handleObjectNode(rootDocument, parent, key, (ObjectNode) sample, true);
                if (isNull(previous)) {
                    previous = node;
                } else {
                    previous.getJsonFields().getJsonField().addAll(node.getJsonFields().getJsonField());
                }
            } else if (sample.isArray()) {
                throw new IllegalArgumentException("Nested JSON array is not supported");
            } else {
                createChildJsonField(rootDocument, parent, key, (ValueNode) sample, true);
            }
        }
    }

    private JsonComplexType createChildJsonComplexType(JsonDocument rootDocument, JsonComplexType parent, String key, boolean isArray) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Creating JSON complex type (array:{}): {}", isArray, key);
        }
        JsonComplexType jsonComplexType = JsonComplexTypeFactory.createJsonComlexField();
        jsonComplexType.setJsonFields(new JsonFields());
        jsonComplexType.setName(key);
        jsonComplexType.setStatus(FieldStatus.SUPPORTED);
        String path = (parent != null ? parent.getPath() : "").concat("/").concat(key != null ? key : "");
        if (isArray) {
            // JSON array is a list in AtlasMap
            path = path.concat("<>");
            jsonComplexType.setCollectionType(CollectionType.LIST);
        }
        jsonComplexType.setPath(path);
        if (parent != null) {
            parent.getJsonFields().getJsonField().add(jsonComplexType);
        } else {
            rootDocument.getFields().getField().add(jsonComplexType);
        }
        return jsonComplexType;
    }

    private JsonField createChildJsonField(JsonDocument rootDocument, JsonComplexType parent, String key, ValueNode valueNode, boolean isArray) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Creating JSON field (array:{}): {}", isArray, key);
        }
        JsonField field = AtlasJsonModelFactory.createJsonField();
        field.setName(key);
        String path = (parent != null ? parent.getPath() : "").concat("/").concat(key != null ? key : "");
        if (isArray) {
            // JSON array is a list in AtlasMap
            path = path.concat("<>");
            field.setCollectionType(CollectionType.LIST);
        }
        field.setPath(path);
        if (parent != null) {
            parent.getJsonFields().getJsonField().add(field);
        } else {
            rootDocument.getFields().getField().add(field);
        }

        LOG.trace("VALUE IS A " + valueNode.getNodeType().name());
        if (valueNode.isNumber()) {
            if (valueNode.isInt()) {
                field.setFieldType(FieldType.INTEGER);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.intValue());
            } else if (valueNode.isBigInteger()) {
                field.setFieldType(FieldType.BIG_INTEGER);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.bigIntegerValue());
            } else if (valueNode.isFloat()) {
                field.setFieldType(FieldType.FLOAT);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.floatValue());
            } else if (valueNode.isDouble()) {
                field.setFieldType(FieldType.DOUBLE);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.asDouble());
            } else if (valueNode.isBigDecimal()) {
                field.setFieldType(FieldType.DECIMAL);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.decimalValue());
            } else if (valueNode.isShort()) {
                field.setFieldType(FieldType.SHORT);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.shortValue());
            } else if (valueNode.isLong()) {
                field.setFieldType(FieldType.LONG);
                field.setStatus(FieldStatus.SUPPORTED);
                field.setValue(valueNode.longValue());
            }
        } else if (valueNode.isTextual()) {
            field.setFieldType(FieldType.STRING);
            field.setStatus(FieldStatus.SUPPORTED);
            field.setValue(valueNode.textValue());
        } else if (valueNode.isBoolean()) {
            field.setFieldType(FieldType.BOOLEAN);
            field.setStatus(FieldStatus.SUPPORTED);
            field.setValue(valueNode.booleanValue());
        } else if (valueNode.isBinary() || valueNode.isPojo()) {
            field.setFieldType(FieldType.UNSUPPORTED);
            field.setStatus(FieldStatus.UNSUPPORTED);
        }
        return field;
    }

}
