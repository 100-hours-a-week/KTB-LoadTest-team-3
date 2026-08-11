# Socket.IO CSR 구조 분석

> 분석 범위: 프론트엔드의 Socket.IO client 생성, 연결, 인증 연동, 재연결, listener 및 room join/leave 생명주기. 채팅 UI 렌더링은 제외했다. 이 문서는 현재 코드의 정적 분석 결과이며 코드는 수정하지 않았다.

## 1. Socket 관련 파일

### 핵심 계층

| 파일 | 역할 | 주요 동작 |
|---|---|---|
| `services/socket.js` | Socket.IO 연결을 소유하는 싱글턴 `SocketService` | 유일한 `io(...)` 호출, connect/disconnect, 기본 listener, 내장 및 수동 reconnect |
| `lib/socket/socketClient.js` | `SocketService`의 도메인용 facade | 메시지 전송, room join/leave, room/connection listener 등록 및 해제 |
| `lib/socket/SocketProvider.js` | `SocketContext`와 전역 client 제공 | online/offline listener 등록, online 복귀 시 인증된 재연결, offline 시 disconnect |
| `lib/socket/useSocket.js` | Context 접근 hook | `SocketProvider`가 제공한 client 반환 |
| `pages/_app.js` | Pages Router의 Provider 조립 | `AuthContext.user`를 `SocketProvider.session`으로 전달 |
| `app/providers.js` | App Router의 Client Provider 조립 | `'use client'`; `AuthContext.user`를 `SocketProvider.session`으로 전달 |
| `contexts/AuthContext.js` | 인증 종료와 socket 정리 연동 | 로그아웃 및 세션 만료 시 `socketService.disconnect()` |

### 연결 및 room 생명주기

| 파일 | 역할 | 주요 동작 |
|---|---|---|
| `features/chat/rooms/useRoomsSocket.js` | 채팅방 목록용 socket 생명주기 | `currentUser` 확인 후 effect에서 connect, 목록 event listener 등록, cleanup에서 disconnect |
| `features/chat/room/useSocketHandling.js` | 활성 room socket 참조와 연결 상태 | socket 교체 시 connect/disconnect listener를 등록하고 정확한 handler로 해제 |
| `features/chat/room/useRoomHandling.js` | 채팅방 socket 준비 및 room 입장 | 인증 확인, connect, room listener 등록, `joinRoomAndWait`, reconnect 후 rejoin, unmount disconnect |
| `features/chat/room/useChatRoomLifecycle.js` | 연결 및 재연결 lifecycle 구독 | socket/manager listener 등록, cleanup, reconnect 성공 후 room 재입장 실행 |
| `features/chat/room/useChatRoom.js` | room 종료 정리 진입점 | `leaveRoom`, room listener 제거, timeout/ref 정리 |

### 테스트에서 확인하는 계약

| 파일 | 확인 내용 |
|---|---|
| `services/__tests__/socket.test.js` | connect 중복 억제, stale socket 보호, manager reconnect, disconnect/reconnect |
| `lib/socket/__tests__/socketClient.test.js` | listener 해제, join/leave, timeout listener cleanup, manager event mapping |
| `lib/socket/__tests__/SocketProvider.test.js` | 인증 세션 유무에 따른 online 재연결 |
| `features/chat/rooms/__tests__/useRoomsSocket.test.js` | 목록 socket 연결 및 불필요한 room-list join 방지 |
| `features/chat/room/__tests__/*socket*`, `useRoomHandling.test.js`, `useChatRoomLifecycle*.test.js` | socket 교체, reconnect, room rejoin, listener lifecycle |

`io(...)` 호출은 `services/socket.js`의 `SocketService.connect()` 한 곳에만 있다. `forceNew: true`이므로 실제 connect 호출이 새 연결 생성까지 도달하면 새 Manager/Socket을 만든다. 동시에 들어온 connect는 `connectionPromise`, 이미 연결된 경우는 `this.socket?.connected` 검사로 합쳐진다.

## 2. 현재 SSR/CSR 구조

### 파일별 실행 경계

