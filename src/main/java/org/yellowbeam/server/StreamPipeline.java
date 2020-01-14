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

import java.util.HashMap;

import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;

/**
 * Media Pipeline (WebRTC endpoints, i.e. Kurento Media Elements) and connections for the 1 to 1
 * video communication.
 * 
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author Micael Gallego (micael.gallego@gmail.com)
 * @since 4.3.1
 */
public class StreamPipeline {

  private String stream; //only initialized when a stream is started

  private MediaPipeline pipeline;
  private UserSession caller;
  private UserSession callee;
  private WebRtcEndpoint callerWebRtcEp;
  private WebRtcEndpoint calleeWebRtcEp;
  private HashMap<String,WebRtcEndpoint> viewerWebRtcEp;

  public StreamPipeline(KurentoClient kurento, UserSession caller, UserSession callee) {
    try {
      this.pipeline = kurento.createMediaPipeline();
      this.callerWebRtcEp = new WebRtcEndpoint.Builder(pipeline).build();
      this.calleeWebRtcEp = new WebRtcEndpoint.Builder(pipeline).build();

      this.caller = caller;
      this.callee = callee;

      this.callerWebRtcEp.connect(this.calleeWebRtcEp);
      this.calleeWebRtcEp.connect(this.callerWebRtcEp);
      this.viewerWebRtcEp = new HashMap<>();
    } catch (Throwable t) {
      if (this.pipeline != null) {
        pipeline.release();
      }
    }
  }

  public void setStreamName(String name){
    this.stream = name;
  }

  public String getStream(){
    return stream;
  }
  public String generateSdpAnswerForCaller(String sdpOffer) {
    return callerWebRtcEp.processOffer(sdpOffer);
  }

  public String generateSdpAnswerForCallee(String sdpOffer) {
    return calleeWebRtcEp.processOffer(sdpOffer);
  }

  public void release() {
    if (pipeline != null) {
      pipeline.release();
    }
  }

  public WebRtcEndpoint getViewerWebRtcEp(String sessionID){
    return viewerWebRtcEp.get(sessionID);
  }

  public UserSession getCaller(){
    return caller;
  }

  public UserSession getCallee(){
    return callee;
  }

  public HashMap<String, WebRtcEndpoint> getAllViewersWebRtcEp(){
    return viewerWebRtcEp;
  }

  /**
   * 
   * @param sessionId
   * @return 0 on success
   */
  public WebRtcEndpoint addViewerWebRtcEp(String sessionId){
    WebRtcEndpoint vRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
    return vRtcEndpoint;
  }
  
  public WebRtcEndpoint getCallerWebRtcEp() {
    return callerWebRtcEp;
  }

  public WebRtcEndpoint getCalleeWebRtcEp() {
    return calleeWebRtcEp;
  }

  public void stopStream() {
    stream = null;
    viewerWebRtcEp.clear();
  }


}
