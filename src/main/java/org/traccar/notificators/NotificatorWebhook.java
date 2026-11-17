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
package org.traccar.notificators;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.WebHelper;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.session.cache.CacheManager;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

@Singleton
public class NotificatorWebhook extends Notificator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificatorWebhook.class);

    public static final String ATTRIBUTE_URL = "webhookUrl";

    private final Client client;
    private final CacheManager cacheManager;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Payload {

        private Event event;
        private Device device;
        private Position position;
        private String subject;
        private String message;

        public Event getEvent() {
            return event;
        }

        public Device getDevice() {
            return device;
        }

        public Position getPosition() {
            return position;
        }

        public String getSubject() {
            return subject;
        }

        public String getMessage() {
            return message;
        }

    }

    @Inject
    public NotificatorWebhook(NotificationFormatter notificationFormatter, Client client, CacheManager cacheManager) {
        super(notificationFormatter);
        this.client = client;
        this.cacheManager = cacheManager;
    }

    private static boolean isValidUrl(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Void> sendAsync(
            User user, NotificationMessage shortMessage, Event event, Position position) {

        String url = user.getString(ATTRIBUTE_URL);
        if (url == null || url.isBlank()) {
            LOGGER.warn("Webhook URL is not set for user {}", user.getId());
            return CompletableFuture.completedFuture(null);
        }
        if (!isValidUrl(url)) {
            LOGGER.warn("Webhook URL is invalid for user {}", user.getId());
            return CompletableFuture.completedFuture(null);
        }

        Payload payload = new Payload();
        payload.event = event;
        payload.device = cacheManager.getObject(Device.class, event.getDeviceId());
        payload.position = position;
        payload.subject = shortMessage.subject();
        payload.message = shortMessage.digest();

        return WebHelper.post(client.target(url).request(), Entity.json(payload), response -> {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                LOGGER.warn("Webhook delivery for user {} failed with status {}",
                        user.getId(), response.getStatus());
            }
        }).exceptionally(throwable -> {
            LOGGER.warn("Webhook delivery for user {} failed", user.getId(), throwable);
            return null;
        });
    }

}