| 대상 | 분류 | SSR 중 가능한 동작 | 브라우저에서만 실행되는 동작 |
|---|---|---|---|
| `app/providers.js` | 명시적 Client Component | Client Component의 초기 HTML 계산 대상이 될 수 있음 | effect와 브라우저 event 처리 |
| `lib/socket/SocketProvider.js` | App Router에서는 client tree, Pages Router에서는 hydration되는 React 컴포넌트 | Context 값 및 JSX 계산 | `useEffect`, `window.addEventListener` |
| `services/socket.js` | React 컴포넌트가 아닌 공유 모듈 | import와 `new SocketService()`로 연결 없는 싱글턴 생성 가능 | `connect()`가 호출될 때 `io(...)` 실행 |
| `lib/socket/socketClient.js` | React 컴포넌트가 아닌 facade 모듈 | 함수 객체 생성 가능 | 실제 connect/emit/on/off 호출 |
| `useRoomsSocket`, `useRoomHandling`, `useChatRoomLifecycle`, `useSocketHandling` | client-side React hook | hook 함수/초기 state 계산 가능 | 모든 연결 및 listener effect |

Socket 관련 hook 파일에 `'use client'`가 직접 붙어 있지는 않다. 그러나 App Router에서는 `'use client'`인 `app/providers.js` 및 client page 하위에서 사용되고, Pages Router에서는 페이지가 hydration된 뒤 effect가 실행된다. 무엇보다 실제 연결 진입점은 `useEffect` 또는 그 effect가 호출하는 함수에 있다.

`SocketProvider`의 `window` 접근도 effect 내부에만 있으므로 서버 HTML 생성 중 실행되지 않는다. Socket 코드 자체는 `localStorage`를 직접 읽지 않는다. 인증 정보는 `AuthContext`가 localStorage에서 복원한 `user` 객체를 prop/hook으로 전달한다.

### 질문별 답변

1. **Socket.IO client 생성 자체는 SSR에서 실행되는가?**  
   연결을 관리하는 `SocketService` 싱글턴 인스턴스와 facade 객체는 모듈 import 중 서버에서도 만들어질 수 있다. 하지만 실제 Socket.IO client/transport를 만드는 `io(...)`는 SSR 중 호출되지 않는다.

2. **socket connection은 브라우저 mount 이후에만 발생하는가?**  
   그렇다. 최초 연결은 `useRoomsSocket` 또는 채팅방 lifecycle의 effect가 인증 사용자를 확인한 뒤 시작한다. online 복구도 `SocketProvider`의 browser event callback에서만 시작한다.

3. **서버 HTML 생성 단계에서는 socket connection이 발생하지 않는가?**  
   현재 호출 그래프에서는 발생하지 않는다. render 본문이나 module top-level에서 `connect()`/`io(...)`를 호출하는 코드는 없다.

## 3. Socket 연결 흐름

### 앱 공통 초기화

```text
Pages Router _app 또는 App Router AppProviders
→ AuthProvider 초기 render: user = null, isLoading = true
→ AuthenticatedSocketProvider render
→ SocketProvider(session = null) mount
→ SocketProvider effect가 online/offline listener만 등록
→ 이 시점에는 connect하지 않음
→ AuthProvider effect가 localStorage 사용자 복원
→ user 설정
→ SocketProvider(session = user) 재render
→ online/offline listener를 새 session 기준으로 재등록
→ 여전히 user 변화만으로는 connect하지 않음
```

### 채팅방 목록 진입

```text
인증된 currentUser 전달
→ useRoomsSocket effect 실행
→ socketClient.connect({ auth: token, sessionId })
→ SocketService.connect()
→ io(NEXT_PUBLIC_SOCKET_URL, options)
→ SocketService 기본 socket/manager listener 등록
→ connect 성공
→ 목록용 listener(connect, disconnect, error, roomCreated, roomUpdated, roomActivity) 등록
→ unmount/currentUser 변경 cleanup에서 socket.disconnect()
```

서버가 연결 시 room-list에 자동 참가한다는 전제이며, 프론트에서 별도 `joinRoomList`를 emit하지 않는다.

### 개별 채팅방 진입 및 재연결

```text
useChatRoomLifecycle effect가 authUser와 roomId 확인
→ setupRoom 중복 실행을 setupPromiseRef/initializingRef로 억제
→ setupSocket에서 token/sessionId 확인
→ socketClient.connect()
→ 활성 socket을 ref와 state에 연결
→ room event listener 등록(기존 구독은 먼저 해제)
→ joinRoomAndWait(roomId)
→ joinRoomSuccess 또는 오류/timeout 시 임시 listener 정리
→ Socket.IO 내장 reconnect 수행
→ manager reconnect는 화면 상태만 복구
→ socket connect event에서 rejoinRoom()
→ room join을 다시 emit하되 room listener는 기존 socket에 유지
```

### 연결 종료

