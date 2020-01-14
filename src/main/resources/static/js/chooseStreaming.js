var ws = new WebSocket('wss://' + location.host + '/call');

window.onload = function() {
    console = new Console();
}


function selectStreaming() {
	if (document.getElementById('streamname').value == '') {
		window.alert('You must specify the peer name');
		return;
    }

	console.log('Asking for stream');
	var message = {
		id : 'discoverStreams',
		stream : document.getElementById('streamname').value
	};
	sendMessage(message);
}

ws.onmessage = function(message) {
	var parsedMessage = JSON.parse(message.data);
	console.info('Received message: ' + message.data);

	switch (parsedMessage.id) {
	case 'discoverStreamResponse':
		discoverStreamsResponse(parsedMessage);
		break;
	default:
		console.error('Unrecognized message', parsedMessage);
	}
}

function discoverStreamsResponse(message) {
	if (message.response == 'accepted') {
        window.location.href = ("ver.html?streaming=" + document.getElementById('streamname').value)
	} else {
		console.log(message.data);
		alert('Couldn\'t connect to stream. See console for further information.');
	}
}

/* Send Message */
function sendMessage(message) {
	var jsonMessage = JSON.stringify(message);
	console.log('Sending message: ' + jsonMessage);
	ws.send(jsonMessage);
}
