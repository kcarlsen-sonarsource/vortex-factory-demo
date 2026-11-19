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
package org.traccar.helper;

import org.traccar.model.Group;
import org.traccar.model.GroupedModel;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the effective position history retention of devices and groups. An object uses its own
 * {@code retentionDays} when set, otherwise the value of the closest ancestor group that has one.
 */
public class RetentionResolver {

    private final Map<Long, Group> groups = new HashMap<>();

    public RetentionResolver(Collection<Group> groups) {
        groups.forEach(group -> this.groups.put(group.getId(), group));
    }

    public static RetentionResolver load(Storage storage) throws StorageException {
        return new RetentionResolver(storage.getObjects(Group.class, new Request(
                new Columns.Include("id", "groupId", "retentionDays"))));
    }

    public static void validate(GroupedModel object) {
        Integer retentionDays = object.getRetentionDays();
        if (retentionDays != null && retentionDays <= 0) {
            throw new IllegalArgumentException("Retention days must be a positive number");
        }
    }

    /**
     * Populates the effective retention fields of the object and returns it.
     */
    public <T extends GroupedModel> T apply(T object) {
        if (object.getRetentionDays() != null) {
            object.setEffectiveRetentionDays(object.getRetentionDays());
            object.setRetentionSource(GroupedModel.RETENTION_SOURCE_OWN);
            object.setRetentionGroupId(0);
        } else {
            Group group = findRetentionGroup(object);
            if (group != null) {
                object.setEffectiveRetentionDays(group.getRetentionDays());
                object.setRetentionSource(GroupedModel.RETENTION_SOURCE_GROUP);
                object.setRetentionGroupId(group.getId());
            } else {
                object.setEffectiveRetentionDays(null);
                object.setRetentionSource(GroupedModel.RETENTION_SOURCE_NONE);
                object.setRetentionGroupId(0);
            }
        }
        return object;
    }

    /**
     * Returns the effective retention in days, or {@code null} when history is kept indefinitely.
     */
    public Integer resolve(GroupedModel object) {
        if (object.getRetentionDays() != null) {
            return object.getRetentionDays();
        }
        Group group = findRetentionGroup(object);
        return group != null ? group.getRetentionDays() : null;
    }

    /**
     * Returns the closest ancestor group with its own retention value, or {@code null} if there is none.
     */
    private Group findRetentionGroup(GroupedModel object) {
        Set<Long> visited = new HashSet<>();
        long groupId = object.getGroupId();
        while (groupId > 0 && visited.add(groupId)) {
            Group group = groups.get(groupId);
            if (group == null) {
                return null;
            }
            if (group.getRetentionDays() != null) {
                return group;
            }
            groupId = group.getGroupId();
        }
        return null;
    }

}