- 로그아웃/세션 만료: `AuthContext`가 싱글턴의 `socketService.disconnect()` 호출.
- offline: `SocketProvider`가 `client.disconnect()` 호출.
- 목록 unmount: `useRoomsSocket`이 보관한 socket에서 직접 `disconnect()`.
- room unmount: room leave를 best-effort로 emit하고 listener 및 socket을 정리.

## 4. Hydration 및 중복 연결 위험

| 점검 항목 | 판정 | 코드 근거 및 영향 |
|---|---|---|
| hydration 전 socket 생성 | 확인되지 않음 | 모든 connect 호출이 effect/lifecycle 비동기 경로에 있음 |
| socket으로 인한 server/client 초기 HTML 차이 | 직접 위험 없음 | socket 상태가 SSR render에서 연결을 시작하지 않음; 연결 상태 변경은 mount 이후 발생 |
| `user = null` 상태에서 연결 | 방지됨 | 목록 hook과 room setup 모두 token을 검사하고, Provider의 online handler도 token/sessionId를 검사 |
| localStorage 복원 후 재생성 | 초기 복원만으로는 생성하지 않음 | Provider는 session 변경 시 browser listener만 재등록; 실제 페이지 hook이 이후 한 번 connect |
| 동시 connect 중복 생성 | 기본 방어 있음 | `connectionPromise`와 connected socket 재사용. 단, 직접 `socket.disconnect()`한 뒤 다른 화면이 connect하면 기존 객체 정리 후 새 `io(...)` 생성 |
| StrictMode effect 이중 실행 | 부분 위험 | 최초 effect의 비동기 connect가 cleanup 전에 resolve되지 않으면 `socketRef`가 아직 null이라 cleanup이 연결을 끊지 못한다. 재실행 effect가 같은 `connectionPromise`를 이어받으면 보통 회수되지만, 실제 unmount라면 연결이 남을 수 있다 |
| 목록 listener 중복 | 부분 위험 | handler를 개별 `off`하지 않고 cleanup에서 socket 전체를 disconnect한다. 동일 socket을 다른 소비자가 공유하면 생명주기 소유권이 충돌할 수 있음 |
| room listener 중복 | 방어됨 | `roomEventsUnsubscribeRef`의 이전 구독을 먼저 해제하고 정확한 handler로 `off`함 |
| connection listener 중복 | 방어됨 | `subscribeConnectionEvents`가 socket과 manager listener 모두 unsubscribe 함수를 반환하고 effect cleanup에서 실행 |
| route 이동 후 listener 잔존 | 목록에서 가능성 있음, room은 대체로 방어됨 | 목록은 pending connect 완료 전에 unmount되면 resolve 후 `isSubscribed` 검사로 listener는 안 붙지만 그 socket을 disconnect하지 않음. room은 명시적 unsubscribe/disconnect 경로 보유 |
| reconnect 시 room join 중복 | 직접적인 중복 방어 있음 | manager `reconnect`에서는 join하지 않고 실제 `connect`에서만 rejoin; `setupCompleteRef`/`initializingRef`로 최초 setup과 구분 |
| 인증 user 객체 identity 변화에 따른 반복 연결 | 가능성 있음 | `useRoomsSocket` dependency가 `[currentUser]` 전체 객체라 동일 token/session의 새 객체도 cleanup/disconnect/connect를 유발할 수 있음 |
| Provider와 page hook의 연결 소유권 분산 | 확인됨 | Provider는 offline disconnect/online reconnect, 목록과 room hook은 최초 connect 및 직접 socket disconnect를 담당 |

가장 구체적인 lifecycle 위험은 `useRoomsSocket`이다. cleanup은 `socketRef.current`만 끊지만, 비동기 connect가 아직 끝나지 않았다면 ref가 null이다. 이후 Promise가 resolve되면 `isSubscribed === false` 때문에 ref/listener는 설정하지 않으면서 연결된 싱글턴 socket은 남을 수 있다.

또한 `services/socket.js`는 Socket.IO 자체 reconnect와 `TransportError`에서 호출하는 수동 `reconnect()`를 함께 사용한다. `isReconnecting` 및 stale socket 검사로 일부 경쟁을 막고 있지만, 연결 정책이 두 군데여서 복잡도가 높다.

## 5. CSR 전환 필요 여부

**Socket.IO 연결은 이미 CSR이므로 SSR → CSR 전환은 필요 없다.**

- `io(...)`는 서버 render 중 호출되지 않는다.
- 연결은 인증된 사용자를 확인한 client effect 또는 browser online event 이후에만 일어난다.
- `window` 접근은 `SocketProvider` effect 안에만 있다.
- 서버와 브라우저가 socket 연결 여부 때문에 서로 다른 초기 markup을 만드는 구조가 아니다.

