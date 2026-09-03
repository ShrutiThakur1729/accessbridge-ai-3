import { WebSocket } from 'ws';

// Connect to your Gateway
const ws = new WebSocket('ws://localhost:3000');

ws.on('open', () => {
  console.log('Connected to Gateway!');
  
  // Simulate a real payload sent by Android
  const mockAndroidPayload = {
    type: "USER_INTENT",
    task_id: "test-task-101",
    transcript: "Open Settings and turn on WiFi",
    user_prompt: "Open Settings and turn on WiFi",
    session_id: "session-101",
    current_package_name: "com.android.launcher",
    screenElements: [
      {
        text: "Settings",
        view_id: "com.android.launcher:id/icon_settings",
        is_clickable: true
      }
    ]
  };

  console.log('Sending mock Android payload...');
  ws.send(JSON.stringify(mockAndroidPayload));
});

ws.on('message', (data) => {
  console.log('\nRECEIVED DIRECTIVE FROM BACKEND AI:');
  console.log(JSON.parse(data.toString()));
  ws.close();
});

ws.on('error', (err) => {
  console.error('WebSocket Error:', err.message);
});