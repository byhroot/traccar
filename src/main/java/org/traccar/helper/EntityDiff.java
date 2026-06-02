package org.traccar.helper;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class EntityDiff {

    private EntityDiff() {
    }

    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            "hashedPassword", "salt", "notificationTokens");

    public static Map<String, String> compute(Object before, Object after) {
        Map<String, String> diff = new LinkedHashMap<>();
        if (before == null || after == null)
            return diff;

        for (Method method : before.getClass().getMethods()) {
            String name = method.getName();
            if ((!name.startsWith("get") && !name.startsWith("is"))
                    || method.getParameterCount() != 0
                    || name.equals("getClass")
                    || name.equals("getId")) {
                continue;
            }

            String field = fieldNameFrom(name);
            if (EXCLUDED_FIELDS.contains(field))
                continue;

            try {
                Object oldVal = method.invoke(before);
                Object newVal = method.invoke(after);

                boolean changed = (oldVal == null) ? newVal != null : !oldVal.equals(newVal);
                if (!changed)
                    continue;

                // ► Map ise içini karşılaştır
                if (oldVal instanceof Map<?, ?> oldMap && newVal instanceof Map<?, ?> newMap) {
                    diffMaps(field, oldMap, newMap, diff);
                } else {
                    diff.put(field, oldVal + " -> " + newVal);
                }

            } catch (Exception ignored) {
            }
        }
        return diff;
    }

    private static void diffMaps(String prefix, Map<?, ?> oldMap, Map<?, ?> newMap, Map<String, String> diff) {
        for (Map.Entry<?, ?> entry : oldMap.entrySet()) {
            String key = prefix + "." + entry.getKey();
            Object newVal = newMap.get(entry.getKey());
            if (newVal == null) {
                diff.put(key, entry.getValue() + " -> (silindi)");
            } else if (!entry.getValue().equals(newVal)) {
                diff.put(key, entry.getValue() + " -> " + newVal);
            }
        }
        for (Map.Entry<?, ?> entry : newMap.entrySet()) {
            if (!oldMap.containsKey(entry.getKey())) {
                diff.put(prefix + "." + entry.getKey(), "(yeni) -> " + entry.getValue());
            }
        }
    }

    private static String fieldNameFrom(String methodName) {
        int start = methodName.startsWith("is") ? 2 : 3;
        return Character.toLowerCase(methodName.charAt(start)) + methodName.substring(start + 1);
    }
}