따라서 `services/socket.js`, `lib/socket/socketClient.js`, `lib/socket/SocketProvider.js`에 단순히 `'use client'`를 추가하거나 dynamic import로 바꿀 이유가 없다. App Router의 client 경계는 이미 `app/providers.js`에 있다. 개선이 필요하다면 렌더링 방식 전환이 아니라 연결 소유권과 비동기 cleanup 안정화가 대상이다.

최근 로그인/회원가입 CSR 변경도 socket 연결 횟수를 직접 늘리지 않는다. 인증 페이지에서도 `SocketProvider`는 mount되지만 session이 null이면 연결하지 않는다. localStorage 복원으로 user가 설정돼도 Provider 자체는 즉시 connect하지 않으며, 인증 후 채팅 목록 또는 채팅방 hook이 mount될 때 연결한다. 다만 로그인 직후 빠른 route 전환이나 StrictMode에서 연결 effect가 pending인 채 unmount되면 위의 `useRoomsSocket` cleanup 경쟁 가능성은 남는다.

## 6. 최적화 우선순위

| 순위 | 최적화 대상 | 코드 근거 | 예상 효과 | 난이도 |
|---:|---|---|---|---|
| 1 | pending connect의 취소/해제 안정화 | `useRoomsSocket` cleanup 시 Promise가 미완료이고 `socketRef.current`가 null이면 이후 연결된 socket이 남을 수 있음 | route 전환·StrictMode에서 유령 연결 및 listener 소유권 문제 감소 | 중간 |
| 2 | socket 연결 소유권 단일화 | Provider, 목록 hook, room hook, AuthContext가 각각 connect/disconnect 일부를 담당 | 한 화면 cleanup이 공유 싱글턴 연결을 끊는 충돌과 예측 불가능한 재연결 감소 | 높음 |
| 3 | 목록 listener를 정확한 handler 단위로 해제 | `useRoomsSocket`은 `socket.on`으로 등록하지만 `off` 없이 전체 disconnect에 의존 | listener 누적 방지, 공유 socket에서도 안전한 cleanup | 낮음 |
| 4 | 목록 effect dependency를 인증 식별자로 제한 | `[currentUser]` 전체 객체 identity 변화가 disconnect/connect를 유발 가능 | 불필요한 재연결과 handshake 감소 | 낮음 |
| 5 | 내장 reconnect와 수동 TransportError reconnect 정책 정리 | Socket.IO 옵션의 자동 reconnect와 `handleSocketError → reconnect()`가 공존 | reconnect 경쟁·새 `forceNew` 연결 생성 가능성 및 유지보수 복잡도 감소 | 중간~높음 |

## 7. 최종 결론

1. **현재 Socket.IO는 SSR 방식인가 CSR 방식인가?**  
   연결 동작 기준으로 CSR 방식이다. 공유 모듈은 서버 bundle에서 import될 수 있지만 연결 side effect는 client lifecycle에서만 발생한다.

2. **Socket.IO client가 서버에서 생성되는가?**  
   `SocketService`라는 연결 없는 JS 싱글턴 객체는 서버 import 중 생성될 수 있다. 실제 `io(...)` Socket.IO client와 네트워크 transport는 서버에서 생성되지 않는다.

3. **hydration 이후에만 연결되는가?**  
   그렇다. 목록/room의 `useEffect` 또는 mount 후 등록된 online callback을 통해서만 connect한다.

4. **SSR → CSR로 바꿀 부분이 있는가?**  
   없다. Socket.IO 관련 코드는 이미 CSR 연결 구조다.

5. **이미 CSR이라 그대로 둬도 되는 부분은 어디인가?**  
   `SocketProvider`의 browser event effect, `SocketService.connect()`의 지연된 `io(...)` 호출, `socketClient`의 listener subscribe/unsubscribe, room lifecycle의 effect 기반 연결·rejoin 구조는 SSR 회피 관점에서 그대로 둬도 된다.

6. **지금 Socket.IO 프론트 코드 한 곳만 수정한다면 어디인가?**  
   `features/chat/rooms/useRoomsSocket.js`가 우선이다. pending connect가 resolve되기 전에 effect가 cleanup되는 경우 새로 연결된 socket을 즉시 disconnect하도록 만들고, 등록한 handler를 정확히 `off`하는 방식으로 정리하는 것이 가장 작은 범위에서 실질적인 중복·잔존 연결 위험을 줄인다.
