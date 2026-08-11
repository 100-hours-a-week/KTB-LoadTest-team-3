import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

const CONNECTION_STATUS = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useRoomsSocket = ({
  currentUser,
  setConnectionStatus,
  setRooms,
}) => {
  const socketRef = useRef(null);
  const handlersRef = useRef(null);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isSubscribed = true;

    const connectSocket = async () => {
      try {
        const socket = await socketClient
          .connect({
            auth: {
              token: currentUser.token,
              sessionId: currentUser.sessionId,
            },
          })
          .catch((err) => {
            console.log('Socket connection error:', err);
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          });

        if (!isSubscribed || !socket) return;

        socketRef.current = socket;

        const handlers = {
          connect: () => {
            setConnectionStatus(CONNECTION_STATUS.CONNECTED);
          },
          disconnect: () => {
            setConnectionStatus(CONNECTION_STATUS.DISCONNECTED);
          },
          error: () => {
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          },
          roomCreated: (newRoom) => {
            setRooms((prev) => [newRoom, ...prev]);
          },
          roomUpdated: (updatedRoom) => {
            setRooms((prev) =>
              prev.map((room) =>
                room._id === updatedRoom._id ? updatedRoom : room
              )
            );
          },
          // 활성도 지표만 담긴 경량 payload이므로 방 정보를 덮지 않고 병합한다
          roomActivity: (activity) => {
            if (!activity?._id) return;

            setRooms((prev) =>
              prev.map((room) =>
                room._id === activity._id
                  ? { ...room, recentMessageCount: activity.recentMessageCount }
                  : room
              )
            );
          },
        };

        handlersRef.current = handlers;

        Object.entries(handlers).forEach(([event, handler]) => {
          socket.on(event, handler);
        });

        // 방 화면에서 이미 연결돼 있던 소켓을 그대로 이어받는 경우 'connect'
        // 이벤트는 다시 발생하지 않는다 — 현재 연결 상태를 즉시 반영한다.
        setConnectionStatus(
          socket.connected
            ? CONNECTION_STATUS.CONNECTED
            : CONNECTION_STATUS.DISCONNECTED
        );
      } catch (error) {
        if (!isSubscribed) return;

        if (
          error.message?.includes('Authentication required') ||
          error.message?.includes('Invalid session')
        ) {
          // Auth error will be handled by the useAuth context
        }

        setConnectionStatus(CONNECTION_STATUS.ERROR);
      }
    };

    connectSocket();

    return () => {
      isSubscribed = false;

      if (socketRef.current && handlersRef.current) {
        Object.entries(handlersRef.current).forEach(([event, handler]) => {
          socketRef.current.off(event, handler);
        });
      }
      socketRef.current = null;
      handlersRef.current = null;
    };
  }, [currentUser]); // eslint-disable-line react-hooks/exhaustive-deps

  return { socketRef };
};

export default useRoomsSocket;
