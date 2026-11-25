# -*- coding: utf-8 -*-
"""
钉钉 @我 消息大模型自动回复服务
读取本地工程 src/main/resources/application.properties 配置的模型服务，
结合 dws (DingTalk Workspace CLI) 实现自动监听和引用回复。
"""

import os
import re
import json
import subprocess
import requests

# 本地配置文件路径
CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(CURRENT_DIR)
PROP_FILE_PATH = os.path.join(PROJECT_ROOT, "src", "main", "resources", "application.properties")

def load_properties(filepath):
    """解析 Java properties 配置文件"""
    props = {}
    if not os.path.exists(filepath):
        print(f"⚠️ 未找到配置文件: {filepath}")
        return props

    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            if "=" in line:
                key, val = line.split("=", 1)
                props[key.strip()] = val.strip()
    return props

def get_model_config():
    """获取本地配置的模型参数"""
    props = load_properties(PROP_FILE_PATH)

    api_url = props.get("opencode.apiUrl", "https://opencode.ai/zen/go/v1/chat/completions")
    api_key = props.get("opencode.apiKey", "")
    primary_model = props.get("opencode.model", "deepseek-v4.1-flash")

    # 收集多模型配置
    models = []
    if primary_model:
        models.append(primary_model)

    # 查找配置中的备选模型
    index = 0
    while True:
        model_key = f"opencode.models[{index}].model"
        if model_key in props:
            m = props[model_key]
            if m and m not in models:
                models.append(m)
            index += 1
        else:
            break

    temperature = float(props.get("opencode.temperature", "0.7"))
    max_tokens = int(props.get("opencode.maxTokens", "2048"))

    return {
        "api_url": api_url,
        "api_key": api_key,
        "models": models,
        "temperature": temperature,
        "max_tokens": max_tokens
    }

CONFIG = get_model_config()

def ask_local_model(prompt, sender_name):
    """
    调用本地配置的大模型服务生成回复（支持多模型轮询故障转移）
    """
    api_url = CONFIG["api_url"]
    api_key = CONFIG["api_key"]
    models = CONFIG["models"]
    temperature = CONFIG["temperature"]
    max_tokens = CONFIG["max_tokens"]

    headers = {
        "Content-Type": "application/json",
        "x-opencode-session": "opencode-robot-session"
    }
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    system_prompt = (
        f"你是王伟在钉钉上的智能工作助理。同事【{sender_name}】在群聊/单聊中@了王伟。\n"
        "请根据同事提出的问题给出简洁、专业、清晰的解答。\n"
        "如果属于需要王伟本人线下确认/审批/改单等具体业务操作，请礼貌告知已记录，稍后王伟本人会亲自跟进处理。"
    )

    for model_name in models:
        payload = {
            "model": model_name,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": prompt}
            ],
            "temperature": temperature,
            "max_tokens": max_tokens
        }

        try:
            print(f"正在尝试使用模型【{model_name}】生成回复...")
            resp = requests.post(api_url, json=payload, headers=headers, timeout=60)
            if resp.status_code == 200:
                data = resp.json()
                content = data["choices"][0]["message"]["content"].strip()
                print(f"模型【{model_name}】生成成功！")
                return content
            else:
                print(f"模型【{model_name}】请求失败 (HTTP {resp.status_code}): {resp.text}")
        except Exception as e:
            print(f"模型【{model_name}】调用异常: {e}")

    return "收到，我稍后处理回复你。"

def reply_via_dws(ref_msg_id, reply_text):
    """通过 dws 发送引用回复"""
    content = f"[AI代回] {reply_text}"
    cmd = [
        "dws", "chat", "+messages-reply",
        "--ref-msg-id", ref_msg_id,
        "--content", content,
        "--yes"
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        if res.returncode == 0:
            print(f"✅ 成功回复消息 ID: {ref_msg_id}")
        else:
            print(f"❌ 回复消息失败: {res.stderr}")
    except Exception as e:
        print(f"执行 dws 回复指令异常: {e}")

def main():
    print("=" * 60)
    print("🤖 钉钉 @我 消息 AI 自动回复服务启动中...")
    print(f"📌 模型接口: {CONFIG['api_url']}")
    print(f"📌 可用模型列表: {CONFIG['models']}")
    print("=" * 60)

    # 启动 dws 个人事件监听进程
    cmd = ["dws", "event", "+listen-im", "--kind", "at-me", "-f", "ndjson"]
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    processed_ids = set()
    print("👂 正在通过 dws 监听 @我 的实时消息...")

    try:
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
                # 兼容不同层级的字段
                msg_id = event.get("messageId") or event.get("openMessageId")
                sender = event.get("sender") or event.get("senderName") or "同事"
                text = event.get("text") or event.get("content") or ""

                if not msg_id or msg_id in processed_ids:
                    continue
                processed_ids.add(msg_id)

                print(f"\n📩 收到来自【{sender}】的 @消息 (ID: {msg_id}):\n   内容: {text}")

                # 剔除 @占位字符
                clean_question = re.sub(r"@\S+\s*", "", text).strip()
                if not clean_question:
                    print("⚠️ 消息无有效提问内容，跳过回复")
                    continue

                # 调用本地配置模型生成回答
                ai_reply = ask_local_model(clean_question, sender)
                print(f"💡 回复内容:\n{ai_reply}")

                # 通过 dws 引用回复
                reply_via_dws(msg_id, ai_reply)

            except json.JSONDecodeError:
                continue
            except Exception as e:
                print(f"处理事件异常: {e}")
    except KeyboardInterrupt:
        print("\n服务已手动停止。")
    finally:
        proc.terminate()

if __name__ == "__main__":
    main()
