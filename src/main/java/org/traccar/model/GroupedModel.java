/*
 * Copyright 2018 Anton Tananaev (anton@traccar.org)
 * Copyright 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.model;

import org.traccar.storage.QueryIgnore;

public class GroupedModel extends ExtendedModel {

    public static final String RETENTION_SOURCE_OWN = "own";
    public static final String RETENTION_SOURCE_GROUP = "group";
    public static final String RETENTION_SOURCE_NONE = "none";

    private long groupId;

    public long getGroupId() {
        return groupId;
    }

    public void setGroupId(long groupId) {
        this.groupId = groupId;
    }

    private Integer retentionDays;

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    private Integer effectiveRetentionDays;

    @QueryIgnore
    public Integer getEffectiveRetentionDays() {
        return effectiveRetentionDays;
    }

    public void setEffectiveRetentionDays(Integer effectiveRetentionDays) {
        this.effectiveRetentionDays = effectiveRetentionDays;
    }

    private String retentionSource;

    @QueryIgnore
    public String getRetentionSource() {
        return retentionSource;
    }

    public void setRetentionSource(String retentionSource) {
        this.retentionSource = retentionSource;
    }

    private long retentionGroupId;

    @QueryIgnore
    public long getRetentionGroupId() {
        return retentionGroupId;
    }

    public void setRetentionGroupId(long retentionGroupId) {
        this.retentionGroupId = retentionGroupId;
    }

}
