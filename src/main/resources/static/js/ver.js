const ws = new WebSocket('wss://' + location.host + '/call');

let webRtcPeer;

var stream2subscribe;
var containedVideos;
var selectedVideo;

var maingameplay;
var secondgameplay;

window.onload = function()
{
  stream2subscribe = new URLSearchParams(window.location.search).get('streaming');
  maingameplay = document.getElementById("maingameplay");
  secondgameplay = document.getElementById("secondgameplay");

  dragElement(document.getElementById("facecam1"));
  dragElement(document.getElementById("facecam2"));
  dragElement(document.getElementById("maingameplay"));
  dragElement(document.getElementById("secondgameplay"));
  console = new Console();
  this.discoverStreamsMessage();
}

window.onbeforeunload = function()
{
  console.log("Page unload - Close WebSocket");
  ws.close();
}



/* ============================= */
/* ==== WebSocket signaling ==== */
/* ============================= */

ws.onmessage = function(message)
{
  const jsonMessage = JSON.parse(message.data);
  console.log("[onmessage] Received message: " + message.data);

  switch (jsonMessage.id) {
    case 'discoverStreamsResponse':
      discoverStreamsResponse(jsonMessage);
      break;
    case 'streamResponse':
      handleProcessSdpAnswer(jsonMessage);
      break;
    case 'iceCandidate':
      webRtcPeer.addIceCandidate(jsonMessage.candidate, function(error) {
        if (error)
          return console.error('Error adding candidate: ' + error);
        });
      break;
    default:
      console.warn("[onmessage] Invalid message, id: " + jsonMessage.id);
      break;
  }
}

function discoverStreamsMessage() {
  var message = {
		id : 'discoverStreams',
		stream : stream2subscribe
	};
	sendMessage(message);
}

function discoverStreamsResponse(jsonMessage) {
  containedVideos = jsonMessage.videos;
  selectedVideo = containedVideos[0]; //TODO for with all array and create streamingDivs dynamically
  startStreaming(maingameplay);
  selectedVideo = containedVideos[1];
  startStreaming(secondgameplay);
}

function startStreaming(streamingDiv) {

  showSpinner(streamingDiv);
  var options = {
    remoteVideo : streamingDiv,
    onicecandidate : onIceCandidate
  }
  webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly(options,
    function(error) {
      if (error) {
        return console.error(error);
      }
      webRtcPeer.generateOffer(onOfferStream);
    });

}

function onOfferStream(error, offerSdp) {
	if (error)
		return console.error('Error generating the offer');
	console.log('Invoking SDP offer callback function');
	var message = {
    id : 'streamRequest',
    stream : stream2subscribe,
    video : selectedVideo,
		sdpOffer : offerSdp
	};
	sendMessage(message);
}

// PROCESS_SDP_ANSWER ----------------------------------------------------------
function handleProcessSdpAnswer(jsonMessage)
{
  console.log("[handleProcessSdpAnswer] SDP Answer from Kurento, process in WebRTC Peer");

  if (webRtcPeer == null) {
    console.warn("[handleProcessSdpAnswer] Skip, no WebRTC Peer");
    return;
  }

  webRtcPeer.processAnswer(jsonMessage.sdpAnswer, (err) => {
    if (err) {
      sendError("[handleProcessSdpAnswer] Error: " + err);
      stop();
      return;
    }

    console.log("[handleProcessSdpAnswer] SDP Answer ready; start remote video");
  });
}

// STOP ------------------------------------------------------------------------
function stop()
{

  console.log("[stop]");

  if (webRtcPeer) {
    webRtcPeer.dispose();
    webRtcPeer = null;
  }

  sendMessage({
    id: 'STOP',
  });
}

function showSpinner()
{
  for (let i = 0; i < arguments.length; i++) {
    arguments[i].poster = './img/transparent-1px.png';
    arguments[i].style.background = "center transparent url('./img/spinner.gif') no-repeat";
  }
}

function hideSpinner()
{
  for (let i = 0; i < arguments.length; i++) {
    arguments[i].src = '';
    arguments[i].poster = './img/webrtc.png';
    arguments[i].style.background = '';
  }
}

// ADD_ICE_CANDIDATE -----------------------------------------------------------
function handleAddIceCandidate(jsonMessage)
{
  if (webRtcPeer == null) {
    console.warn("[handleAddIceCandidate] Skip, no WebRTC Peer");
    return;
  }

  webRtcPeer.addIceCandidate(jsonMessage.candidate, (err) => {
    if (err) {
      console.error("[handleAddIceCandidate] " + err);
      return;
    }
  });
}

function onIceCandidate(candidate) {
	console.log("Local candidate" + JSON.stringify(candidate));

	var message = {
		id : 'onIceCandidate',
		candidate : candidate
	};
	sendMessage(message);
}


