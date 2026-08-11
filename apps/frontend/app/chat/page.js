'use client';

import { useEffect } from 'react';
import dynamic from 'next/dynamic';
import { usePathname, useRouter } from 'next/navigation';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomsView from '@/features/chat/rooms/ChatRoomsView';

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

function ChatPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, isLoading } = useAuth();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      // const redirect = encodeURIComponent(pathname);
      // window.location.replace(`/?redirect=${redirect}`);
      router.replace(`/?redirect=${pathname}`);
    }
  }, [isAuthenticated, isLoading, pathname, router]);

  if (isLoading || !isAuthenticated) {
    return <LoadingState />;
  }

  return (
    <>
      <ChatHeader />
      <ChatRoomsView router={router} />
    </>
  );
}

const ClientOnlyChatPage = dynamic(
  () => Promise.resolve(ChatPageContent),
  {
    ssr: false,
    loading: LoadingState,
  }
);

export default ClientOnlyChatPage;
