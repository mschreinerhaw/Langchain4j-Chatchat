import os
import ssl
from openai import DefaultHttpx2Client, OpenAI, __version__

ca = ssl.create_default_context(cafile=os.environ["GATEWAY_TEST_CA"])
client = OpenAI(
    base_url=os.environ["GATEWAY_BASE_URL"],
    api_key=os.environ["SDK_GATEWAY_KEY"],
    http_client=DefaultHttpx2Client(verify=ca, trust_env=False),
    max_retries=0,
)
model = "sdk-model"
assert any(entry.id == model for entry in client.models.list().data)
messages = [{"role": "user", "content": "Please reply."}]
reply = client.chat.completions.create(model=model, messages=messages)
assert reply.choices[0].message.content == "网关正常"
with client.chat.completions.create(model=model, messages=messages, stream=True) as events:
    text = "".join(event.choices[0].delta.content or "" for event in events if event.choices)
assert "你好" in text
print(f"OpenAI Python SDK {__version__} HTTPS models + JSON + SSE PASS")
