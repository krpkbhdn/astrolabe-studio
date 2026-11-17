package com.github.krpkbhdn.ccmanager.overlay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deep merge of two settings objects: objects merge key by key, arrays are united (winner first, no duplicates), any other value comes from the winner. */
public final class SettingsMerge {

    private SettingsMerge() {}

    public static ObjectNode deep(ObjectNode winner, ObjectNode loser) {
        var result = winner.deepCopy();
        loser.properties().forEach(entry -> {
            var key = entry.getKey();
            var current = result.get(key);
            var other = entry.getValue();
            if (current == null) {
                result.set(key, other.deepCopy());
            } else if (current instanceof ObjectNode currentObject && other instanceof ObjectNode otherObject) {
                result.set(key, deep(currentObject, otherObject));
            } else if (current instanceof ArrayNode currentArray && other instanceof ArrayNode otherArray) {
                otherArray.forEach(item -> addIfMissing(currentArray, item));
            }
        });
        return result;
    }

    private static void addIfMissing(ArrayNode array, JsonNode item) {
        for (JsonNode existing : array) {
            if (existing.equals(item)) {
                return;
            }
        }
        array.add(item.deepCopy());
    }
}
