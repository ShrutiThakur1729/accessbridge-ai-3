import json, sys, time, urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "llama3.2:3b"

SYSTEM = """You classify a user's spoken reply to a question a voice assistant just asked.
Reply with ONLY JSON: {"answer": "YES" or "NO" or "OTHER"}.
- YES: the user agrees or confirms.
- NO: the user declines, or says the suggestion is wrong.
- OTHER: the reply is unclear, unrelated, or asks for something new instead of answering.
Judge the reply in the context of the question.

Question: "Did you mean Telegram?" Reply: "yeah that one" -> {"answer":"YES"}
Question: "Did you mean Telegram?" Reply: "no that's not it" -> {"answer":"NO"}
Question: "Did you mean Telegram?" Reply: "open the camera" -> {"answer":"OTHER"}
Question: "Do you want me to install Zomato?" Reply: "sounds good" -> {"answer":"YES"}
Question: "Do you want me to install Zomato?" Reply: "never mind" -> {"answer":"NO"}"""

Q1 = "Did you mean LinkedIn?"
Q2 = "I couldn't find Blinkit. Do you want me to look for it on the Play Store?"

# (question, reply, acceptable answers)
TESTS = [
    (Q1, "yup", {"YES"}),
    (Q1, "go for it", {"YES"}),
    (Q1, "yes please open it", {"YES"}),
    (Q1, "sure why not", {"YES"}),
    (Q1, "nope", {"NO"}),
    (Q1, "cancel", {"NO"}),
    (Q1, "not really", {"NO"}),
    (Q1, "no please don't open it", {"NO"}),
    (Q1, "open whatsapp", {"OTHER"}),
    (Q1, "what", {"OTHER"}),
    (Q1, "no, open uber instead", {"NO", "OTHER"}),
    (Q2, "yeah go ahead", {"YES"}),
    (Q2, "do it", {"YES"}),
    (Q2, "no leave it", {"NO"}),
    (Q2, "scroll down", {"OTHER"}),
    (Q2, "I don't think so", {"NO"}),
]

def classify(question, reply):
    body = {
        "model": MODEL, "stream": False, "format": "json", "keep_alive": "30m",
        "options": {"temperature": 0, "num_predict": 30, "num_ctx": 1024},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": f'Question: "{question}"\nReply: "{reply}"'},
        ],
    }
    req = urllib.request.Request(OLLAMA, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=300) as resp:
        out = json.load(resp)
    raw = out["message"]["content"]
    try:
        data = json.loads(raw)
        answer = str(data.get("answer", "")).strip().upper() if isinstance(data, dict) else ""
    except ValueError:
        answer = ""
    if answer not in {"YES", "NO", "OTHER"}:
        answer = "INVALID"
    return answer, time.time() - start

print(f"Model: {MODEL}")
_, warm = classify("Did you mean Telegram?", "yes")
print(f"warm-up: {warm:.1f}s\n")

passed, total_time = 0, 0.0
for q, r, ok in TESTS:
    answer, secs = classify(q, r)
    good = answer in ok
    passed += good
    total_time += secs
    print(f"{'PASS' if good else 'FAIL'} {secs:4.1f}s  {r!r:30} -> {answer:7} (wanted {'/'.join(sorted(ok))})")
print(f"\n{passed}/{len(TESTS)} correct, mean {total_time / len(TESTS):.1f}s per call")