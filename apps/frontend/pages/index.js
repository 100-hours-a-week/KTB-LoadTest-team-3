import React, { useState, useEffect } from 'react';
import dynamic from 'next/dynamic';
import { useRouter } from 'next/router';
import { ErrorCircleIcon } from '@vapor-ui/icons';
import { withoutAuth, useAuth } from '@/contexts/AuthContext';
import authService from '@/services/authService';
import {
    Box,
    Button,
    Callout,
    Field,
    Form,
    HStack,
    Text,
    TextInput,
    VStack,
} from '@vapor-ui/core';

const Login = () => {
  const [formData, setFormData] = useState({
    email: '',
    password: ''
  });
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [serverStatus, setServerStatus] = useState({
    checking: true,
    connected: false
  });
  // 서버 생존 확인 결과는 로그인 시도 결과와 섞지 않는다.
  // 한 요소로 합치면 연결 경고가 로그인 실패로 오인된다.
  const [serverNotice, setServerNotice] = useState(null);
  const router = useRouter();
  const { login } = useAuth();

  // 서버 연결 상태 확인
  useEffect(() => {
    const checkServerConnection = async () => {
      try {
        await authService.checkServerConnection();
        setServerStatus({ checking: false, connected: true });
      } catch (error) {

        // 개발 환경에서는 더 관대하게 처리
        if (process.env.NODE_ENV === 'development') {
          setServerStatus({ checking: false, connected: true });
          setServerNotice('개발 환경: 서버 연결을 확인할 수 없지만 계속 진행합니다. 백엔드 서버가 실행 중인지 확인해주세요.');
        } else {
          // 프로덕션에서는 연결 실패해도 페이지는 보여주되, 경고만 표시
          setServerStatus({ checking: false, connected: false });
          setServerNotice('서버와의 연결을 확인할 수 없습니다. 로그인을 시도해보세요. 문제가 지속되면 새로고침해주세요.');
        }
      }
    };

    checkServerConnection();

    // fallback으로 4초 후에는 무조건 체크 완료로 처리 (authService timeout 3초 + 여유시간)
    const fallbackTimer = setTimeout(() => {
      setServerStatus(prev => prev.checking ? { checking: false, connected: true } : prev);
    }, 4000);

    return () => {
      clearTimeout(fallbackTimer);
    };
  }, []);

  const validateForm = () => {
    // 유효성 검사는 HTML5 폼 검증에 맡김
    return true;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    // 서버 연결 상태 확인 — 로그인을 시도조차 못 한 것이므로 서버 안내로 알린다.
    if (!serverStatus.connected) {
      setServerNotice('서버와 연결할 수 없습니다. 인터넷 연결을 확인하고 잠시 후 다시 시도해주세요.');
      return;
    }

    // 폼 유효성 검사
    if (!validateForm()) {
      return;
    }

    setLoading(true);
    setError(null);

    try {
      // 로그인 요청 데이터 준비
      const loginCredentials = {
        email: formData.email.trim(),
        password: formData.password
      };

      // AuthContext의 login 메서드 사용 (API 호출 + 상태 저장)
      await login(loginCredentials);

      // 리다이렉트
      const redirectUrl = router.query.redirect || '/chat';
      router.push(redirectUrl);

    } catch (err) {
      setError(err.message || '로그인 처리 중 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  // if (serverStatus.checking) {
  //   return (
  //     <div className="min-h-screen flex items-center justify-center p-[var(--vapor-space-300)] bg-[var(--vapor-color-background)]">
  //       <VStack
  //         $css={{
  //           gap: '$250',
  //           width: '400px',
  //           padding: '$300',
  //           borderRadius: '$300',
  //           border: '1px solid var(--vapor-color-border-normal)',
  //         }}
  //       >
  //         <div className="text-center mb-[2rem]">
  //           <img src="images/logo-h.png" className="w-1/2 mx-auto" alt="KTB Chat 로고" />
  //         </div>
  //         <div className="text-center">
  //           <Text typography="body1">서버 연결 확인 중...</Text>
  //         </div>
  //       </VStack>
  //     </div>
  //   );
  // }

  return (
    <div className="min-h-screen flex items-center justify-center p-(--vapor-space-300) bg-(--vapor-color-background)">
      <VStack
        $css={{
          gap: '$250',
          width: '400px',
          padding: '$300',
          borderRadius: '$300',
          border: '1px solid var(--vapor-color-border-normal)',
        }}
        render={<Form onSubmit={handleSubmit} />}
      >
        <div className="text-center mb-4">
          <img src="images/logo-h.png" className="w-1/2 mx-auto" alt="KTB Chat 로고" />
        </div>

        {serverNotice && (
          <Callout.Root colorPalette="warning" data-testid="server-status-message">
            <Callout.Icon>
              <ErrorCircleIcon />
            </Callout.Icon>
            {serverNotice}
          </Callout.Root>
        )}

        {error && (
          <Callout.Root colorPalette="warning" data-testid="login-error-message">
            <Callout.Icon>
              <ErrorCircleIcon />
            </Callout.Icon>
            {error}
          </Callout.Root>
        )}

        <VStack $css={{ gap: '$400' }}>
          <VStack $css={{ gap: '$200' }}>
            <Field.Root>
              <Box
                render={<Field.Label />}
                $css={{ flexDirection: 'column' }}
                style={{ fontSize: '14px', fontWeight: '500', marginBottom: '8px' }}
              >
                이메일
                <TextInput
                  id="login-email"
                  size="lg"
                  type="email"
                  required
                  disabled={loading}
                  value={formData.email}
                  onValueChange={(value) => setFormData(prev => ({ ...prev, email: value }))}
                  placeholder="이메일을 입력하세요"
                  data-testid="login-email-input"
                />
              </Box>
              <Field.Error match="valueMissing">이메일을 입력해주세요.</Field.Error>
              <Field.Error match="typeMismatch">유효한 이메일 형식이 아닙니다.</Field.Error>
            </Field.Root>

            <Field.Root>
              <Box
                render={<Field.Label />}
                $css={{ flexDirection: 'column' }}
                style={{ fontSize: '14px', fontWeight: '500', marginBottom: '8px' }}
              >
                비밀번호
                <TextInput
                  id="login-password"
                  size="lg"
                  type="password"
                  required
                  disabled={loading}
                  value={formData.password}
                  onValueChange={(value) => setFormData(prev => ({ ...prev, password: value }))}
                  placeholder="비밀번호를 입력하세요"
                  data-testid="login-password-input"
                />
              </Box>
              <Field.Error match="valueMissing">비밀번호를 입력해주세요.</Field.Error>
            </Field.Root>
          </VStack>

          <Button
            type="submit"
            size="lg"
            disabled={loading || !serverStatus.connected}
            data-testid="login-submit-button"
          >
            {loading ? '로그인 중...' : '로그인'}
          </Button>
        </VStack>

        <HStack $css={{ justifyContent: 'center' }}>
          <Text typography="body2">계정이 없으신가요?</Text>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => router.push('/register')}
            disabled={loading || !serverStatus.connected}
          >
            회원가입
          </Button>
        </HStack>
      </VStack>
    </div>
  );
};

const ClientOnlyLogin = dynamic(
  () => Promise.resolve(withoutAuth(Login)),
  {
    ssr: false,
    loading: () => (
      <div className="min-h-screen flex items-center justify-center">
        <div>Loading...</div>
      </div>
    ),
  }
);

export default ClientOnlyLogin;
