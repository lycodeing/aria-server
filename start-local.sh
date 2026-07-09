#!/bin/bash
# AI CustomerService 本地后端启动脚本
# 用法：./start-local.sh [auth|knowledge|conversation|all]

BACKEND=/Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend
MVN=/Users/lycodeing/apache-maven-3.9.12/bin/mvn

# 公共环境变量
export DB_USERNAME=aidev
export DB_PASSWORD=aidev123
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=
export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5672
export RABBITMQ_USERNAME=guest
export RABBITMQ_PASSWORD=guest
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET=ai-knowledge
export JWT_SECRET_KEY=cs-auth-dev-secret-key-change-in-production
# 内部服务密钥：ARIA_INTERNAL_SECRET 为新命名，兼容旧变量 INTERNAL_SECRET
export INTERNAL_SECRET=change-this-in-production
export ARIA_INTERNAL_SECRET=change-this-in-production
export AI_CTYUN_API_KEY=${AI_CTYUN_API_KEY:-}
# auth-service 内网地址：ARIA_AUTH_URL 为新命名，兼容旧变量 ARIA_AUTH_INTERNAL_URL
export ARIA_AUTH_URL=http://localhost:8083
export ARIA_AUTH_INTERNAL_URL=http://localhost:8083
export KNOWLEDGE_SERVICE_BASE_URL=http://localhost:8081
export APP_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5666,http://localhost:5667,http://localhost:5668,http://localhost:5669,http://localhost:5670,http://localhost:5671

start_auth() {
  echo "▶ 启动 auth-service (port 8083)..."
  cd "$BACKEND/ai-auth/auth-service" && \
  $MVN spring-boot:run -q &
  echo "  PID=$!"
}

start_knowledge() {
  echo "▶ 启动 knowledge-service (port 8081)..."
  cd "$BACKEND/ai-knowledge/knowledge-service" && \
  $MVN spring-boot:run -q &
  echo "  PID=$!"
}

start_conversation() {
  echo "▶ 启动 conversation-service (port 8082)..."
  cd "$BACKEND/ai-conversation/conversation-service" && \
  $MVN spring-boot:run -q &
  echo "  PID=$!"
}

case "${1:-all}" in
  auth)        start_auth ;;
  knowledge)   start_knowledge ;;
  conversation) start_conversation ;;
  all)
    start_auth
    sleep 5
    start_knowledge
    sleep 2
    start_conversation
    echo ""
    echo "✅ 全部服务已后台启动"
    echo "   auth-service:         http://localhost:8083"
    echo "   knowledge-service:    http://localhost:8081"
    echo "   conversation-service: http://localhost:8082"
    echo ""
    echo "停止所有服务: kill \$(lsof -ti:8081,8082,8083)"
    ;;
  *)
    echo "用法: $0 [auth|knowledge|conversation|all]"
    exit 1
    ;;
esac
