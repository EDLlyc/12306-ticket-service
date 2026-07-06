# Local GGUF Import for Ollama

When `ollama pull qwen2.5:*` is slow or times out, prefer local import instead of repeatedly pulling from the default remote registry.

## Recommended flow

1. Download a `Qwen2.5` GGUF file from a faster source available to you.
2. Place it on the host filesystem, for example:

```bash
mkdir -p /root/models/qwen2.5
```

3. Create a temporary Modelfile:

```text
FROM /root/models/qwen2.5/qwen2.5-7b-instruct-q4_k_m.gguf

PARAMETER temperature 0
PARAMETER num_ctx 4096

SYSTEM """
你是 12306 智能体中的本地辅助模型。
你的职责是处理轻量、结构化、可判定的任务：
- 意图打分
- 问题改写
- 政策问题分类
- 政策证据判断
"""
```

4. Import into Ollama:

```bash
docker cp /root/models/qwen2.5/qwen2.5-7b-instruct-q4_k_m.gguf ollama:/root/
docker exec ollama sh -lc 'cat >/root/Modelfile <<EOF
FROM /root/qwen2.5-7b-instruct-q4_k_m.gguf
PARAMETER temperature 0
PARAMETER num_ctx 4096
SYSTEM """
你是 12306 智能体中的本地辅助模型。
你的职责是处理轻量、结构化、可判定的任务：
- 意图打分
- 问题改写
- 政策问题分类
- 政策证据判断
"""
EOF
ollama create qwen2.5:7b -f /root/Modelfile'
```

5. Verify:

```bash
docker exec ollama ollama list
```

## Why this route

- avoids repeated remote registry timeouts
- fits domestic network conditions better
- works cleanly with your existing `OLLAMA_AUX_MODEL=qwen2.5:7b` config
