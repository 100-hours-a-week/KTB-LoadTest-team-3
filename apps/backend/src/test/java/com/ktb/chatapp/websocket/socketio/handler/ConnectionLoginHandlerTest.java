package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.DUPLICATE_LOGIN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations existingSocketRoom;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                roomLeaveHandler,
                new SimpleMeterRegistry());
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        UUID sessionId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", sessionId.toString());
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));
        when(client.getSessionId()).thenReturn(sessionId);

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list", "socket:" + sessionId));
    }

    @Test
    void onConnect_withExistingSessionOnAnotherNode_broadcastsToThatSocketsPrivateRoom() {
        UUID newSessionId = UUID.randomUUID();
        String previousSocketId = UUID.randomUUID().toString();
        SocketUser newUser = new SocketUser("user-1", "tester", "session-2", newSessionId.toString());
        SocketUser previousSocketUser = new SocketUser("user-1", "tester", "session-1", previousSocketId);

        HandshakeData handshakeData = handshakeDataStub();

        when(connectedUsers.get(newUser.id())).thenReturn(previousSocketUser);
        when(client.get("user")).thenReturn(newUser);
        when(userRooms.get(newUser.id())).thenReturn(Set.of());
        when(client.getSessionId()).thenReturn(newSessionId);
        when(client.getHandshakeData()).thenReturn(handshakeData);
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress(0));
        when(socketIOServer.getRoomOperations("socket:" + previousSocketId)).thenReturn(existingSocketRoom);

        handler.onConnect(client, newUser);

        verify(existingSocketRoom).sendEvent(eq(DUPLICATE_LOGIN), any());
    }

    private HandshakeData handshakeDataStub() {
        HandshakeData handshakeData = mock(HandshakeData.class);
        var headers = new io.netty.handler.codec.http.DefaultHttpHeaders();
        headers.set("User-Agent", "junit-test-agent");
        when(handshakeData.getHttpHeaders()).thenReturn(headers);
        return handshakeData;
    }

    @Test
    void onDisconnect_removesCurrentConnectionAndLeavesRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(roomLeaveHandler).handleLeaveRoom(client, "room-1");
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list", "socket:" + socketId));
        verify(client).del("user");
        verify(client).disconnect();
    }
}
