import { redirect } from 'next/navigation';

export default async function LoginRedirectPage({ searchParams }) {
  const params = new URLSearchParams(await searchParams);
  const queryString = params.toString();

  redirect(queryString ? `/?${queryString}` : '/');
}
