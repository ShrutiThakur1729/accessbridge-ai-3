import WebSocket from "ws";

const ws = new WebSocket("ws://localhost:3000/ws/agent");
ws.on("open", () => {
  ws.send(JSON.stringify({
    message_type: "EXTRACT_APP",
    request_id: "t1",
    transcript: process.argv[2] || "book a cab on Ola",
  }));
});
ws.on("message", (m) => { console.log(m.toString()); ws.close(); });
ws.on("error", (e) => console.error("ERR", e.message));