function explainUserMediaError(err)
{
  const n = err.name;
  if (n === 'NotFoundError' || n === 'DevicesNotFoundError') {
    return "Missing webcam for required tracks";
  }
  else if (n === 'NotReadableError' || n === 'TrackStartError') {
    return "Webcam is already in use";
  }
  else if (n === 'OverconstrainedError' || n === 'ConstraintNotSatisfiedError') {
    return "Webcam doesn't provide required tracks";
  }
  else if (n === 'NotAllowedError' || n === 'PermissionDeniedError') {
    return "Webcam permission has been denied by the user";
  }
  else if (n === 'TypeError') {
    return "No media tracks have been requested";
  }
  else {
    return "Unknown error: " + err;
  }
}

function sendError(message)
{
  console.error(message);

  sendMessage({
    id: 'ERROR',
    message: message,
  });
}

function sendMessage(message)
{
  if (ws.readyState !== ws.OPEN) {
    console.warn("[sendMessage] Skip, WebSocket session isn't open");
    return;
  }

  const jsonMessage = JSON.stringify(message);
  console.log("[sendMessage] message: " + jsonMessage);
  ws.send(jsonMessage);
}


/** Añadido por nosotros */

// Make the DIV element draggable:
//dragElement(document.getElementById("gamplay_left"));
//dragElement(document.getElementById("gamplay_right"));

function dragElement(elmnt) {
  var pos1 = 0, pos2 = 0, pos3 = 0, pos4 = 0;
  elmnt.onmousedown = dragMouseDown;

  function dragMouseDown(e) {
    e = e || window.event;
    e.preventDefault();
    // get the mouse cursor position at startup:
    pos3 = e.clientX;
    pos4 = e.clientY;
    document.onmouseup = closeDragElement;
    // call a function whenever the cursor moves:
    document.onmousemove = elementDrag;
  }

  function elementDrag(e) {
    e = e || window.event;
    e.preventDefault();
    // calculate the new cursor position:
    pos1 = pos3 - e.clientX;
    pos2 = pos4 - e.clientY;
    pos3 = e.clientX;
    pos4 = e.clientY;
    // set the element's new position:
    elmnt.style.top = (elmnt.offsetTop - pos2) + "px";
    elmnt.style.left = (elmnt.offsetLeft - pos1) + "px";
  }

  function closeDragElement() {
    // stop moving when mouse button is released:
    document.onmouseup = null;
    document.onmousemove = null;
  }
}

function makeResizable(div) {
  const element = document.querySelector(div);
  const resizers = document.querySelectorAll(div + ' .resizer')
  const minimum_size = 20;
  let original_width = 0;
  let original_height = 0;
  let original_x = 0;
  let original_y = 0;
  let original_mouse_x = 0;
  let original_mouse_y = 0;
  for (let i = 0;i < resizers.length; i++) {
    const currentResizer = resizers[i];
    currentResizer.addEventListener('mousedown', function(e) {
      e.preventDefault()
      original_width = parseFloat(getComputedStyle(element, null).getPropertyValue('width').replace('px', ''));
      original_height = parseFloat(getComputedStyle(element, null).getPropertyValue('height').replace('px', ''));
      original_x = element.getBoundingClientRect().left;
      original_y = element.getBoundingClientRect().top;
      original_mouse_x = e.pageX;
      original_mouse_y = e.pageY;
      window.addEventListener('mousemove', resize)
      window.addEventListener('mouseup', stopResize)
    })
    
    function resize(e) {
      if (currentResizer.classList.contains('bottom-right')) {
        const width = original_width + (e.pageX - original_mouse_x);
        const height = original_height + (e.pageY - original_mouse_y)
        if (width > minimum_size) {
          element.style.width = width + 'px'
        }
        if (height > minimum_size) {
          element.style.height = height + 'px'
        }
      }
      else if (currentResizer.classList.contains('bottom-left')) {
        const height = original_height + (e.pageY - original_mouse_y)
        const width = original_width - (e.pageX - original_mouse_x)
        if (height > minimum_size) {
          element.style.height = height + 'px'
        }
        if (width > minimum_size) {
          element.style.width = width + 'px'
          element.style.left = original_x + (e.pageX - original_mouse_x) + 'px'
        }
      }
      else if (currentResizer.classList.contains('top-right')) {
        const width = original_width + (e.pageX - original_mouse_x)
        const height = original_height - (e.pageY - original_mouse_y)
        if (width > minimum_size) {
          element.style.width = width + 'px'
        }
        if (height > minimum_size) {
          element.style.height = height + 'px'
          element.style.top = original_y + (e.pageY - original_mouse_y) + 'px'
        }
      }
      else {
        const width = original_width - (e.pageX - original_mouse_x)
        const height = original_height - (e.pageY - original_mouse_y)
        if (width > minimum_size) {
          element.style.width = width + 'px'
          element.style.left = original_x + (e.pageX - original_mouse_x) + 'px'
        }
        if (height > minimum_size) {
          element.style.height = height + 'px'
          element.style.top = original_y + (e.pageY - original_mouse_y) + 'px'
        }
      }
    }
    
    function stopResize() {
      window.removeEventListener('mousemove', resize)
    }
  }
}


/**
 * Lightbox utility (to display media pipeline image in a modal dialog)
 */
$(document).delegate('*[data-toggle="lightbox"]', 'click', function(event) {
  event.preventDefault();
  $(this).ekkoLightbox();
});