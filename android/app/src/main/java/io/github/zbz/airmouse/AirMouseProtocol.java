package io.github.zbz.airmouse;

final class AirMouseProtocol {
    static final String KEY_ACK = "ack";
    static final String KEY_AMOUNT = "amount";
    static final String KEY_BUTTON = "button";
    static final String KEY_DX = "dx";
    static final String KEY_DY = "dy";
    static final String KEY_HOST = "host";
    static final String KEY_PORT = "port";
    static final String KEY_PROTOCOL = "protocol";
    static final String KEY_SCREEN_HEIGHT = "screenHeight";
    static final String KEY_SCREEN_WIDTH = "screenWidth";
    static final String KEY_SEQ = "seq";
    static final String KEY_SERVER_SENSITIVITY = "serverSensitivity";
    static final String KEY_SESSION_TOKEN = "sessionToken";
    static final String KEY_STATUS = "status";
    static final String KEY_TIMESTAMP = "timestamp";
    static final String KEY_TYPE = "type";
    static final String KEY_X = "x";
    static final String KEY_Y = "y";

    static final String TYPE_CLICK = "click";
    static final String TYPE_CURSOR = "cursor";
    static final String TYPE_DISCONNECT = "disconnect";
    static final String TYPE_HELLO = "hello";
    static final String TYPE_HELLO_ACK = "hello_ack";
    static final String TYPE_MOVE = "move";
    static final String TYPE_SCROLL = "scroll";

    static final String STATUS_OK = "ok";
    static final String PROTOCOL_NAME = "airmouse";
    static final String BUTTON_LEFT = "left";
    static final String BUTTON_RIGHT = "right";

    private AirMouseProtocol() {
    }
}
