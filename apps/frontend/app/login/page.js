'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

const LoginRedirectPage = () => {
  const router = useRouter();

  useEffect(() => {
    const queryString = window.location.search;
    router.replace(queryString ? `/${queryString}` : '/');
  }, [router]);

  return null;
};

export default LoginRedirectPage;
