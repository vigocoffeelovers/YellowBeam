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

package org.yellowbeam.server;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.client.WebRtcEndpoint;
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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Protocol handler for 1 to 1 video call communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author Micael Gallego (micael.gallego@gmail.com)
 * @since 4.3.1
 */
public class CallHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
  private static final Gson gson = new GsonBuilder().create();

  private final ConcurrentHashMap<String, StreamPipeline> pipelines = new ConcurrentHashMap<>();  //This one is to atach each user session with a pipeline
  private final ConcurrentHashMap<String, StreamPipeline> streams = new ConcurrentHashMap<>();  //This one ataches eachs pipeline to a stream identifier

  @Autowired
  private KurentoClient kurento;

  @Autowired
  private UserRegistry registry;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
    UserSession user = registry.getBySession(session);

    if (user != null) {
      log.debug("Incoming message from user '{}': {}", user.getName(), jsonMessage);
    } else {
      log.debug("Incoming message from new user: {}", jsonMessage);
    }

    switch (jsonMessage.get("id").getAsString()) {
      case "register":
        try {
          register(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "registerResponse");
        }
        break;
      case "call":
        try {
          call(user, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "callResponse");
        }
        break;
      case "incomingCallResponse":
        incomingCallResponse(user, jsonMessage);
        break;
      case "initStream":
        try {
          initStream(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "initStreamResponse");
        }
        break;
      case "discoverStreams":
        try {
          discoverStreams(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "discoverStreamResponse");
        }
        break;
      case "streamRequest":
        try {
          streamRequest(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "streamResponse");
        }
        break;
      case "viewerResponse":
        try {
          viewerResponse(user, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "streamResponse");
        }
        break;
      case "onIceCandidate": {
        JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();
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
      case "stopStream":
        stopStream(session);
        break;
      default:
        break;
    }
  }

  private void handleErrorResponse(Throwable throwable, WebSocketSession session, String responseId)
      throws IOException {
    stop(session);
    log.error(throwable.getMessage(), throwable);
    JsonObject response = new JsonObject();
    response.addProperty("id", responseId);
    response.addProperty("response", "rejected");
    response.addProperty("message", throwable.getMessage());
    session.sendMessage(new TextMessage(response.toString()));
  }

  private void register(WebSocketSession session, JsonObject jsonMessage) throws IOException {
    String name = jsonMessage.getAsJsonPrimitive("name").getAsString();

    UserSession caller = new UserSession(session, name);
    String responseMsg = "accepted";
    if (name.isEmpty()) {
      responseMsg = "rejected: empty user name";
    } else if (registry.exists(name)) {
      responseMsg = "rejected: user '" + name + "' already registered";
    } else {
      registry.register(caller);
    }

    JsonObject response = new JsonObject();
    response.addProperty("id", "registerResponse");
    response.addProperty("response", responseMsg);
    caller.sendMessage(response);
  }

  private void call(UserSession caller, JsonObject jsonMessage) throws IOException {
    String to = jsonMessage.get("to").getAsString();
    String from = jsonMessage.get("from").getAsString();
    JsonObject response = new JsonObject();

    if (registry.exists(to)) {
      caller.setSdpOffer(jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
      caller.setCallingTo(to);

      response.addProperty("id", "incomingCall");
      response.addProperty("from", from);

      UserSession callee = registry.getByName(to);
      callee.sendMessage(response);
      callee.setCallingFrom(from);
    } else {
      response.addProperty("id", "callResponse");
      response.addProperty("response", "rejected: user '" + to + "' is not registered");

      caller.sendMessage(response);
    }
  }

  private void incomingCallResponse(final UserSession callee, JsonObject jsonMessage)
      throws IOException {
    String callResponse = jsonMessage.get("callResponse").getAsString();
    String from = jsonMessage.get("from").getAsString();
    final UserSession calleer = registry.getByName(from);
    String to = calleer.getCallingTo();

    if ("accept".equals(callResponse)) {
      log.debug("Accepted call from '{}' to '{}'", from, to);

      StreamPipeline pipeline = null;
      try {
        pipeline = new StreamPipeline(kurento, calleer, callee);
        pipelines.put(calleer.getSessionId(), pipeline);
        pipelines.put(callee.getSessionId(), pipeline);

        callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEp());
        pipeline.getCalleeWebRtcEp().addIceCandidateFoundListener(
            new EventListener<IceCandidateFoundEvent>() {

              @Override
              public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "iceCandidate");
                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                try {
                  synchronized (callee.getSession()) {
                    callee.getSession().sendMessage(new TextMessage(response.toString()));
                  }
                } catch (IOException e) {
                  log.debug(e.getMessage());
                }
              }
            });

        calleer.setWebRtcEndpoint(pipeline.getCallerWebRtcEp());
        pipeline.getCallerWebRtcEp().addIceCandidateFoundListener(
            new EventListener<IceCandidateFoundEvent>() {

              @Override
              public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "iceCandidate");
                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                try {
                  synchronized (calleer.getSession()) {
                    calleer.getSession().sendMessage(new TextMessage(response.toString()));
                  }
                } catch (IOException e) {
                  log.debug(e.getMessage());
                }
              }
            });

        String calleeSdpOffer = jsonMessage.get("sdpOffer").getAsString();
        String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
        JsonObject startCommunication = new JsonObject();
        startCommunication.addProperty("id", "startCommunication");
        startCommunication.addProperty("sdpAnswer", calleeSdpAnswer);

        synchronized (callee) {
          callee.sendMessage(startCommunication);
        }

        pipeline.getCalleeWebRtcEp().gatherCandidates();

        String callerSdpOffer = registry.getByName(from).getSdpOffer();
        String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);
        JsonObject response = new JsonObject();
        response.addProperty("id", "callResponse");
        response.addProperty("response", "accepted");
        response.addProperty("sdpAnswer", callerSdpAnswer);

        synchronized (calleer) {
          calleer.sendMessage(response);
        }

        pipeline.getCallerWebRtcEp().gatherCandidates();

      } catch (Throwable t) {
        log.error(t.getMessage(), t);

        if (pipeline != null) {
          pipeline.release();
        }

        pipelines.remove(calleer.getSessionId());
        pipelines.remove(callee.getSessionId());

        JsonObject response = new JsonObject();
        response.addProperty("id", "callResponse");
        response.addProperty("response", "rejected");
        calleer.sendMessage(response);

        response = new JsonObject();
        response.addProperty("id", "stopCommunication");
        callee.sendMessage(response);
      }

    } else {
      JsonObject response = new JsonObject();
      response.addProperty("id", "callResponse");
      response.addProperty("response", "rejected");
      calleer.sendMessage(response);
    }
  }

  private void viewerResponse(final UserSession streamer, JsonObject jsonMessage)
      throws IOException {
    String callResponse = jsonMessage.get("callResponse").getAsString();
    String from = jsonMessage.get("from").getAsString();
    final UserSession calleer = registry.getByName(from);
    String to = calleer.getCallingTo();

    if ("accept".equals(callResponse)) {
      log.debug("Accepted call from '{}' to '{}'", from, to);
      
      StreamPipeline pipeline = pipelines.get(streamer.getSessionId());

      String calleeSdpOffer = jsonMessage.get("sdpOffer").getAsString();
      String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
      JsonObject startCommunication = new JsonObject();
      startCommunication.addProperty("id", "startCommunication");
      startCommunication.addProperty("sdpAnswer", calleeSdpAnswer);

      synchronized (streamer) {
        streamer.sendMessage(startCommunication);
      }

      pipeline.getCalleeWebRtcEp().gatherCandidates();
    }
  }
  /**
   * Stream start
   * @param session
   * @param jsonMessage
   * @throws IOException
   */
  private void initStream(final WebSocketSession session, JsonObject jsonMessage) throws IOException {

    String streamName = jsonMessage.get("stream").getAsString();  //Stream Identifier
    StreamPipeline streamPipeline = pipelines.get(session.getId());

    if(registry.getBySession(session) == null){
      JsonObject response = new JsonObject();
      response.addProperty("id", "initStreamResponse");
      response.addProperty("response", "rejected: User is not registered. ");
      session.sendMessage(new TextMessage(response.toString()));
      log.debug("User '{}' is not registered. ", registry.getBySession(session).getName());

    } else if(pipelines == null) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "initStreamResponse");
      response.addProperty("response", "rejected: Call is not ready. ");
      session.sendMessage(new TextMessage(response.toString()));
      log.debug(" Call is not ready. ");

    } else if(streams.containsKey(streamName)){
      JsonObject response = new JsonObject();
      response.addProperty("id", "initStreamResponse");
      response.addProperty("response", "rejected: Stream name is already on use. ");
      session.sendMessage(new TextMessage(response.toString()));
      log.debug("Stream name '{}' is already on use. ", streamName);
    
    } else {
      streamPipeline.setStreamName(streamName);
      streams.put(streamName, streamPipeline);
      JsonObject response = new JsonObject();
      response.addProperty("id", "initStreamResponse");
      response.addProperty("response", "accepted");
      session.sendMessage(new TextMessage(response.toString()));
      log.debug("Starting Stream '{}'. ", streamName);

    }
  }

  /**
   * A user solicites the avaliable videos on a stream
   * @param session
   * @param jsonMessage
   * @throws IOException
   */
  private void discoverStreams(final WebSocketSession session, JsonObject jsonMessage) throws IOException {

    String stream = jsonMessage.get("stream").getAsString();  //Stream Identifier

    //Check if the stream exists
    if(!streams.containsKey(stream)) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "discoverStreamResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message", "Unkown Stream Identifier");
      session.sendMessage(new TextMessage(response.toString()));

    } else {
      JsonObject response = new JsonObject();
      response.addProperty("id", "discoverStreamResponse");
      response.addProperty("response", "accepted");
      JsonArray vids = new JsonArray();
      vids.add("callerVid");
      vids.add("calleeVid");
      response.add("videos", vids);
      
      
      synchronized (session) {
        session.sendMessage(new TextMessage(response.toString()));
      }

    }
  }

  /**
   * A user entabloishes a conection to a stream
   * @param session
   * @param jsonMessage
   */
  private void streamRequest(final WebSocketSession session, JsonObject jsonMessage) throws IOException{

    String stream = jsonMessage.get("stream").getAsString();  //Stream Identifier
    String video = jsonMessage.get("video").getAsString();

    //Check if the stream exists
    if(!streams.containsKey(stream)) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "streamResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message", "Unkown Stream Identifier");
      session.sendMessage(new TextMessage(response.toString()));

    } else if(!video.equals("callerVid") && !video.equals("calleeVid")) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "streamResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message", "Unkown Requested Video");
      session.sendMessage(new TextMessage(response.toString()));

    } else {
      StreamPipeline streamPipeline = streams.get(stream);
      String sessionId = session.getId();

      WebRtcEndpoint vRtcEndpoint = streamPipeline.addViewerWebRtcEp(sessionId);

      vRtcEndpoint.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
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

      System.out.println("Recieved: " + video);
      //On future the diferent videos will be maped on a HashMap<String, WebRtcEndPoint>
      if(video.equals("callerVid")){
        streamPipeline.getCallerWebRtcEp().connect(vRtcEndpoint);

      } else {
        streamPipeline.getCalleeWebRtcEp().connect(vRtcEndpoint);
      }

      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
      String sdpAnswer = vRtcEndpoint.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "streamResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (session) {
        session.sendMessage(new TextMessage(response.toString()));
      }
      vRtcEndpoint.gatherCandidates();
      log.debug("Viewer started viewing the vid: '{}' stream: '{}' ", video, stream);
    }
      
  }

  public void stopStream(WebSocketSession session) throws IOException {
    StreamPipeline pipeline = pipelines.get(session.getId());
    String stream = pipeline.getStream();
    if(streams.containsKey(stream)){
      streams.remove(stream);

      pipeline.stopStream();
    }
  }

  public void stop(WebSocketSession session) throws IOException {
    String sessionId = session.getId();
    if (pipelines.get(sessionId) == null ) {
      return;
    }
    if (pipelines.get(sessionId).getStream() != null){
        stopStream(session);
    }
    if (pipelines.containsKey(sessionId)) {
      pipelines.get(sessionId).release();
      StreamPipeline pipeline = pipelines.remove(sessionId);
      pipeline.release();

      // Both users can stop the communication. A 'stopCommunication'
      // message will be sent to the other peer.
      UserSession stopperUser = registry.getBySession(session);
      if (stopperUser != null) {
        UserSession stoppedUser =
            (stopperUser.getCallingFrom() != null) ? registry.getByName(stopperUser
                .getCallingFrom()) : stopperUser.getCallingTo() != null ? registry
                    .getByName(stopperUser.getCallingTo()) : null;

                    if (stoppedUser != null) {
                      JsonObject message = new JsonObject();
                      message.addProperty("id", "stopCommunication");
                      stoppedUser.sendMessage(message);
                      stoppedUser.clear();
                    }
                    stopperUser.clear();
      }

    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    stop(session);
    registry.removeBySession(session);
  }

}
