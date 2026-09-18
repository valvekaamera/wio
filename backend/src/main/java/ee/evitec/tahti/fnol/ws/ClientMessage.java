package ee.evitec.tahti.fnol.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ee.evitec.tahti.fnol.audio.PcmFormat;

/** JSON control message from client to server. Only {@code type} is mandatory. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientMessage(String type, String sessionId, String device, String language, PcmFormat format) {

    public static final String START = "start";
    public static final String TRANSCRIBE = "transcribe";
    public static final String STOP = "stop";
}
