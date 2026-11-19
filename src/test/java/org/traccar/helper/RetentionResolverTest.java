package org.traccar.helper;

import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.GroupedModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetentionResolverTest {

    private static Group group(long id, long parentId, Integer retentionDays) {
        Group group = new Group();
        group.setId(id);
        group.setGroupId(parentId);
        group.setRetentionDays(retentionDays);
        return group;
    }

    private static Device device(long groupId, Integer retentionDays) {
        Device device = new Device();
        device.setId(100);
        device.setGroupId(groupId);
        device.setRetentionDays(retentionDays);
        return device;
    }

    // 1 (90 days) -> 2 (inherits) -> 3 (inherits); 4 (365 days) -> 5 (30 days); 6 (none)
    private final RetentionResolver resolver = new RetentionResolver(List.of(
            group(1, 0, 90), group(2, 1, null), group(3, 2, null),
            group(4, 0, 365), group(5, 4, 30), group(6, 0, null)));

    @Test
    void testInheritanceThroughNestedGroups() {
        Group subgroup = resolver.apply(group(3, 2, null));
        assertEquals(90, subgroup.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_GROUP, subgroup.getRetentionSource());
        assertEquals(1, subgroup.getRetentionGroupId());

        Device device = resolver.apply(device(3, null));
        assertEquals(90, device.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_GROUP, device.getRetentionSource());
        assertEquals(1, device.getRetentionGroupId());
        assertEquals(90, resolver.resolve(device));
    }

    @Test
    void testSubgroupOverridesParent() {
        Group subgroup = resolver.apply(group(5, 4, 30));
        assertEquals(30, subgroup.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_OWN, subgroup.getRetentionSource());
        assertEquals(0, subgroup.getRetentionGroupId());

        Device device = resolver.apply(device(5, null));
        assertEquals(30, device.getEffectiveRetentionDays());
        assertEquals(5, device.getRetentionGroupId());
    }

    @Test
    void testDeviceOverride() {
        Device device = resolver.apply(device(3, 7));
        assertEquals(7, device.getEffectiveRetentionDays());
        assertEquals(GroupedModel.RETENTION_SOURCE_OWN, device.getRetentionSource());
        assertEquals(0, device.getRetentionGroupId());
        assertEquals(7, resolver.resolve(device));
    }

    @Test
    void testNoRetention() {
        for (Device device : List.of(device(0, null), device(6, null), device(999, null))) {
            resolver.apply(device);
            assertNull(device.getEffectiveRetentionDays());
            assertEquals(GroupedModel.RETENTION_SOURCE_NONE, device.getRetentionSource());
            assertNull(resolver.resolve(device));
        }
    }

    @Test
    void testCycleTerminates() {
        RetentionResolver cyclic = new RetentionResolver(List.of(group(1, 2, null), group(2, 1, null)));
        assertNull(cyclic.resolve(device(1, null)));
    }

    @Test
    void testValidate() {
        Device unset = device(0, null);
        Device positive = device(0, 1);
        Device zero = device(0, 0);
        Group negative = group(1, 0, -5);
        assertDoesNotThrow(() -> RetentionResolver.validate(unset));
        assertDoesNotThrow(() -> RetentionResolver.validate(positive));
        assertThrows(IllegalArgumentException.class, () -> RetentionResolver.validate(zero));
        assertThrows(IllegalArgumentException.class, () -> RetentionResolver.validate(negative));
    }

}
