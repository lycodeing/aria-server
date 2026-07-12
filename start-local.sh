#!/bin/bash
# AI CustomerService 本地后端启动脚本
# 用法：./start-local.sh [auth|knowledge|conversation|all]
# 连接配置统一在各服务的 application-local.yml 中维护，无需 export 环境变量

BACKEND=/Users/lycodeing/IdeaProjects/aria-server
MVN=/Users/lycodeing/code/apache-maven-3.9.9/bin/mvn

start_auth() {
  echo "▶ 启动 auth-service (port 8083)..."
  cd "$BACKEND/ai-auth/auth-service" && \
  $MVN spring-boot:run -Dspring-boot.run.profiles=local -q &
  echo "  PID=$!"
}

start_knowledge() {
  echo "▶ 启动 knowledge-service (port 8081)..."
  cd "$BACKEND/ai-knowledge/knowledge-service" && \
  $MVN spring-boot:run -Dspring-boot.run.profiles=local -q &
  echo "  PID=$!"
}

start_conversation() {
  echo "▶ 启动 conversation-service (port 8082)..."
  cd "$BACKEND/ai-conversation/conversation-service" && \
  $MVN spring-boot:run -Dspring-boot.run.profiles=local -q &
  echo "  PID=$!"
}

case "${1:-all}" in
  auth)         start_auth ;;
  knowledge)    start_knowledge ;;
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
