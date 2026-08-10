import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

const ROOM_PAGE_SIZE = 50; // 백엔드 룸컨트롤러 사이즈 기본값

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
  isRetrying,
  attemptConnection,
}) => {
  const [rooms, setRooms] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoom, setJoiningRoom] = useState(false);

  const isLoadingRef = useRef(false);
  const pageRef = useRef(0);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = !isRetrying;

    if (error.message === 'AUTH_EXPIRED') {
      errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
      errorType = 'danger';
      showRetry = false;

      setError({
        title: '인증 만료',
        message: errorMessage,
        type: errorType,
        showRetry,
      });

      setConnectionStatus(CONNECTION_STATUS.ERROR);
      return;
    }

    if (error.message === 'SERVER_UNREACHABLE') {
      errorMessage = '서버와 연결할 수 없습니다. 다시 시도해주세요.';
      errorType = 'warning';
      showRetry = true;
    }

    setError({
      title: '채팅방 목록 로드 실패',
      message: errorMessage,
      type: errorType,
      showRetry,
    });

    setConnectionStatus(CONNECTION_STATUS.ERROR);
  }, [isRetrying, setConnectionStatus]);

  const loadRoomsPage = useCallback(async (page, size) => {
    await attemptConnection();

    const response = await axiosInstance.get('/api/rooms', { params: { page, size } });

    if (!response?.data?.data) {
      throw new Error('INVALID_RESPONSE');
    }

    return response.data; // {data, metadata} 반환
  }, [attemptConnection]);

  const fetchRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current) {
      return;
    }

    try {
      isLoadingRef.current = true;

      setLoading(true);
      setError(null);

      const { data, metadata } = await loadRoomsPage(0, ROOM_PAGE_SIZE);
      pageRef.current = 0;
      setRooms(data);
      setHasMore(Boolean(metadata?.hasMore));

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }
    } catch (error) {
      handleFetchError(error);
    } finally {
      setLoading(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, isInitialLoad, loadRoomsPage, handleFetchError]);


  const loadMoreRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current || !hasMore) {
      return;
    }
    try {
      isLoadingRef.current = true;
      setLoadingMore(true);

      const nextPage = pageRef.current + 1;
      const { data, metadata } = await loadRoomsPage(nextPage, ROOM_PAGE_SIZE);
      
      pageRef.current = nextPage;
      
      setRooms((prev) => [...prev, ...data]);
      setHasMore(Boolean(metadata?.hasMore));
    } catch (error) {
      handleFetchError(error);
    } finally {
      setLoadingMore(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, hasMore, loadRoomsPage, handleFetchError]);
 

  /**
   * 이미 그려진 목록을 유지한 채 다시 조회한다.
   * 자동 갱신(silent)은 실패해도 화면을 흔들지 않고 다음 주기를 기다린다.
   */
  const refreshRooms = useCallback(async ({ silent = false } = {}) => {
    if (!currentUser?.token || isLoadingRef.current) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setRefreshing(true);

      const size = Math.max(ROOM_PAGE_SIZE, (pageRef.current + 1) * ROOM_PAGE_SIZE);
      const { data, metadata } = await loadRoomsPage(0, size);
      pageRef.current = 0;
      setRooms(data);
      setHasMore(Boolean(metadata?.hasMore));
      setError(null);

      return true;
    } catch (error) {
      if (!silent) {
        setError({
          title: '채팅방 목록 갱신 실패',
          message: '목록을 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
          type: 'warning',
          showRetry: false,
        });
      }

      return false;
    } finally {
      setRefreshing(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, loadRoomsPage]);

  const handleJoinRoom = useCallback(async (roomId) => {
    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
      }
    } catch (error) {
      let errorMessage = '입장에 실패했습니다.';
      if (error.response?.status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (error.response?.status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setError({
        title: '채팅방 입장 실패',
        message: error.response?.data?.message || errorMessage,
        type: 'danger',
      });
    } finally {
      setJoiningRoom(false);
    }
  }, [connectionStatus, router]);

  return {
    rooms,
    setRooms,
    error,
    setError,
    loading,
    refreshing,
    loadingMore,
    hasMore,
    joiningRoom,
    fetchRooms,
    loadMoreRooms,
    refreshRooms,
    handleJoinRoom,
  };
};

export default useRoomList;
