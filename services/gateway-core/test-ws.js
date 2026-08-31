import WebSocket from 'ws';

const ws = new WebSocket('ws://localhost:3000');

ws.on('open', () => {
  console.log('Connected to Dockerized Gateway! Sending intent payload...');

  const payload = {
    action: 'EVALUATE_INTENT',
    payload: {
      user_id: 'usr_docker_test',
      input_type: 'TEXT',
      text_prompt: 'Transfer $100 to Bob',
      current_app_package: 'com.banking.app'
    }
  };

  ws.send(JSON.stringify(payload));
});

ws.on('message', (data) => {
  console.log('Received response from Docker pipeline:\n', JSON.parse(data.toString()));
  ws.close();
});