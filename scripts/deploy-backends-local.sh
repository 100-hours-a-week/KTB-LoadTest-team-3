#!/usr/bin/env bash

# Docker Hub에 CI가 올린 동일한 백엔드 이미지를 여러 EC2에 순차 배포한다.
# 실행 예시:
#   SSH_KEY="$HOME/.ssh/team3.pem" \
#     ./scripts/deploy-backends-local.sh backend-<commit-sha> 1.2.3.4 5.6.7.8

set -euo pipefail

IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-khm8992/ktb-bootcampchat}"
SSH_USER="${SSH_USER:-ubuntu}"
REMOTE_ENV_FILE="${REMOTE_ENV_FILE:-/home/ubuntu/KTB-LoadTest-team-3/apps/backend/.env}"
CONTAINER_NAME="${CONTAINER_NAME:-ktb-backend}"

usage() {
    cat <<'EOF'
사용법:
  SSH_KEY="/키/경로/team3.pem" \
    ./scripts/deploy-backends-local.sh <이미지-태그> <서버1> [서버2 ...]

예시:
  SSH_KEY="$HOME/.ssh/team3.pem" \
    ./scripts/deploy-backends-local.sh \
    backend-c95e1a0... \
    15.1.1.1 15.1.1.2

선택 환경변수:
  IMAGE_REPOSITORY  기본값: khm8992/ktb-bootcampchat
  SSH_USER         기본값: ubuntu
  REMOTE_ENV_FILE  기본값: /home/ubuntu/KTB-LoadTest-team-3/apps/backend/.env
  CONTAINER_NAME   기본값: ktb-backend
EOF
}

if [ "$#" -lt 2 ]; then
    usage
    exit 1
fi

if [ -z "${SSH_KEY:-}" ]; then
    echo "SSH_KEY 환경변수를 입력해주세요."
    usage
    exit 1
fi

if [ ! -f "$SSH_KEY" ]; then
    echo "SSH 키 파일을 찾을 수 없습니다: $SSH_KEY"
    exit 1
fi

IMAGE_TAG="$1"
shift
BACKEND_HOSTS=("$@")
IMAGE="$IMAGE_REPOSITORY:$IMAGE_TAG"

chmod 400 "$SSH_KEY"

echo "배포 이미지: $IMAGE"
echo "배포 서버 수: ${#BACKEND_HOSTS[@]}"

for host in "${BACKEND_HOSTS[@]}"; do
    echo
    echo "배포 시작: $host"

    ssh \
        -i "$SSH_KEY" \
        -o ConnectTimeout=10 \
        "$SSH_USER@$host" \
        bash -s -- "$IMAGE" "$REMOTE_ENV_FILE" "$CONTAINER_NAME" <<'REMOTE_SCRIPT'
set -euo pipefail

IMAGE="$1"
ENV_FILE="$2"
CONTAINER_NAME="$3"

if [ ! -f "$ENV_FILE" ]; then
    echo "환경변수 파일이 없습니다: $ENV_FILE"
    exit 1
fi

# 서비스를 중단하기 전에 이미지를 먼저 받아 중단 시간을 줄인다.
echo "새 이미지 다운로드: $IMAGE"
sudo docker pull "$IMAGE"

echo "기존 백엔드 종료"
pkill -TERM -f '[j]ava -jar target/ktb-chat-backend-.*\.jar' || true
sudo docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

echo "새 백엔드 컨테이너 실행"
sudo docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    --env-file "$ENV_FILE" \
    -e JAVA_TOOL_OPTIONS=-Xmx1024m \
    -p 5001:5001 \
    -p 5002:5002 \
    "$IMAGE"

echo "헬스체크 대기"
HEALTHY=false

for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:5001/api/health >/dev/null; then
        HEALTHY=true
        break
    fi
    sleep 5
done

if [ "$HEALTHY" != true ]; then
    echo "헬스체크 실패"
    sudo docker logs --tail 100 "$CONTAINER_NAME" || true
    exit 1
fi

echo "배포 성공"
sudo docker ps --filter "name=$CONTAINER_NAME"
REMOTE_SCRIPT

    echo "서버 배포 완료: $host"
done

echo
echo "모든 백엔드 배포 완료"
