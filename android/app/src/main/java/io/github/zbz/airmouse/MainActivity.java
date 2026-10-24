package io.github.zbz.airmouse;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private AirMouseConfig config;
    private AirMouseUdpClient udpClient;
    private String connectedHost;
    private int connectedPort;
    private AirMouseUdpClient.ConnectionResult connectionResult;
    private TextView statusView;
    private EditText manualHostInput;
    private EditText manualPortInput;
    private Button manualConnectButton;
    private String pendingSessionToken;
    private final Object moveLock = new Object();
    private float pendingMoveDx;
    private float pendingMoveDy;
    private boolean moveFlushScheduled;
    private long lastMoveFlushMillis;
    private CalibrationStore calibrationStore;
    private CalibrationController calibrationController;
    private AirMouseOrientationEngine orientationEngine;
    private CursorPreviewView cursorPreview;
    private TextView calibStageView;
    private TextView calibInstructionView;
    private TextView calibProgressView;
    private Button calibActionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = AirMouseConfig.from(getResources());
        calibrationStore = new CalibrationStore(this);
        showConnectionScreen();
    }

    @Override
    protected void onPause() {
        stopOrientationEngine();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cleanupSensors();
        closeClientOnly();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showConnectionScreen() {
        cleanupSensors();
        connectedHost = null;
        connectedPort = 0;
        connectionResult = null;
        pendingSessionToken = null;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = verticalLayout(config.screenPaddingPx);
        scrollView.addView(root);

        TextView title = heading(getString(R.string.connection_title), config.titleTextSizePx);
        root.addView(title);

        Button scanQr = new Button(this);
        scanQr.setText(R.string.scan_qr_placeholder);
        scanQr.setAllCaps(false);
        root.addView(scanQr, matchWrap());

        statusView = body(getString(R.string.default_status));
        root.addView(statusView);

        TextView manualTitle = heading(getString(R.string.manual_connection), config.sectionTitleTextSizePx);
        root.addView(manualTitle);

        manualHostInput = new EditText(this);
        manualHostInput.setHint(R.string.host_hint);
        manualHostInput.setSingleLine(true);
        manualHostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(manualHostInput, matchWrap());

        manualPortInput = new EditText(this);
        manualPortInput.setHint(R.string.port_hint);
        manualPortInput.setSingleLine(true);
        manualPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        manualPortInput.setText(String.format(Locale.US, "%d", config.defaultPort));
        root.addView(manualPortInput, matchWrap());

        manualConnectButton = new Button(this);
        manualConnectButton.setText(R.string.connect);
        manualConnectButton.setAllCaps(false);
        root.addView(manualConnectButton, matchWrap());

        scanQr.setOnClickListener(view -> scanQrCode());
        manualConnectButton.setOnClickListener(view -> connectManually(manualHostInput, manualPortInput, manualConnectButton));

        setContentView(scrollView);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                setStatus(getString(R.string.qr_scan_cancelled));
            } else {
                handleQrPayload(result.getContents());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void scanQrCode() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt(getString(R.string.qr_scan_prompt));
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    private void handleQrPayload(String contents) {
        try {
            JSONObject payload = new JSONObject(contents);
            if (!AirMouseProtocol.PROTOCOL_NAME.equals(payload.optString(AirMouseProtocol.KEY_PROTOCOL))) {
                setStatus(getString(R.string.qr_scan_invalid));
                return;
            }
            String host = payload.optString(AirMouseProtocol.KEY_HOST).trim();
            int port = payload.optInt(AirMouseProtocol.KEY_PORT, config.defaultPort);
            if (host.isEmpty() || port < config.minPort || port > config.maxPort) {
                setStatus(getString(R.string.qr_scan_invalid));
                return;
            }
            pendingSessionToken = payload.optString(AirMouseProtocol.KEY_SESSION_TOKEN, null);
            manualHostInput.setText(host);
            manualPortInput.setText(String.format(Locale.US, "%d", port));
            setStatus(getString(R.string.qr_scan_ready));
            connectManually(manualHostInput, manualPortInput, manualConnectButton);
        } catch (JSONException exc) {
            setStatus(getString(R.string.qr_scan_invalid));
        }
    }

    private void connectManually(EditText hostInput, EditText portInput, Button connectButton) {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            setStatus(getString(R.string.invalid_host));
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException exc) {
            setStatus(getString(R.string.invalid_port));
            return;
        }
        if (port < config.minPort || port > config.maxPort) {
            setStatus(getString(R.string.invalid_port));
            return;
        }

        connectButton.setEnabled(false);
        setStatus(getString(R.string.connecting_status));
        networkExecutor.execute(() -> {
            AirMouseUdpClient candidate = null;
            try {
                candidate = new AirMouseUdpClient(host, port, pendingSessionToken, config);
                AirMouseUdpClient.ConnectionResult result = candidate.hello();
                AirMouseUdpClient connectedClient = candidate;
                connectedClient.startReceiver(() -> mainHandler.post(this::handleServerDisconnect));
                mainHandler.post(() -> {
                    udpClient = connectedClient;
                    connectedHost = host;
                    connectedPort = port;
                    connectionResult = result;
                    showModeSelectionScreen();
                });
            } catch (Exception exc) {
                if (candidate != null) {
                    candidate.close();
                }
                String message = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
                mainHandler.post(() -> {
                    connectButton.setEnabled(true);
                    setStatus(getString(R.string.connection_failed, message));
                });
            }
        });
    }

    private void showMainScreen() {
        cleanupSensors();
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = verticalLayout(config.compactScreenPaddingPx);
        scrollView.addView(root);

        TextView title = heading(getString(R.string.app_name), config.titleTextSizePx);
        root.addView(title);

        statusView = body(getString(
                R.string.connection_info,
                connectedHost,
                connectedPort,
                connectionResult.screenWidth,
                connectionResult.screenHeight,
                connectionResult.serverSensitivity
        ));
        root.addView(statusView);

        Button disconnectButton = new Button(this);
        disconnectButton.setText(R.string.disconnect);
        disconnectButton.setAllCaps(false);
        disconnectButton.setOnClickListener(view -> {
            disconnectFromUi();
            showConnectionScreen();
            setStatus(getString(R.string.disconnected_status));
        });
        root.addView(disconnectButton, matchWrap());

        TextView controlTitle = heading(getString(R.string.manual_control), config.sectionTitleTextSizePx);
        root.addView(controlTitle);

        TouchpadView touchpad = new TouchpadView(
                connectionResult.screenWidth,
                connectionResult.screenHeight,
                config,
                (dx, dy) -> queueMove(dx, dy)
        );
        root.addView(touchpad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout clickRow = horizontalLayout();
        Button leftClick = actionButton(getString(R.string.left_click));
        Button rightClick = actionButton(getString(R.string.right_click));
        clickRow.addView(leftClick, weightedButtonParams());
        clickRow.addView(rightClick, weightedButtonParams());
        root.addView(clickRow);

        LinearLayout scrollRow = horizontalLayout();
        Button scrollUp = actionButton(getString(R.string.scroll_up));
        Button scrollDown = actionButton(getString(R.string.scroll_down));
        scrollRow.addView(scrollUp, weightedButtonParams());
        scrollRow.addView(scrollDown, weightedButtonParams());
        root.addView(scrollRow);

        leftClick.setOnTouchListener(repeatingAction(
                getString(R.string.left_click),
                client -> client.sendClick(AirMouseProtocol.BUTTON_LEFT)
        ));
        rightClick.setOnTouchListener(repeatingAction(
                getString(R.string.right_click),
                client -> client.sendClick(AirMouseProtocol.BUTTON_RIGHT)
        ));
        scrollUp.setOnTouchListener(repeatingAction(getString(R.string.scroll_up), client -> client.sendScroll(1)));
        scrollDown.setOnTouchListener(repeatingAction(getString(R.string.scroll_down), client -> client.sendScroll(-1)));

        setContentView(scrollView);
    }

    private void queueMove(float dx, float dy) {
        synchronized (moveLock) {
            pendingMoveDx += dx;
            pendingMoveDy += dy;
            if (moveFlushScheduled) {
                return;
            }
            long elapsed = System.currentTimeMillis() - lastMoveFlushMillis;
            long delay = Math.max(0, config.moveSendIntervalMs - elapsed);
            moveFlushScheduled = true;
            mainHandler.postDelayed(this::flushMove, delay);
        }
    }

    private void flushMove() {
        float dx;
        float dy;
        synchronized (moveLock) {
            dx = pendingMoveDx;
            dy = pendingMoveDy;
            pendingMoveDx = 0;
            pendingMoveDy = 0;
            moveFlushScheduled = false;
            lastMoveFlushMillis = System.currentTimeMillis();
        }
        sendMove(dx, dy);
        synchronized (moveLock) {
            if ((pendingMoveDx != 0 || pendingMoveDy != 0) && !moveFlushScheduled) {
                moveFlushScheduled = true;
                mainHandler.postDelayed(this::flushMove, config.moveSendIntervalMs);
            }
        }
    }

    private void sendMove(float dx, float dy) {
        if (dx == 0 && dy == 0) {
            return;
        }
        AirMouseUdpClient client = udpClient;
        if (client == null) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                client.sendMove(dx, dy);
            } catch (Exception ignored) {
            }
        });
    }

    private void sendAckAction(String label, NetworkAction action) {
        AirMouseUdpClient client = udpClient;
        if (client == null) {
            return;
        }
        setStatus(getString(R.string.action_pending, label));
        networkExecutor.execute(() -> {
            try {
                action.run(client);
                mainHandler.post(() -> setStatus(getString(R.string.action_ok, label)));
            } catch (Exception exc) {
                String message = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
                mainHandler.post(() -> setStatus(getString(R.string.action_failed, label, message)));
            }
        });
    }

    private void disconnectFromUi() {
        cleanupSensors();
        AirMouseUdpClient client = udpClient;
        udpClient = null;
        if (client != null) {
            networkExecutor.execute(() -> {
                client.sendDisconnect();
                client.close();
            });
        }
    }

    private void closeClientOnly() {
        AirMouseUdpClient client = udpClient;
        udpClient = null;
        if (client != null) {
            client.close();
        }
    }

    private void handleServerDisconnect() {
        if (udpClient == null) {
            return;
        }
        closeClientOnly();
        showConnectionScreen();
        setStatus(getString(R.string.server_disconnected_status));
    }

    private void showModeSelectionScreen() {
        cleanupSensors();
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = verticalLayout(config.screenPaddingPx);
        scrollView.addView(root);

        root.addView(heading(getString(R.string.mode_selection_title), config.titleTextSizePx));
        statusView = body(getString(
                R.string.connection_info,
                connectedHost,
                connectedPort,
                connectionResult.screenWidth,
                connectionResult.screenHeight,
                connectionResult.serverSensitivity
        ));
        root.addView(statusView);

        Button manualButton = new Button(this);
        manualButton.setText(R.string.mode_manual);
        manualButton.setAllCaps(false);
        manualButton.setOnClickListener(view -> showMainScreen());
        root.addView(manualButton, matchWrap());

        Button sensorButton = new Button(this);
        sensorButton.setText(R.string.mode_sensor);
        sensorButton.setAllCaps(false);
        sensorButton.setOnClickListener(view -> showCalibrationScreen(false));
        root.addView(sensorButton, matchWrap());

        Button disconnectButton = new Button(this);
        disconnectButton.setText(R.string.disconnect);
        disconnectButton.setAllCaps(false);
        disconnectButton.setOnClickListener(view -> {
            disconnectFromUi();
            showConnectionScreen();
            setStatus(getString(R.string.disconnected_status));
        });
        root.addView(disconnectButton, matchWrap());

        setContentView(scrollView);
    }

    private void showCalibrationScreen(boolean forceRecalibrate) {
        cleanupSensors();
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = verticalLayout(config.screenPaddingPx);
        scrollView.addView(root);

        root.addView(heading(getString(R.string.calibration_title), config.titleTextSizePx));
        statusView = body(getString(R.string.calibration_intro));
        root.addView(statusView);

        calibStageView = body("");
        calibStageView.setVisibility(View.GONE);
        root.addView(calibStageView);
        calibInstructionView = body("");
        calibInstructionView.setVisibility(View.GONE);
        root.addView(calibInstructionView);
        calibProgressView = body("");
        calibProgressView.setVisibility(View.GONE);
        root.addView(calibProgressView);

        calibActionButton = new Button(this);
        calibActionButton.setAllCaps(false);
        calibActionButton.setVisibility(View.GONE);
        root.addView(calibActionButton, matchWrap());

        if (!forceRecalibrate && calibrationStore.hasCalibration()) {
            Button useSaved = new Button(this);
            useSaved.setText(R.string.calibration_use_saved);
            useSaved.setAllCaps(false);
            useSaved.setOnClickListener(view -> showSensorModeScreen(calibrationStore.load()));
            root.addView(useSaved, matchWrap());

            Button recalibrate = new Button(this);
            recalibrate.setText(R.string.calibration_recalibrate);
            recalibrate.setAllCaps(false);
            recalibrate.setOnClickListener(view -> showCalibrationScreen(true));
            root.addView(recalibrate, matchWrap());
        } else {
            Button start = new Button(this);
            start.setText(R.string.calibration_start);
            start.setAllCaps(false);
            start.setOnClickListener(view -> {
                start.setVisibility(View.GONE);
                startCalibrationFlow();
            });
            root.addView(start, matchWrap());
        }

        setContentView(scrollView);
    }

    private void startCalibrationFlow() {
        calibrationController = new CalibrationController(this, config, new CalibrationController.Callback() {
            @Override
            public void onStageChanged(CalibrationController.Stage stage, AccelCalibrator.Position accelPosition) {
                calibStageView.setVisibility(View.VISIBLE);
                calibInstructionView.setVisibility(View.VISIBLE);
                calibProgressView.setVisibility(View.VISIBLE);
                switch (stage) {
                    case GYRO:
                        calibStageView.setText(R.string.calib_stage_gyro);
                        calibInstructionView.setText(R.string.calib_gyro_instruction);
                        calibActionButton.setVisibility(View.GONE);
                        break;
                    case ACCEL:
                        calibStageView.setText(R.string.calib_stage_accel);
                        calibInstructionView.setText(getString(
                                R.string.calib_accel_instruction, accelPositionDescription(accelPosition)));
                        calibActionButton.setVisibility(View.VISIBLE);
                        calibActionButton.setEnabled(true);
                        calibActionButton.setText(R.string.calib_capture);
                        calibActionButton.setOnClickListener(view -> {
                            calibActionButton.setEnabled(false);
                            calibrationController.captureAccelPosition();
                        });
                        break;
                    case MAG:
                        calibStageView.setText(R.string.calib_stage_mag);
                        calibInstructionView.setText(R.string.calib_mag_instruction);
                        calibActionButton.setVisibility(View.VISIBLE);
                        calibActionButton.setEnabled(false);
                        calibActionButton.setText(R.string.calib_finish);
                        calibActionButton.setOnClickListener(view -> calibrationController.finishMag());
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onProgress(int percent) {
                calibProgressView.setText(getString(R.string.calib_progress, percent));
            }

            @Override
            public void onMagReady() {
                calibActionButton.setEnabled(true);
            }

            @Override
            public void onComplete(SensorCalibration calibration) {
                calibrationStore.save(calibration);
                calibrationController = null;
                setStatus(getString(R.string.calib_saved));
                showSensorModeScreen(calibration);
            }
        });

        if (!calibrationController.hasRequiredSensors()) {
            calibrationController = null;
            setStatus(getString(R.string.sensor_unavailable));
            return;
        }
        calibrationController.begin();
    }

    private String accelPositionDescription(AccelCalibrator.Position position) {
        switch (position) {
            case Z_UP:
                return getString(R.string.accel_pos_z_up);
            case Z_DOWN:
                return getString(R.string.accel_pos_z_down);
            case X_UP:
                return getString(R.string.accel_pos_x_up);
            case X_DOWN:
                return getString(R.string.accel_pos_x_down);
            case Y_UP:
                return getString(R.string.accel_pos_y_up);
            case Y_DOWN:
            default:
                return getString(R.string.accel_pos_y_down);
        }
    }

    private void showSensorModeScreen(SensorCalibration calibration) {
        cleanupSensors();
        int screenWidth = connectionResult.screenWidth;
        int screenHeight = connectionResult.screenHeight;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = verticalLayout(config.compactScreenPaddingPx);
        scrollView.addView(root);

        root.addView(heading(getString(R.string.sensor_mode_title), config.titleTextSizePx));
        statusView = body(getString(R.string.sensor_status_idle));
        root.addView(statusView);

        cursorPreview = new CursorPreviewView(this, config, screenWidth, screenHeight);
        root.addView(cursorPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        orientationEngine = new AirMouseOrientationEngine(
                this,
                calibration,
                config,
                screenWidth,
                screenHeight,
                new AirMouseOrientationEngine.Listener() {
                    @Override
                    public void onMove(float dx, float dy) {
                        sendMove(dx, dy);
                    }

                    @Override
                    public void onClick(String button) {
                        sensorSendClick(button);
                        if (cursorPreview != null) {
                            cursorPreview.triggerClickBlink();
                        }
                    }

                    @Override
                    public void onScroll(int amount) {
                        sensorSendScroll(amount);
                        if (cursorPreview != null) {
                            cursorPreview.triggerScroll(amount);
                        }
                    }
                });

        if (!orientationEngine.hasRequiredSensors()) {
            orientationEngine = null;
            setStatus(getString(R.string.sensor_unavailable));
        } else {
            AirMouseUdpClient client = udpClient;
            if (client != null) {
                client.setCursorListener((x, y) -> mainHandler.post(() -> {
                    if (cursorPreview != null && screenWidth > 0 && screenHeight > 0) {
                        cursorPreview.setCursorNormalized((float) x / screenWidth, (float) y / screenHeight);
                    }
                }));
            }
        }

        Button startStop = new Button(this);
        startStop.setText(R.string.sensor_start);
        startStop.setAllCaps(false);
        startStop.setOnClickListener(new View.OnClickListener() {
            private boolean tracking;

            @Override
            public void onClick(View view) {
                if (orientationEngine == null) {
                    return;
                }
                tracking = !tracking;
                if (tracking) {
                    orientationEngine.start();
                    startStop.setText(R.string.sensor_stop);
                    setStatus(getString(R.string.sensor_status_tracking));
                } else {
                    orientationEngine.stop();
                    startStop.setText(R.string.sensor_start);
                    setStatus(getString(R.string.sensor_status_stopped));
                }
            }
        });
        root.addView(startStop, matchWrap());

        Button recalibrate = new Button(this);
        recalibrate.setText(R.string.calibration_recalibrate);
        recalibrate.setAllCaps(false);
        recalibrate.setOnClickListener(view -> showCalibrationScreen(true));
        root.addView(recalibrate, matchWrap());

        Button disconnectButton = new Button(this);
        disconnectButton.setText(R.string.disconnect);
        disconnectButton.setAllCaps(false);
        disconnectButton.setOnClickListener(view -> {
            disconnectFromUi();
            showConnectionScreen();
            setStatus(getString(R.string.disconnected_status));
        });
        root.addView(disconnectButton, matchWrap());

        setContentView(scrollView);
    }

    private void sensorSendClick(String button) {
        AirMouseUdpClient client = udpClient;
        if (client == null) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                client.sendClick(button);
            } catch (Exception ignored) {
            }
        });
    }

    private void sensorSendScroll(int amount) {
        AirMouseUdpClient client = udpClient;
        if (client == null) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                client.sendScroll(amount);
            } catch (Exception ignored) {
            }
        });
    }

    private void stopOrientationEngine() {
        if (orientationEngine != null) {
            orientationEngine.stop();
        }
    }

    private void cleanupSensors() {
        if (orientationEngine != null) {
            orientationEngine.stop();
            orientationEngine = null;
        }
        if (calibrationController != null) {
            calibrationController.cancel();
            calibrationController = null;
        }
        AirMouseUdpClient client = udpClient;
        if (client != null) {
            client.setCursorListener(null);
        }
        cursorPreview = null;
    }

    private void setStatus(String message) {
        if (statusView != null) {
            statusView.setText(message);
        }
    }

    private LinearLayout verticalLayout(int paddingPx) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return layout;
    }

    private LinearLayout horizontalLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        return layout;
    }

    private TextView heading(String text, float sizePx) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(config.textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        view.setGravity(Gravity.START);
        view.setPadding(0, 0, 0, config.sectionSpacingPx);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(config.textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, config.bodyTextSizePx);
        view.setPadding(0, config.touchpadCornerRadiusPx, 0, config.sectionSpacingPx);
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private View.OnTouchListener repeatingAction(String label, NetworkAction action) {
        return new RepeatActionTouchListener(label, action);
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, config.mediumSpacingPx, 0, config.largeSpacingPx);
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(config.smallSpacingPx, config.smallSpacingPx, config.smallSpacingPx, config.smallSpacingPx);
        return params;
    }

    private interface NetworkAction {
        void run(AirMouseUdpClient client) throws Exception;
    }

    private interface MoveSender {
        void send(float dx, float dy);
    }

    private final class RepeatActionTouchListener implements View.OnTouchListener {
        private final String label;
        private final NetworkAction action;
        private Runnable repeatRunnable;

        RepeatActionTouchListener(String label, NetworkAction action) {
            this.label = label;
            this.action = action;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    sendAckAction(label, action);
                    scheduleRepeat();
                    return true;
                case MotionEvent.ACTION_UP:
                    stopRepeat();
                    view.setPressed(false);
                    view.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopRepeat();
                    view.setPressed(false);
                    return true;
                default:
                    return true;
            }
        }

        private void scheduleRepeat() {
            stopRepeat();
            repeatRunnable = new Runnable() {
                @Override
                public void run() {
                    sendAckAction(label, action);
                    mainHandler.postDelayed(this, config.holdRepeatIntervalMs);
                }
            };
            mainHandler.postDelayed(repeatRunnable, config.holdRepeatInitialDelayMs);
        }

        private void stopRepeat() {
            if (repeatRunnable != null) {
                mainHandler.removeCallbacks(repeatRunnable);
                repeatRunnable = null;
            }
        }
    }

    private final class TouchpadView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float aspectRatio;
        private final AirMouseConfig config;
        private final MoveSender moveSender;
        private float lastX;
        private float lastY;
        private boolean dragging;

        TouchpadView(int screenWidth, int screenHeight, AirMouseConfig config, MoveSender moveSender) {
            super(MainActivity.this);
            this.config = config;
            this.moveSender = moveSender;
            if (screenWidth > 0 && screenHeight > 0) {
                this.aspectRatio = (float) screenWidth / (float) screenHeight;
            } else {
                this.aspectRatio = (float) config.fallbackAspectWidth / (float) config.fallbackAspectHeight;
            }
            setFocusable(true);
            setClickable(true);
            fillPaint.setColor(config.touchpadFillColor);
            strokePaint.setColor(config.touchpadStrokeColor);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(config.touchpadStrokeWidthPx);
            textPaint.setColor(config.touchpadStrokeColor);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(config.touchpadTextSizePx);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width == 0) {
                width = config.touchpadDefaultWidthPx;
            }
            int desiredHeight = Math.round(width / aspectRatio);
            int height = Math.max(config.touchpadMinHeightPx, Math.min(desiredHeight, config.touchpadMaxHeightPx));
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = config.touchpadCornerRadiusPx;
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, fillPaint);
            canvas.drawRoundRect(
                    config.touchpadStrokeWidthPx,
                    config.touchpadStrokeWidthPx,
                    getWidth() - config.touchpadStrokeWidthPx,
                    getHeight() - config.touchpadStrokeWidthPx,
                    radius,
                    radius,
                    strokePaint
            );
            canvas.drawText(getString(R.string.touchpad_label), getWidth() / 2f, getHeight() / 2f, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        float x = event.getX();
                        float y = event.getY();
                        moveSender.send(x - lastX, y - lastY);
                        lastX = x;
                        lastY = y;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
