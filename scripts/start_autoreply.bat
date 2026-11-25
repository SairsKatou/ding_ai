@echo off
chcp 65001 >nul
title 钉钉 @我 消息 AI 自动回复服务
cd /d "%~dp0.."
echo 正在启动钉钉 @我 消息 AI 自动回复服务...
python scripts/dingtalk_autoreply.py
pause
