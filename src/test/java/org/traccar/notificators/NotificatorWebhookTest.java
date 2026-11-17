package org.traccar.notificators;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.session.cache.CacheManager;

import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificatorWebhookTest {

    private static final String URL = "https://example.com/hook";

    private Client client;
    private AsyncInvoker asyncInvoker;
    private CacheManager cacheManager;
    private NotificatorWebhook notificator;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        WebTarget target = mock(WebTarget.class);
        Invocation.Builder builder = mock(Invocation.Builder.class);
        asyncInvoker = mock(AsyncInvoker.class);
        when(client.target(any(URI.class))).thenReturn(target);
        when(target.request()).thenReturn(builder);
        when(builder.async()).thenReturn(asyncInvoker);
        cacheManager = mock(CacheManager.class);
        notificator = new NotificatorWebhook(mock(NotificationFormatter.class), client, cacheManager);
    }

    private static User createUser(String url) {
        User user = new User();
        if (url != null) {
            user.set(NotificatorWebhook.ATTRIBUTE_URL, url);
        }
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<InvocationCallback<Response>> captureCallback(ArgumentCaptor<Entity<?>> entityCaptor) {
        ArgumentCaptor<InvocationCallback<Response>> callbackCaptor = ArgumentCaptor.forClass(InvocationCallback.class);
        verify(asyncInvoker).post(entityCaptor.capture(), callbackCaptor.capture());
        return callbackCaptor;
    }

    private static Response createResponse(Response.Status status) {
        Response response = mock(Response.class);
        when(response.getStatusInfo()).thenReturn(status);
        when(response.getStatus()).thenReturn(status.getStatusCode());
        return response;
    }

    @Test
    void testPostsPayload() throws Exception {
        Event event = new Event("alarm", 1);
        Position position = new Position();
        Device device = new Device();
        device.setId(1);
        when(cacheManager.getObject(Device.class, 1)).thenReturn(device);

        var future = notificator.sendAsync(
                createUser(URL), new NotificationMessage("subject", "digest", "<b>body</b>", false), event, position);

        verify(client).target(URI.create(URL));
        ArgumentCaptor<Entity<?>> entityCaptor = ArgumentCaptor.captor();
        var callback = captureCallback(entityCaptor);

        var message = assertInstanceOf(NotificatorWebhook.Message.class, entityCaptor.getValue().getEntity());
        assertSame(event, message.getEvent());
        assertSame(device, message.getDevice());
        assertSame(position, message.getPosition());
        assertEquals("subject", message.getSubject());
        assertEquals("digest", message.getText());

        callback.getValue().completed(createResponse(Response.Status.OK));
        assertNull(future.get());
    }

    @Test
    void testHttpErrorFails() {
        var future = notificator.sendAsync(
                createUser(URL), new NotificationMessage("subject", "digest", null, false), null, null);

        var callback = captureCallback(ArgumentCaptor.captor());
        callback.getValue().completed(createResponse(Response.Status.INTERNAL_SERVER_ERROR));

        var exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(MessageException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("500"));
    }

    @Test
    void testConnectionFailureFails() {
        var future = notificator.sendAsync(
                createUser(URL), new NotificationMessage("subject", "digest", null, false), null, null);

        var callback = captureCallback(ArgumentCaptor.captor());
        var failure = new RuntimeException("connection refused");
        callback.getValue().failed(failure);

        var exception = assertThrows(ExecutionException.class, future::get);
        assertSame(failure, exception.getCause());
    }

    @Test
    void testMissingUrlFails() {
        assertInvalidUrl(null);
        assertInvalidUrl(" ");
    }

    @Test
    void testInvalidUrlFails() {
        assertInvalidUrl("ftp://example.com/hook");
        assertInvalidUrl("file:///etc/passwd");
        assertInvalidUrl("not a url");
    }

    private void assertInvalidUrl(String url) {
        CompletableFuture<Void> future = notificator.sendAsync(
                createUser(url), new NotificationMessage("subject", "digest", null, false), null, null);
        var exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(MessageException.class, exception.getCause());
        verify(client, never()).target(any(URI.class));
    }

}
