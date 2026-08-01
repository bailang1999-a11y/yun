#!/usr/bin/env bash
# 一键上传并执行服务器检查
# 使用方法: ./upload-and-check.sh

set -euo pipefail

SERVER="109.244.63.71"
USER="root"
PROJECT_DIR="/vol1/1000/docker/xiyiyun"

echo "=========================================="
echo "上传检查脚本到服务器"
echo "=========================================="

# 上传检查脚本
scp scripts/check-server-status.sh "${USER}@${SERVER}:${PROJECT_DIR}/scripts/"

echo ""
echo "=========================================="
echo "连接服务器并执行检查"
echo "=========================================="

# 连接并执行
ssh "${USER}@${SERVER}" << 'ENDSSH'
cd /vol1/1000/docker/xiyiyun
chmod +x scripts/check-server-status.sh
./scripts/check-server-status.sh
ENDSSH

echo ""
echo "检查完成！"
