#!/bin/bash
# 远程执行服务器命令的便捷脚本

SERVER="109.244.63.71"
USER="root"
PROJECT_DIR="/vol1/1000/docker/xiyiyun"

# 显示菜单
show_menu() {
    echo "=========================================="
    echo "喜易云服务器管理工具"
    echo "=========================================="
    echo "1. 查看服务状态"
    echo "2. 查看容器日志"
    echo "3. 启动所有服务"
    echo "4. 重启所有服务"
    echo "5. 停止所有服务"
    echo "6. 查看环境配置"
    echo "7. 运行完整检查"
    echo "8. 进入服务器 Shell"
    echo "0. 退出"
    echo "=========================================="
}

# 执行远程命令
run_remote() {
    ssh ${USER}@${SERVER} "cd ${PROJECT_DIR} && $1"
}

while true; do
    show_menu
    read -p "请选择操作 [0-8]: " choice

    case $choice in
        1)
            echo "查看服务状态..."
            run_remote "docker compose -p xiyiyun -f docker-compose.prod.yml ps"
            ;;
        2)
            echo "查看容器日志（最近50行）..."
            run_remote "docker compose -p xiyiyun -f docker-compose.prod.yml logs --tail=50"
            ;;
        3)
            echo "启动所有服务..."
            run_remote "docker compose -p xiyiyun -f docker-compose.prod.yml up -d"
            ;;
        4)
            echo "重启所有服务..."
            run_remote "docker compose -p xiyiyun -f docker-compose.prod.yml restart"
            ;;
        5)
            echo "停止所有服务..."
            run_remote "docker compose -p xiyiyun -f docker-compose.prod.yml down"
            ;;
        6)
            echo "查看环境配置（隐藏敏感信息）..."
            run_remote "cat .env | grep -v PASSWORD | grep -v SECRET | grep -v BCRYPT"
            ;;
        7)
            echo "运行完整检查..."
            run_remote "chmod +x scripts/check-server-status.sh && ./scripts/check-server-status.sh"
            ;;
        8)
            echo "进入服务器..."
            ssh ${USER}@${SERVER}
            ;;
        0)
            echo "退出"
            exit 0
            ;;
        *)
            echo "无效选择，请重试"
            ;;
    esac

    echo ""
    read -p "按回车键继续..."
    clear
done
