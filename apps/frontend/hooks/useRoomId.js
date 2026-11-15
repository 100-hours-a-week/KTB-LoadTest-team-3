import { useSearchParams } from 'next/navigation';

export const useRoomId = () => {
  return useSearchParams().get('id');
};

export default useRoomId;
