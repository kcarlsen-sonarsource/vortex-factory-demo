package org.traccar.api.resource;

import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Permission;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-memory storage holding objects of a single user. Supports the conditions used by the resource tests.
 */
final class ResourceTestStorage extends Storage {

    private final Map<Class<?>, List<Object>> objects = new HashMap<>();
    private final Map<Class<?>, Set<Long>> linked = new HashMap<>();
    private long nextId = 100;

    static void inject(Object target, Class<?> type, Object value) throws IllegalAccessException {
        for (Class<?> clazz = target.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType().equals(type)) {
                    field.setAccessible(true);
                    field.set(target, value);
                }
            }
        }
    }

    void put(Object object, boolean link) {
        objects.computeIfAbsent(object.getClass(), k -> new ArrayList<>()).add(object);
        if (link) {
            linked.computeIfAbsent(object.getClass(), k -> new HashSet<>()).add(((BaseModel) object).getId());
        }
    }

    private boolean matches(Object object, Condition condition) {
        if (condition == null) {
            return true;
        } else if (condition instanceof Condition.And and) {
            return matches(object, and.getFirst()) && matches(object, and.getSecond());
        } else if (condition instanceof Condition.Equals equals) {
            Object value = switch (equals.getColumn()) {
                case "id" -> ((BaseModel) object).getId();
                case "uniqueId" -> ((Device) object).getUniqueId();
                default -> throw new UnsupportedOperationException(equals.getColumn());
            };
            return equals.getValue().equals(value);
        } else if (condition instanceof Condition.Permission permission) {
            return linked.getOrDefault(permission.getPropertyClass(), Set.of())
                    .contains(((BaseModel) object).getId());
        }
        throw new UnsupportedOperationException(condition.getClass().getName());
    }

    @Override
    public <T> List<T> getObjects(Class<T> clazz, Request request) {
        return getObjectsStream(clazz, request).toList();
    }

    @Override
    public <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) {
        return objects.getOrDefault(clazz, List.of()).stream()
                .filter(object -> matches(object, request.getCondition()))
                .map(clazz::cast);
    }

    @Override
    public <T> long addObject(T entity, Request request) {
        long id = nextId++;
        ((BaseModel) entity).setId(id);
        put(entity, false);
        return id;
    }

    @Override
    public <T> void updateObject(T entity, Request request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeObject(Class<?> clazz, Request request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Permission> getPermissions(
            Class<? extends BaseModel> ownerClass, long ownerId,
            Class<? extends BaseModel> propertyClass, long propertyId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addPermission(Permission permission) {
        linked.computeIfAbsent(permission.getPropertyClass(), k -> new HashSet<>()).add(permission.getPropertyId());
    }

    @Override
    public void removePermission(Permission permission) {
        throw new UnsupportedOperationException();
    }

}
