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
import com.fasterxml.jackson.annotation.JsonProperty;
import org.traccar.helper.WebHelper;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
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

    public static final String ATTRIBUTE_URL = "webhookUrl";

    private final Client client;
    private final CacheManager cacheManager;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        @JsonProperty("event")
        private Event event;
        @JsonProperty("device")
        private Device device;
        @JsonProperty("position")
        private Position position;
        @JsonProperty("subject")
        private String subject;
        @JsonProperty("message")
        private String text;

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

        public String getText() {
            return text;
        }
    }

    @Inject
    public NotificatorWebhook(NotificationFormatter notificationFormatter, Client client, CacheManager cacheManager) {
        super(notificationFormatter);
        this.client = client;
        this.cacheManager = cacheManager;
    }

    static URI parseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new MessageException("Webhook URL is not configured");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new MessageException(e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new MessageException("Unsupported webhook URL");
        }
        return uri;
    }

    @Override
    public CompletableFuture<Void> sendAsync(
            User user, NotificationMessage shortMessage, Event event, Position position) {

        URI uri;
        try {
            uri = parseUrl(user.getString(ATTRIBUTE_URL));
        } catch (MessageException e) {
            return CompletableFuture.failedFuture(e);
        }

        Message message = new Message();
        message.event = event;
        if (event != null && event.getDeviceId() != 0) {
            message.device = cacheManager.getObject(Device.class, event.getDeviceId());
        }
        message.position = position;
        message.subject = shortMessage.subject();
        message.text = shortMessage.digest();

        return WebHelper.post(client.target(uri).request(), Entity.json(message), response -> {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new MessageException("Webhook delivery failed with HTTP code " + response.getStatus());
            }
        });
    }

}
