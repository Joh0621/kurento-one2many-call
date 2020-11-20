let ws;
let video;
let webRtcPeer;
let wsUrl = 'ws://140.210.90.228:8443' + '/rtsp';
let timer = null;
window.onload = () => {
  video = document.getElementById('video');
}

window.onbeforeunload = () => {
  stop(true);
}

document.getElementById('play').addEventListener('click', () => {
  document.getElementsByName('tag').forEach(el => {
    el.disabled = true;
  });
  document.getElementById('play').disabled = true;
  if (!ws) {
    initWS();
  }
  let options = {
    remoteVideo: video,
    onicecandidate : onIceCandidate,
    configuration: {
      iceServers: [{
        urls : ['stun:140.210.90.228:3478']
      }, {
        urls : ['turn:140.210.90.228:3478'],
        username : 'kurento',
        credential : 'kurento',
      }]
    }
  }
  timer = setInterval(() => {
    if (ws) {
      if (ws.readyState == 1) {
        webRtcPeer = kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly(options, error => {
          if(error) return;
          webRtcPeer.generateOffer(onOffer);
        });
        clearInterval(timer);
      } else if (ws.readyState == 2 || ws.readyState == 3) {
        clearInterval(timer);
      }
    } else {
      clearInterval(timer);
    }
  }, 10);
  
});

document.getElementById('stop').addEventListener('click', () => {
  stop(true);
});


function sendMessage (message) {
  let jsonMessage = JSON.stringify(message);
  console.log('Sending message: ' + jsonMessage);
  ws.send(jsonMessage);
}

function onOffer(error, offerSdp) {
  if (error) return;
  let message = {
    type: 'play',
    tag: getTag(),
    sdpOffer : offerSdp
  }
  sendMessage(message);
}


function initWS () {
  ws = new WebSocket(wsUrl);
  ws.onmessage = message => {
    let parsedMessage = JSON.parse(message.data);
    console.info('Received message: ' + message.data);

    switch (parsedMessage.type) {
      case 'playResponse':
        playResponse(parsedMessage);
        break;
      case 'iceCandidate':
        webRtcPeer.addIceCandidate(parsedMessage.candidate)
        break;
      default:
        stop();
        debugger;
        console.error('Unrecognized message', parsedMessage.message);
    }
  }
}

function playResponse(message) {
  if (message.response != 'accepted') {
    let errorMsg = message.message ? message.message : 'Unknow error';
    console.warn('Call not accepted for the following reason: ' + errorMsg);
    dispose();
  } else {
    webRtcPeer.processAnswer(message.sdpAnswer);
  }
}



function onIceCandidate(candidate) {
  console.log('Local candidate' + JSON.stringify(candidate));
  let message = {
    type: 'onIceCandidate',
    candidate : candidate
  }
  sendMessage(message);
}

function stop (isActive = false) {
  if (isActive) {
    let message = {
      type: 'stop'
    };
    sendMessage(message);
  }
  if (ws) {
    ws.close();
    ws = null;
  }
  if (webRtcPeer) {
    webRtcPeer.dispose();
    webRtcPeer = null;
  }
}

function getTag () {
  let nodes = document.querySelectorAll('[name="tag"]');
  for (let i = 0; i <= nodes.length; i++) {
    if (nodes[i].checked) return Number(nodes[i].value);
  }
  return 1;
}
