import WebSocket from "ws";

const question = process.argv[2] || "Did you mean LinkedIn?";
const reply = process.argv[3] || "yeah that one";

const ws = new WebSocket("ws://localhost:3000/ws/agent");
ws.on("open", () => {
  ws.send(JSON.stringify({ message_type: "CLASSIFY_REPLY", request_id: "c1", question, reply }));
});
ws.on("message", (m) => { console.log(m.toString()); ws.close(); });
ws.on("error", (e) => console.error("ERR", e.message));