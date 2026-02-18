#!/bin/bash
# =============================================================
# EC2 User Data — Amazon Linux 2023
# Docker + Docker Compose 설치
# EC2 생성 시 "User Data"에 붙여넣기
# =============================================================
set -euo pipefail

# Docker 설치
dnf update -y
dnf install -y docker git
systemctl enable docker
systemctl start docker

# ec2-user를 docker 그룹에 추가 (sudo 없이 docker 실행)
usermod -aG docker ec2-user

# Docker Compose v2 플러그인 설치
DOCKER_COMPOSE_VERSION="v2.29.1"
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fSL "https://github.com/docker/compose/releases/download/${DOCKER_COMPOSE_VERSION}/docker-compose-linux-$(uname -m)" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 설치 확인
docker --version
docker compose version

echo "=== Docker + Docker Compose 설치 완료 ==="
