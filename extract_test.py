import json, sys, time, urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "llama3.1:8b"

SYSTEM = """You extract the target mobile app from a voice command.
Reply with ONLY JSON: {"app_name": string or null, "task": string}.
- app_name: the app the user wants to use, copied exactly as spoken. null if the command names no app.
- task: the rest of the command, copied word for word (do not rephrase). "" if the user only wants to open the app.
Never invent an app name that is not in the command.

Command: "order pizza on swiggy" -> {"app_name":"swiggy","task":"order pizza"}
Command: "open camera" -> {"app_name":"camera","task":""}
Command: "open face book" -> {"app_name":"face book","task":""}
Command: "scroll down" -> {"app_name":null,"task":"scroll down"}
Command: "message rahul on telegram saying hello" -> {"app_name":"telegram","task":"message rahul saying hello"}"""

TESTS = [
    "book a cab on Ola","go back","tap the red button","search for bread on blinkit", "send a message on whatsapp",
    "open youtube", "book a cab to Thane on uber", "click search",
    "play some music on spotify", "open chart GPT", "whatsapp mom hi",
]

def extract(transcript):
    body = {
        "model": MODEL, "stream": False, "format": "json", "keep_alive": "30m",
        "options": {"temperature": 0, "num_predict": 60, "num_ctx": 1024},
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": f'Command: "{transcript}"'}],
    }
    req = urllib.request.Request(OLLAMA, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=300) as resp:
        out = json.load(resp)
    return json.loads(out["message"]["content"]), time.time() - start

print(f"Model: {MODEL}")
_, warm = extract("open camera")
print(f"warm-up (model load): {warm:.1f}s\n")

total = 0
for t in TESTS:
    try:
        result, secs = extract(t)
        total += secs
        print(f"{secs:5.1f}s  {t!r:36} -> {result}")
    except Exception as e:
        print(f"FAILED {t!r}: {e}")
print(f"\nmean: {total/len(TESTS):.1f}s per call")