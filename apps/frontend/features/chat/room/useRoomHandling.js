import { useRef, useEffect, useCallback } from 'react';
import socketClient from '@/lib/socket/socketClient';
import { useAuth } from '@/contexts/AuthContext';
import { Toast } from '@/components/Toast';
import api, { getAuthHeaders } from '@/lib/api/client';
import {
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from './roomEventHandlers';

export const useRoomHandling = ({
  roomId,
  route,
  state,
  refs,
  actions,
  cleanup,
  handleReactionUpdate,
}) => {
  const { onReplace, asPath } = route;
  const { currentUser } = state;
  const {
    socketRef,
    attachSocket,
    mountedRef,
    initializingRef,
    setupCompleteRef,
    userRooms,
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
  } = refs;
  const {
    setRoom,
    setError,
    setMessages,
    setHasMoreMessages,
    setLoadingMessages,
    setupStarted,
    setupSucceeded,
    setupFailed,
  } = actions;
  const { user, refreshToken, logout } = useAuth();
  const setupPromiseRef = useRef(null);
  const roomEventsUnsubscribeRef = useRef(null);
  const MAX_SOCKET_RECONNECT_ATTEMPTS = 3;
  const MAX_MESSAGE_RETRY_ATTEMPTS = 3;
  const MESSAGE_TIMEOUT = 5000;
  const MESSAGE_RETRY_DELAY = 2000;

  const processMessages = useCallback(
    (loadedMessages, hasMore, isInitialLoad = false) => {
      processLoadedRoomMessages({
        loadedMessages,
        hasMore,
        isInitialLoad,
        processedMessageIds,
        setMessages,
        setHasMoreMessages,
        initialLoadCompletedRef,
      });
    },
    [
      processedMessageIds,
      setMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    ]
  );

  const setupEventListeners = useCallback(() => {
    if (!socketRef.current || !mountedRef.current) return;

    if (roomEventsUnsubscribeRef.current) {
      roomEventsUnsubscribeRef.current();
      roomEventsUnsubscribeRef.current = null;
    }

    roomEventsUnsubscribeRef.current = socketClient.subscribeRoomEvents(
      socketRef.current,
      createRoomEventHandlers({
        mountedRef,
        messageProcessingRef,
        processedMessageIds,
        initialLoadCompletedRef,
        processMessages,
        setRoom,
        setMessages,
        setLoadingMessages,
        setError,
        setHasMoreMessages,
        cleanup,
        logout,
        onReplace,
        handleReactionUpdate,
        showRejectedMessage: Toast.error.bind(Toast),
      })
    );
  }, [
    processMessages,
    setHasMoreMessages,
    cleanup,
    handleReactionUpdate,
    setLoadingMessages,
    setError,
    logout,
    socketRef,
    mountedRef,
    messageProcessingRef,
    processedMessageIds,
    initialLoadCompletedRef,
    setRoom,
    setMessages,
    onReplace,
  ]);

  const handleSessionError = useCallback(async () => {
    try {
      if (!user) {
        throw new Error('No user session found');
      }

      await refreshToken();
      if (mountedRef.current) {
        return true;
      }
    } catch (error) {}

    if (mountedRef.current) {
      await logout();
      onReplace('/?redirect=' + asPath);
    }
    return false;
  }, [user, refreshToken, mountedRef, logout, onReplace, asPath]);

  const setupSocket = useCallback(async () => {
    try {
      if (!user?.token || !user?.sessionId) {
        throw new Error('Invalid authentication state');
      }

      if (socketRef.current?.connected) {
        return socketRef.current;
      }

      if (socketRef.current) {
        const currentSocket = socketRef.current;

        if (userRooms.current?.get(currentSocket.id)) {
          await new Promise((resolve) => {
            socketClient.leaveRoom(
              userRooms.current.get(currentSocket.id),
              currentSocket
            );
            setTimeout(resolve, 1000);
          });
          userRooms.current.delete(currentSocket.id);
        }

        currentSocket.disconnect();
        currentSocket.removeAllListeners();
        attachSocket(null);

        await new Promise((resolve) => setTimeout(resolve, 2000));
      }

      const socket = await socketClient.connect({
        auth: {
          token: user.token,
          sessionId: user.sessionId,
        },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: MAX_SOCKET_RECONNECT_ATTEMPTS,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 3000,
        timeout: 10000,
        pingTimeout: 10000,
        pingInterval: 8000,
        forceNew: true,
        autoConnect: true,
      });

      return socket;
    } catch (error) {
      if (error.message === 'Invalid authentication state') {
        onReplace('/?error=auth_required');
      }
      throw error;
    }
  }, [userRooms, onReplace, socketRef, attachSocket, user]);

  const fetchRoomData = useCallback(
    async (roomId) => {
      try {
        if (!user?.token || !user?.sessionId) {
          await handleSessionError();
          throw new Error('인증 정보가 유효하지 않습니다.');
        }

        if (!roomId || !mountedRef.current) {
          throw new Error('채팅방 정보가 올바르지 않습니다.');
        }

        let response;
        try {
          response = await api.get(`/api/rooms/${roomId}`, {
            handleAuthError: false,
            headers: getAuthHeaders(user),
          });
        } catch (error) {
          if (error.response?.status === 401) {
            const refreshed = await handleSessionError();
            if (refreshed && mountedRef.current) {
              return fetchRoomData(roomId);
            }
            throw new Error('인증이 만료되었습니다.');
          }
          throw error;
        }

        const data = response.data;
        if (!data.success || !data.data) {
          throw new Error('채팅방 데이터가 올바르지 않습니다.');
        }

        return data.data;
      } catch (error) {
        throw error;
      }
    },
    [user, mountedRef, handleSessionError]
  );

  const joinRoom = useCallback(
    async (roomId) => {
      if (!roomId || !mountedRef.current) {
        throw new Error('잘못된 채팅방 정보입니다.');
      }

      const socket = socketRef.current;
      if (!socket?.connected) {
        throw new Error('Socket not connected');
      }

      const data = await socketClient.joinRoomAndWait(roomId, socket);
      userRooms.current?.set(socket.id, roomId);
      return data;
    },
    [socketRef, mountedRef, userRooms]
  );

  const rejoinRoom = useCallback(async () => {
    const socket = socketRef.current;
    if (!roomId || !mountedRef.current || !socket?.connected) {
      return;
    }

    const joinResult = await joinRoom(roomId);

    if (Array.isArray(joinResult?.messages)) {
      processMessages(joinResult.messages, joinResult.hasMore, true);
    }

    if (mountedRef.current) {
      setupCompleteRef.current = true;
    }
  }, [
    roomId,
    socketRef,
    mountedRef,
    setupCompleteRef,
    joinRoom,
    processMessages,
  ]);

  const loadInitialMessages = useCallback(
    async (roomId) => {
      const loadMessagesWithRetry = async (retryCount = 0) => {
        const socket = socketRef.current;
        if (!socket?.connected) {
          throw new Error('Socket not connected');
        }

        try {
          const response = await socketClient.fetchPreviousMessagesAndWait(
            { roomId, limit: 30 },
            socket,
            { timeoutMs: MESSAGE_TIMEOUT }
          );

          if (!response || !Array.isArray(response.messages)) {
            throw new Error('잘못된 메시지 응답 형식입니다.');
          }

          processMessages(response.messages, response.hasMore, true);
          return response;
        } catch (error) {
          if (retryCount < MAX_MESSAGE_RETRY_ATTEMPTS) {
            await new Promise((resolve) =>
              setTimeout(resolve, MESSAGE_RETRY_DELAY)
            );
            return loadMessagesWithRetry(retryCount + 1);
          }

          throw error;
        }
      };

      try {
        return await loadMessagesWithRetry();
      } catch (error) {
        if (!socketRef.current?.connected) {
          attachSocket(await setupSocket());
          return loadMessagesWithRetry();
        }
        throw error;
      }
    },
    [socketRef, attachSocket, processMessages, setupSocket]
  );

  const setupRoom = useCallback(async () => {
    if (setupPromiseRef.current) {
      return setupPromiseRef.current;
    }

    setupPromiseRef.current = (async () => {
      try {
        initializingRef.current = true;
        setupStarted();
        // 1. Socket Setup
        attachSocket(await setupSocket());

        // 2. Fetch Room Data
        const roomData = await fetchRoomData(roomId);

        // Ensure current user is included in participants for display
        if (currentUser && roomData.participants) {
          const isUserInParticipants = roomData.participants.some(
            (p) => p._id === currentUser.id || p.id === currentUser.id
          );

          if (!isUserInParticipants) {
            roomData.participants = [
              ...roomData.participants,
              {
                _id: currentUser.id,
                id: currentUser.id,
                name: currentUser.name,
                email: currentUser.email,
              },
            ];
          }
        }

        // 3. Setup Event Listeners
        if (mountedRef.current) {
          setupEventListeners();
        }

        // 4. Join Room and Load Messages
        if (mountedRef.current && socketRef.current?.connected) {
          const joinResult = await joinRoom(roomId);

          if (Array.isArray(joinResult?.messages)) {
            processMessages(joinResult.messages, joinResult.hasMore, true);
          } else {
            await loadInitialMessages(roomId);
          }
        }

        if (mountedRef.current) {
          setupCompleteRef.current = true;
          setupSucceeded(roomData);
        }
      } catch (error) {
        if (mountedRef.current) {
          const errorMessage = error.message.includes('시간 초과')
            ? '채팅방 연결 시간이 초과되었습니다.'
            : error.message || '채팅방 연결에 실패했습니다.';

          setupFailed(errorMessage);
          cleanup('ERROR');

          if (socketRef.current) {
            socketRef.current.disconnect();
            attachSocket(null);
          }
        }

        throw error;
      } finally {
        if (mountedRef.current) {
          initializingRef.current = false;
        }

        setupPromiseRef.current = null;
      }
    })();

    return setupPromiseRef.current;
  }, [
    roomId,
    socketRef,
    attachSocket,
    mountedRef,
    setupSocket,
    fetchRoomData,
    joinRoom,
    loadInitialMessages,
    processMessages,
    cleanup,
    setupEventListeners,
    setupStarted,
    setupSucceeded,
    setupFailed,
    currentUser,
    initializingRef,
    setupCompleteRef,
  ]);

  useEffect(() => {
    return () => {
      setupPromiseRef.current = null;
      initializingRef.current = false;
      setupCompleteRef.current = false;

      if (roomEventsUnsubscribeRef.current) {
        roomEventsUnsubscribeRef.current();
        roomEventsUnsubscribeRef.current = null;
      }
    };
  }, []);

  return {
    setupRoom,
    rejoinRoom,
    loadInitialMessages,
  };
};

export default useRoomHandling;
