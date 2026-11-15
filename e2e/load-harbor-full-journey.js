// Load Harbor 사용자 정의 E2E 입력란 전용 코드입니다.
// Playwright test(), 헬퍼 함수, expect() 없이 위에서 아래로 순서대로 실행합니다.

const baseUrl = target.replace(/\/+$/, '');
const unique = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
const password = 'Password123!';
const email = `loadharbor_${unique}@load-harbor.test`;
const name = `Load Harbor ${unique}`;

page.setDefaultTimeout(15000);
page.setDefaultNavigationTimeout(30000);

// 1. 로그인 실패
await page.goto(`${baseUrl}/login`);
await page.getByTestId('login-email-input').fill('invalid-login@load-harbor.test');
await page.getByTestId('login-password-input').fill('WrongPassword123!');
await page.getByTestId('login-submit-button').click();
await page.getByTestId('login-error-message').waitFor({ state: 'visible', timeout: 10000 });
await page.waitForTimeout(300);

// 2. 회원가입
await page.goto(`${baseUrl}/register`);
await page.getByTestId('register-email-input').fill(email);
await page.getByTestId('register-password-input').fill(password);
await page.getByTestId('register-password-confirm-input').fill(password);
await page.getByTestId('register-name-input').fill(name);
await page.getByTestId('register-submit-button').click();
await page.waitForURL(
  (url) => ['/', '/login'].includes(new URL(url).pathname),
  { timeout: 20000 }
);

// 3. 로그인
await page.goto(`${baseUrl}/login`);
await page.getByTestId('login-email-input').fill(email);
await page.getByTestId('login-password-input').fill(password);
await page.getByTestId('login-submit-button').click();
await page.waitForURL((url) => new URL(url).pathname === '/chat', { timeout: 30000 });
await page
  .getByTestId('join-chat-room-button')
  .first()
  .or(page.getByTestId('rooms-empty'))
  .waitFor({ state: 'visible', timeout: 20000 });

// 4. 채팅방 생성
await page.goto(`${baseUrl}/chat/new`);
await page.getByTestId('chat-room-name-input').fill(`E2E_채팅방_${unique}`);
await page.getByTestId('create-chat-room-button').click();
await page.getByTestId('chat-message-input').waitFor({ state: 'visible', timeout: 20000 });
await page.getByTestId('chat-send-button').waitFor({ state: 'visible', timeout: 10000 });

// 5. 일반 메시지 전송
const firstMessage = `E2E 테스트 메시지 ${unique}`;
await page.getByTestId('chat-message-input').fill(firstMessage);
await page.getByTestId('chat-send-button').click();
await page
  .getByTestId('message-content')
  .filter({ hasText: firstMessage })
  .last()
  .waitFor({ state: 'visible', timeout: 15000 });

// 6. 두 번째 메시지 전송
const secondMessage = `E2E 연속 메시지 ${unique}`;
await page.getByTestId('chat-message-input').fill(secondMessage);
await page.getByTestId('chat-send-button').click();
await page
  .getByTestId('message-content')
  .filter({ hasText: secondMessage })
  .last()
  .waitFor({ state: 'visible', timeout: 15000 });

// 7. 파일 전용 채팅방 생성
await page.goto(`${baseUrl}/chat/new`);
await page.getByTestId('chat-room-name-input').fill(`E2E_파일방_${unique}`);
await page.getByTestId('create-chat-room-button').click();
await page.getByTestId('chat-message-input').waitFor({ state: 'visible', timeout: 20000 });

// 8. 이미지 업로드
const profileImageFile = {
  name: 'profile.png',
  mimeType: 'image/png',
  buffer: Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=',
    'base64'
  ),
};
const fileMessage = `E2E 파일 업로드 ${unique}`;
const uploadResponsePromise = page.waitForResponse(
  (response) =>
    response.url().includes('/api/files/upload') &&
    response.request().method() === 'POST',
  { timeout: 35000 }
);
await page.getByTestId('file-upload-input').setInputFiles(profileImageFile);
await page.getByTestId('chat-message-input').fill(fileMessage);
await page.getByTestId('chat-send-button').click();
const uploadResponse = await uploadResponsePromise;
if (uploadResponse.status() !== 200) {
  throw new Error(`파일 업로드 실패: HTTP ${uploadResponse.status()}`);
}
await page
  .getByTestId('file-message-container')
  .filter({ hasText: fileMessage })
  .last()
  .waitFor({ state: 'visible', timeout: 15000 });

// 9. 금칙어 차단
const forbiddenWord = 'b3sig78jv';
await page.getByTestId('chat-message-input').fill(forbiddenWord);
await page.getByTestId('chat-send-button').click();
await page.getByTestId('toast-error').waitFor({ state: 'visible', timeout: 10000 });

// 10. 프로필 수정
const updatedName = `E2E_수정_${unique}`;
await page.goto(`${baseUrl}/profile`);
await page.getByTestId('profile-name-input').waitFor({ state: 'visible', timeout: 15000 });
await page.getByTestId('profile-name-input').fill(updatedName);
await page.getByTestId('profile-save-button').click();
await page.getByTestId('profile-success-message').waitFor({ state: 'visible', timeout: 15000 });
const savedName = await page.getByTestId('profile-name-input').getAttribute('value');
if (savedName !== updatedName) {
  throw new Error(`프로필 이름 불일치: ${savedName}`);
}

// 11. 접속 유지 후 로그아웃
await page.waitForTimeout(30000);
await page.getByTestId('logout-link').click();
await page.waitForURL(
  (url) => ['/', '/login'].includes(new URL(url).pathname),
  { timeout: 20000 }
);
