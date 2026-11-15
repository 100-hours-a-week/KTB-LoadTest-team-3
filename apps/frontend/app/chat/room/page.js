'use client';

import { Suspense, useEffect } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomView from '@/features/chat/room/ChatRoomView';
import { useRoomId } from '@/hooks/useRoomId';

const LoadingState = () => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100vh',
      backgroundColor: 'var(--vapor-color-background)',
      color: 'var(--vapor-color-text-primary)',
    }}
  >
    <div>Loading...</div>
  </div>
);

function ChatRoomContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { isAuthenticated, isLoading } = useAuth();
  const roomId = useRoomId();
  const asPath = searchParams.size > 0
    ? `${pathname}?${searchParams.toString()}`
    : pathname;

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.replace(`/?redirect=${encodeURIComponent(asPath)}`);
    }
  }, [asPath, isAuthenticated, isLoading, router]);

  if (isLoading || !isAuthenticated) {
    return <LoadingState />;
  }

  return (
    <>
      <ChatHeader />
      <ChatRoomView
        roomId={roomId}
        onNavigate={router.push}
        onReplace={router.replace}
        asPath={asPath}
      />
    </>
  );
}

export default function ChatRoomPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <ChatRoomContent />
    </Suspense>
  );
}
