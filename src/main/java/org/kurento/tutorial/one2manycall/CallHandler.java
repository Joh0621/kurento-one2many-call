/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.one2manycall;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Protocol handler for 1 to N video call communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
public class CallHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
  private static final Gson gson = new GsonBuilder().create();

  private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();

  @Autowired
  private KurentoClient kurento;

  private MediaPipeline pipeline;
  private UserSession presenterUserSession;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
    log.debug("Incoming message from session '{}': {}", session.getId(), jsonMessage);

    //presenterUserSession = new UserSession(session);
    MediaPipeline pipeline = kurento.createMediaPipeline();
    // presenterUserSession.setWebRtcEndpoint(new WebRtcEndpoint.Builder(pipeline).build());
    String videourl = "rtsp://yxxny:yxxny12345@vpn.yongx.net:1554/h264/ch33/main/av_stream";
   // final PlayerEndpoint playerEndpoint = new PlayerEndpoint.Builder(pipeline, videourl).build() ;

    final PlayerEndpoint playerEndpoint = new PlayerEndpoint.Builder(pipeline, videourl).withNetworkCache(0).build();


    playerEndpoint.play();
    switch (jsonMessage.get("type").getAsString()) {
      case "presenter":
        try {
          presenter(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "presenterResponse");
        }
        break;
      case "play":
        try {
          play(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "playResponse");
        }
        break;
      case "onIceCandidate": {
        JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

        UserSession user = null;
        if (presenterUserSession != null) {
          if (presenterUserSession.getSession() == session) {
            user = presenterUserSession;
          } else {
            user = viewers.get(session.getId());
          }
        }
        if (user != null) {
          IceCandidate cand =
                  new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                          .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(cand);
        }
        break;
      }
      case "stop":
        stop(session);
        break;
      default:
        break;
    }
  }

  private void handleErrorResponse(Throwable throwable, WebSocketSession session, String onError)
          throws IOException {
    stop(session);
    log.error(throwable.getMessage(), throwable);
    JsonObject response = new JsonObject();
    response.addProperty("type", onError);
    response.addProperty("response", "rejected");
    response.addProperty("message", throwable.getMessage());
    session.sendMessage(new TextMessage(response.toString()));
  }

  private synchronized void presenter(final WebSocketSession session, JsonObject jsonMessage)
          throws IOException {



  }


  private void pause(String sessionId) {
    UserSession user = users.get(sessionId);

    if (user != null) {
      user.getPlayerEndpoint().pause();
    }
  }

  private void resume(final WebSocketSession session) {
    UserSession user = users.get(session.getId());

    if (user != null) {
      user.getPlayerEndpoint().play();
      VideoInfo videoInfo = user.getPlayerEndpoint().getVideoInfo();

      JsonObject response = new JsonObject();
      response.addProperty("id", "videoInfo");
      response.addProperty("isSeekable", videoInfo.getIsSeekable());
      response.addProperty("initSeekable", videoInfo.getSeekableInit());
      response.addProperty("endSeekable", videoInfo.getSeekableEnd());
      response.addProperty("videoDuration", videoInfo.getDuration());
      sendMessage(session, response.toString());
    }
  }


  private synchronized void play(final WebSocketSession session, JsonObject jsonMessage)
          throws IOException {

    if (viewers.containsKey(session.getId())) {
      JsonObject response = new JsonObject();
      response.addProperty("type", "playResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message", "您已经在此会话中查看，请使用其他浏览器添加其他查看器");
      session.sendMessage(new TextMessage(response.toString()));
      return;
    }
    UserSession play = new UserSession(session);
    viewers.put(session.getId(), play);

    MediaPipeline pipeline = kurento.createMediaPipeline();

    WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

    play.setWebRtcEndpoint(nextWebRtc);

    int tag = jsonMessage.get("tag").getAsInt();
    if (jsonMessage.get("tag").getAsInt() == 0) {
      tag=1;
    }

    int tagUrl = 32+tag;

    String videourl = "rtsp://yxxny:yxxny12345@vpn.yongx.net:1554/h264/ch"+ tagUrl+"/main/av_stream";

    final PlayerEndpoint  playerEndpoint = new PlayerEndpoint.Builder(pipeline, videourl).withNetworkCache(0).build();
    play.setPlayerEndpoint(playerEndpoint);
    users.put(session.getId(), play);

    playerEndpoint.connect(nextWebRtc);

    nextWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

      @Override
      public void onEvent(IceCandidateFoundEvent event) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "iceCandidate");
        response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
        try {
          synchronized (session) {
            session.sendMessage(new TextMessage(response.toString()));
          }
        } catch (IOException e) {
          log.debug(e.getMessage());
        }
      }
    });

    //  play.setWebRtcEndpoint(nextWebRtc);
    //  presenterUserSession.getWebRtcEndpoint().connect(nextWebRtc);
    String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
    String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

    JsonObject response = new JsonObject();
    response.addProperty("type", "playResponse");
    response.addProperty("response", "accepted");
    response.addProperty("sdpAnswer", sdpAnswer);

    synchronized (session) {
      play.sendMessage(response);
    }
    nextWebRtc.gatherCandidates();

    // 3. PlayEndpoint
    playerEndpoint.addErrorListener(new EventListener<ErrorEvent>() {
      @Override
      public void onEvent(ErrorEvent event) {
        log.info("ErrorEvent: {}", event.getDescription());
        sendPlayEnd(session);
      }
    });

    playerEndpoint.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
      @Override
      public void onEvent(EndOfStreamEvent event) {
        log.info("EndOfStreamEvent: {}", event.getTimestamp());
        sendPlayEnd(session);
      }
    });


    playerEndpoint.play();
  }





  private synchronized void stop(WebSocketSession session) throws IOException {
    String sessionId = session.getId();
    if (presenterUserSession != null && presenterUserSession.getSession().getId().equals(sessionId)) {
      for (UserSession play : viewers.values()) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "stopCommunication");
        play.sendMessage(response);
      }

      log.info("Releasing media pipeline");
      if (pipeline != null) {
        pipeline.release();
      }
      pipeline = null;
      presenterUserSession = null;
    } else if (viewers.containsKey(sessionId)) {
      if (viewers.get(sessionId).getWebRtcEndpoint() != null) {
        viewers.get(sessionId).getWebRtcEndpoint().release();
      }
      viewers.remove(sessionId);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    stop(session);
  }

  public void sendPlayEnd(WebSocketSession session) {
    if (users.containsKey(session.getId())) {
      JsonObject response = new JsonObject();
      response.addProperty("type", "playEnd");
      sendMessage(session, response.toString());
    }
  }


  private synchronized void sendMessage(WebSocketSession session, String message) {
    try {
      session.sendMessage(new TextMessage(message));
    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }

}
