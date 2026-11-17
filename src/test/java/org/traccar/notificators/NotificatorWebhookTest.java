package org.traccar.notificators;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.session.cache.CacheManager;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificatorWebhookTest {

    private static final String URL = "https://example.com/hook";

    private Client client;
    private AsyncInvoker asyncInvoker;
    private NotificatorWebhook notificator;
    private Device device;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        WebTarget target = mock(WebTarget.class);
        Invocation.Builder builder = mock(Invocation.Builder.class);
        asyncInvoker = mock(AsyncInvoker.class);
        when(client.target(anyString())).thenReturn(target);
        when(target.request()).thenReturn(builder);
        when(builder.async()).thenReturn(asyncInvoker);

        device = new Device();
        device.setId(1);
        device.setName("Test device");
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(Device.class, 1)).thenReturn(device);

        notificator = new NotificatorWebhook(mock(NotificationFormatter.class), client, cacheManager);
    }

    private static User createUser(String url) {
        User user = new User();
        user.setId(2);
        if (url != null) {
            user.set(NotificatorWebhook.ATTRIBUTE_URL, url);
        }
        return user;
    }

    private static Event createEvent() {
        Event event = new Event(Event.TYPE_DEVICE_ONLINE, 1);
        event.setPositionId(3);
        return event;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<InvocationCallback<Response>> captureCallback(ArgumentCaptor<Entity<?>> entity) {
        ArgumentCaptor<InvocationCallback<Response>> callback = ArgumentCaptor.forClass(InvocationCallback.class);
        verify(asyncInvoker).post(entity.capture(), callback.capture());
        return callback;
    }

    private static Response createResponse(Response.Status status) {
        Response response = mock(Response.class);
        when(response.getStatusInfo()).thenReturn(status);
        when(response.getStatus()).thenReturn(status.getStatusCode());
        return response;
    }

    @Test
    void testSendPostsPayload() {
        Event event = createEvent();
        Position position = new Position();
        NotificationMessage message = new NotificationMessage("Subject", "Digest", "Body", false);

        CompletableFuture<Void> result = notificator.sendAsync(createUser(URL), message, event, position);

        verify(client).target(URL);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Entity<?>> entity = ArgumentCaptor.forClass(Entity.class);
        var callback = captureCallback(entity);

        NotificatorWebhook.Payload payload = (NotificatorWebhook.Payload) entity.getValue().getEntity();
        assertSame(event, payload.getEvent());
        assertSame(device, payload.getDevice());
        assertSame(position, payload.getPosition());
        assertEquals("Subject", payload.getSubject());
        assertEquals("Digest", payload.getMessage());

        assertFalse(result.isDone());
        callback.getValue().completed(createResponse(Response.Status.OK));
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void testErrorStatusIsHandled() {
        NotificationMessage message = new NotificationMessage("Subject", "Digest", "Body", false);

        CompletableFuture<Void> result = notificator.sendAsync(createUser(URL), message, createEvent(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Entity<?>> entity = ArgumentCaptor.forClass(Entity.class);
        captureCallback(entity).getValue().completed(createResponse(Response.Status.INTERNAL_SERVER_ERROR));
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void testConnectionFailureIsHandled() {
        NotificationMessage message = new NotificationMessage("Subject", "Digest", "Body", false);

        CompletableFuture<Void> result = notificator.sendAsync(createUser(URL), message, createEvent(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Entity<?>> entity = ArgumentCaptor.forClass(Entity.class);
        captureCallback(entity).getValue().failed(new ProcessingException("Connection refused"));
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void testMissingUrlSkipsDelivery() {
        NotificationMessage message = new NotificationMessage("Subject", "Digest", "Body", false);

        CompletableFuture<Void> result = notificator.sendAsync(createUser(null), message, createEvent(), null);

        assertTrue(result.isDone());
        verify(client, never()).target(anyString());
    }

    @Test
    void testInvalidUrlSkipsDelivery() {
        NotificationMessage message = new NotificationMessage("Subject", "Digest", "Body", false);

        CompletableFuture<Void> result = notificator.sendAsync(
                createUser("file:///etc/passwd"), message, createEvent(), null);

        assertTrue(result.isDone());
        verify(client, never()).target(anyString());
        verify(asyncInvoker, never()).post(any(), any(InvocationCallback.class));
    }

}
