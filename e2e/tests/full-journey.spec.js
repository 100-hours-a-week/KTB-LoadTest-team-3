const { test, expect } = require('@playwright/test');

const PASSWORD = 'Password123!';
const FORBIDDEN_WORD = 'b3sig78jv';

const createUniqueId = () =>
  `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;

const profileImageFile = {
  name: 'profile.png',
  mimeType: 'image/png',
  buffer: Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=',
    'base64'
  ),
};

const think = async (page, min = 300, max = 900) => {
  const delay = Math.floor(Math.random() * (max - min + 1)) + min;
  await page.waitForTimeout(delay);
};

const waitForChatReady = async (page) => {
  await expect(page.getByTestId('chat-message-input')).toBeVisible({ timeout: 20000 });
  await expect(page.getByTestId('chat-send-button')).toBeVisible({ timeout: 10000 });
};

const waitForRoomList = async (page) => {
  const joinButton = page.getByTestId('join-chat-room-button').first();
  const emptyRooms = page.getByTestId('rooms-empty');
  await expect(joinButton.or(emptyRooms)).toBeVisible({ timeout: 20000 });
};

const sendAndVerifyMessage = async (page, message) => {
  await waitForChatReady(page);
  await page.getByTestId('chat-message-input').fill(message);
  await page.getByTestId('chat-send-button').click();
  await expect(
    page.getByTestId('message-content').filter({ hasText: message }).last()
  ).toBeVisible({ timeout: 15000 });
};

test.describe.serial('전체 사용자 여정 E2E', () => {
  test('@journey 로그인 실패부터 로그아웃까지 주요 기능을 검증한다', async ({ page }) => {
    test.setTimeout(120000);
    page.setDefaultTimeout(15000);
    page.setDefaultNavigationTimeout(30000);

    const unique = createUniqueId();
    const testUser = {
      email: `e2e_journey_${unique}@load-harbor.test`,
      password: PASSWORD,
      passwordConfirm: PASSWORD,
      name: `E2E User ${unique}`,
    };

    await test.step('잘못된 계정으로 로그인하면 오류가 표시된다', async () => {
      await page.goto('/login');
      await page.getByTestId('login-email-input').fill('invalid-login@load-harbor.test');
      await page.getByTestId('login-password-input').fill('WrongPassword123!');
      await page.getByTestId('login-submit-button').click();
      await expect(page.getByTestId('login-error-message')).toBeVisible({ timeout: 10000 });
    });

    await test.step('고유 사용자를 회원가입하고 로그인한다', async () => {
      await page.goto('/register');
      await page.getByTestId('register-email-input').fill(testUser.email);
      await page.getByTestId('register-password-input').fill(testUser.password);
      await page.getByTestId('register-password-confirm-input').fill(testUser.passwordConfirm);
      await page.getByTestId('register-name-input').fill(testUser.name);
      await page.getByTestId('register-submit-button').click();
      await page.waitForURL(
        (url) => ['/', '/login'].includes(new URL(url).pathname),
        { timeout: 20000 }
      );

      await page.goto('/login');
      await page.getByTestId('login-email-input').fill(testUser.email);
      await page.getByTestId('login-password-input').fill(testUser.password);
      await page.getByTestId('login-submit-button').click();
      await page.waitForURL((url) => new URL(url).pathname === '/chat', { timeout: 30000 });
      await waitForRoomList(page);
    });

    await test.step('채팅방을 만들고 일반 메시지를 전송한다', async () => {
      await page.goto('/chat/new');
      await page.getByTestId('chat-room-name-input').fill(`E2E_채팅방_${unique}`);
      await page.getByTestId('create-chat-room-button').click();
      await waitForChatReady(page);
      await sendAndVerifyMessage(page, `E2E 테스트 메시지 ${unique}`);
    });

    await test.step('채팅 목록의 첫 방에서 연속 메시지를 전송한다', async () => {
      await page.goto('/chat');
      await waitForRoomList(page);
      const firstJoinButton = page.getByTestId('join-chat-room-button').first();

      if (await firstJoinButton.isVisible()) {
        await firstJoinButton.click();
        await waitForChatReady(page);
        for (let index = 1; index <= 3; index += 1) {
          await sendAndVerifyMessage(page, `E2E 일반 채팅 ${index} ${unique}`);
          await think(page, 300, 700);
        }
      }
    });

    await test.step('이미지 파일을 업로드하고 메시지를 확인한다', async () => {
      await page.goto('/chat/new');
      await page.getByTestId('chat-room-name-input').fill(`E2E_파일방_${unique}`);
      await page.getByTestId('create-chat-room-button').click();
      await waitForChatReady(page);

      const fileMessage = `E2E 파일 업로드 ${unique}`;
      const uploadResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/files/upload') &&
          response.request().method() === 'POST',
        { timeout: 20000 }
      );

      await page.getByTestId('file-upload-input').setInputFiles(profileImageFile);
      await page.getByTestId('chat-message-input').fill(fileMessage);
      await page.getByTestId('chat-send-button').click();

      const uploadResponse = await uploadResponsePromise;
      expect(uploadResponse.status()).toBe(200);
      await expect(
        page.getByTestId('file-message-container').filter({ hasText: fileMessage }).last()
      ).toBeVisible({ timeout: 15000 });
    });

    await test.step('금칙어 메시지를 차단한다', async () => {
      await page.getByTestId('chat-message-input').fill(FORBIDDEN_WORD);
      await page.getByTestId('chat-send-button').click();
      const errorToast = page.getByTestId('toast-error');
      await expect(errorToast).toBeVisible({ timeout: 10000 });
      await expect(errorToast).toContainText('금칙어');
      await expect(
        page.getByTestId('message-content').filter({ hasText: FORBIDDEN_WORD })
      ).toHaveCount(0);
    });

    await test.step('프로필 이름을 수정한다', async () => {
      const updatedName = `E2E_수정_${unique}`;
      await page.goto('/profile');
      await expect(page.getByTestId('profile-name-input')).toBeVisible();
      await page.getByTestId('profile-name-input').fill(updatedName);
      await page.getByTestId('profile-save-button').click();
      await expect(page.getByTestId('profile-success-message')).toBeVisible();
      await expect(page.getByTestId('profile-name-input')).toHaveValue(updatedName);
    });

    await test.step('정상적으로 로그아웃한다', async () => {
      await page.getByTestId('logout-link').click();
      await page.waitForURL(
        (url) => ['/', '/login'].includes(new URL(url).pathname),
        { timeout: 20000 }
      );
      await expect(page.getByTestId('login-email-input')).toBeVisible();
    });
  });
});
