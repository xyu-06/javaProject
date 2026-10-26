package com.embedded.blueserial;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import org.json.JSONArray;
import org.json.JSONObject;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Native Android app. Bluetooth Classic SPP only, no BLE/network dependencies. */
public final class MainActivity extends Activity {
    private static final int NAVY = 0xff0b1c2c;
    private static final int TEAL = 0xff039db0;
    private static final int TEXT = 0xff152b3c;
    private static final int MUTED = 0xff698497;
    private static final int BG = 0xfff1f6f9;
    private static final String DEFAULT_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    private static final String[] MACRO_LABELS = {"LED 开", "LED 关", "前进", "后退", "左转", "右转", "停止", "蜂鸣"};
    private static final String[] MACRO_COMMANDS = {"LED_ON", "LED_OFF", "F", "B", "L", "R", "S", "BEEP"};
    private static final int REQ_PERMISSION = 151, REQ_ENABLE = 152, REQ_EXPORT = 153;
    private static final int MAX_LOG = 28000;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSppClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private final StreamDecoder decoder = new StreamDecoder();
    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder logText = new StringBuilder();

    private LinearLayout root, header;
    private FrameLayout pageHost;
    private View[] pages = new View[4];
    private Button[] navButtons = new Button[4];
    private TextView connectionState, counterLabel, connectButtonText, outputLog, joyReadout, chartCounter, sendSuffixLabel;
    private ScrollView logScroller;
    private EditText sendInput, uuidEdit;
    private CheckBox showRxHex, sendAsHex, autoScroll;
    private Spinner endSelection;
    private LinearLayout macroList, sliderList, bottomNavigation;
    private final List<MacroEntry> macros = new ArrayList<>();
    private final List<SliderEntry> sliders = new ArrayList<>();
    private AxisJoystickView throttleJoystick, steeringJoystick;
    private JoystickView singleJoystick;
    private LinearLayout singleModePanel, dualModePanel, buttonModePanel;
    private int throttle = 0, steering = 0;
    private final boolean[] directionHeld = new boolean[4]; // 前、后、左、右
    private final long[] directionPressedAt = new long[4];
    private final Runnable buttonHeartbeat = new Runnable() {
        @Override public void run() {
            if (!anyDirectionHeld()) return;
            sendButtonMotion(false);
            handler.postDelayed(this, 90);
        }
    };
    private static final String MACRO_STORE = "macros_v2";
    private static final String SLIDER_STORE = "sliders_v2";
    private static final class MacroEntry {
        String label, command;
        MacroEntry(String a, String b) { label = a; command = b; }
    }
    private static final class SliderEntry {
        String id, label, prefix;
        int min, max, value;
        SliderEntry(String id, String label, String prefix, int min, int max, int value) {
            this.id=id; this.label=label; this.prefix=prefix; this.min=min; this.max=max; this.value=value;
        }
    }
    private OscilloscopeView chart;

    private boolean chartPaused = false, demoMode = false;
    private int demoTick = 0, receivedCount = 0, transmittedCount = 0;
    private int selectedTab = 0;
    private String currentDevice = "未连接";

    private final LinkedHashMap<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private final List<String> keys = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private AlertDialog deviceDialog;
    private boolean receiverRegistered = false;
    private boolean scanning = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action) || BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice found;
                if (Build.VERSION.SDK_INT >= 33)
                    found = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                else
                    found = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (found != null && hasPermissions()) {
                    try { devices.put(found.getAddress(), found); }
                    catch (SecurityException ignored) { }
                }
                updateDeviceList();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                updateDeviceList();
            }
        }
    };

    private final Runnable demoRunner = new Runnable() {
        @Override public void run() {
            if (!demoMode || isFinishing() || isDestroyed()) return;
            double t = demoTick++ * .12;
            chart.addSample(new float[]{(float) (50 + 25 * Math.sin(t)),
                (float) (50 + 18 * Math.cos(t * .6)), (float) (30 + 9 * Math.sin(t * 1.4))});
            updateChartCount();
            handler.postDelayed(this, 115);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("studio_prefs", MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        loadControls();
        client = new BluetoothSppClient(new BluetoothSppClient.Listener() {
            @Override public void onStatus(String status, boolean connected) {
                connectionState.setText(status);
                currentDevice = status;
                connectButtonText.setText(connected ? "断开" : "连接设备");
                connectionState.setTextColor(connected ? 0xff70e4cd : 0xffbbcad5);
                if (!connected) { decoder.reset(); stopAllMotion(); }
            }
            @Override public void onReceived(byte[] bytes) { onReceiveBytes(bytes); }
            @Override public void onSent(int count) {
                transmittedCount += count;
                updateCounters();
            }
            @Override public void onError(String message) { toast(message); addLog("!", message); }
        });
        registerBluetoothReceiver();
        buildInterface();
        openTab(0);
    }

    private void registerBluetoothReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill); gd.setCornerRadius(dp(radius));
        if (stroke != 0) gd.setStroke(dp(1), stroke);
        return gd;
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private LinearLayout.LayoutParams lparams(int w, int h) {
        return new LinearLayout.LayoutParams(w == -1 ? -1 : dp(w), h == -2 ? -2 : dp(h));
    }
    private void gap(LinearLayout layout, int h) { View v = new View(this); layout.addView(v, lparams(1, h)); }
    private Button btn(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(13); b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : NAVY);
        b.setBackground(shape(primary ? TEAL : 0xffeaf3f7, 0, 12));
        b.setMinHeight(dp(44)); b.setPadding(dp(8), dp(6), dp(8), dp(6));
        return b;
    }
    private EditText input(String hint, boolean multi) {
        EditText edit = new EditText(this);
        edit.setSingleLine(!multi);
        edit.setTextSize(14);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(0xff9caeb9);
        edit.setHint(hint);
        edit.setPadding(dp(13), dp(9), dp(13), dp(9));
        edit.setBackground(shape(Color.WHITE, 0xffdbe6ec, 11));
        if (multi) edit.setMinLines(2);
        return edit;
    }
    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout box = vertical();
        box.setPadding(dp(15), dp(14), dp(15), dp(14));
        box.setBackground(shape(Color.WHITE, 0xffe7edf1, 17));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        parent.addView(box, lp);
        if (!heading.isEmpty()) {
            box.addView(text(heading, 16, TEXT, true));
            gap(box, 12);
        }
        return box;
    }
    private LinearLayout pageBody(ScrollView scroll) {
        LinearLayout body = vertical();
        body.setPadding(dp(14), dp(17), dp(14), dp(26));
        scroll.setFillViewport(true);
        scroll.addView(body);
        return body;
    }
    private void addWeighted(LinearLayout row, View v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(47), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        row.addView(v, p);
    }
    private void notice(LinearLayout parent, String msg) {
        TextView hint = text(msg, 12, MUTED, false);
        hint.setLineSpacing(dp(2), 1);
        parent.addView(hint);
    }

    private void buildInterface() {
        root = vertical(); root.setBackgroundColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                boolean keyboard = insets.isVisible(WindowInsets.Type.ime());
                view.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
                if (header != null) header.setVisibility(keyboard ? View.GONE : View.VISIBLE);
                if (bottomNavigation != null) bottomNavigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
                return insets;
            });
        } else {
            getWindow().setStatusBarColor(NAVY);
            getWindow().setNavigationBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        setContentView(root);
        header = vertical(); header.setBackgroundColor(NAVY);
        header.setPadding(dp(19), dp(19), dp(19), dp(18));
        root.addView(header, lparams(-1, -2));
        TextView title = text("蓝牙串口实验室", 22, Color.WHITE, true);
        header.addView(title);
        gap(header, 5);
        TextView subtitle = text("Bluetooth Classic · SPP / RFCOMM", 11, 0xff8baebc, false);
        header.addView(subtitle);
        gap(header, 16);
        LinearLayout stateRow = horizontal();
        connectionState = text("未连接", 13, 0xffbbcad5, true);
        stateRow.addView(connectionState, new LinearLayout.LayoutParams(0, -2, 1));
        Button choose = btn("连接设备", true);
        connectButtonText = choose;
        stateRow.addView(choose, lparams(118, 43));
        choose.setOnClickListener(v -> { if (client.isConnected()) client.disconnect(); else chooseDevice(); });
        header.addView(stateRow);
        gap(header, 13);
        LinearLayout counterRow = horizontal();
        counterLabel = text("↑ TX 0 B      ↓ RX 0 B", 12, 0xffb2c9d4, false);
        counterRow.addView(counterLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Button resetCounters = btn("清空/归零", false);
        resetCounters.setTextSize(11);
        counterRow.addView(resetCounters, lparams(96, 39));
        resetCounters.setOnClickListener(v -> clearMonitoring());
        header.addView(counterRow);

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        pages[0] = createTerminalPage();
        pages[1] = createControlPage();
        pages[2] = createPlotPage();
        pages[3] = createSettingsPage();

        LinearLayout nav = horizontal();
        nav.setPadding(dp(10), dp(7), dp(10), dp(7));
        nav.setBackgroundColor(Color.WHITE);
        String[] titles = {"⌨ 串口", "✥ 遥控", "⌁ 曲线", "⚙ 设置"};
        for (int i = 0; i < 4; i++) {
            final int tab = i;
            Button button = btn(titles[i], false);
            navButtons[i] = button;
            button.setTextSize(12);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
            p.setMargins(dp(2), 0, dp(2), 0);
            nav.addView(button, p);
            button.setOnClickListener(v -> openTab(tab));
        }
        bottomNavigation = nav;
        root.addView(nav, lparams(-1, -2));
    }

    private void openTab(int tab) {
        if (selectedTab == 1 && tab != 1) stopAllMotion();
        selectedTab = tab;
        pageHost.removeAllViews();
        View page = pages[tab];
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < navButtons.length; i++) {
            navButtons[i].setBackground(shape(i == tab ? NAVY : 0xffedf4f7, 0, 12));
            navButtons[i].setTextColor(i == tab ? Color.WHITE : MUTED);
        }
    }

    private View createTerminalPage() {
        // Sending dock is anchored above the IME, rather than buried below a 265dp log view.
        LinearLayout page = vertical();
        ScrollView receiveScroll = new ScrollView(this);
        receiveScroll.setFillViewport(true);
        LinearLayout body = pageBody(receiveScroll);
        LinearLayout receive = card(body, "数据接收");
        LinearLayout toggles = horizontal();
        showRxHex = new CheckBox(this); showRxHex.setText("HEX 显示"); showRxHex.setTextSize(12);
        autoScroll = new CheckBox(this); autoScroll.setText("自动滚动"); autoScroll.setTextSize(12); autoScroll.setChecked(true);
        toggles.addView(showRxHex, new LinearLayout.LayoutParams(0, -2, 1));
        toggles.addView(autoScroll, new LinearLayout.LayoutParams(0, -2, 1));
        receive.addView(toggles);
        gap(receive, 7);
        logScroller = new ScrollView(this);
        logScroller.setFillViewport(true);
        logScroller.setBackground(shape(0xfff6fafc, 0xffe3edf2, 12));
        outputLog = text("等待串口数据…\n", 12, TEXT, false);
        outputLog.setTypeface(Typeface.MONOSPACE);
        outputLog.setGravity(Gravity.TOP | Gravity.START);
        outputLog.setPadding(dp(11), dp(11), dp(11), dp(11));
        outputLog.setTextIsSelectable(true);
        logScroller.addView(outputLog);
        receive.addView(logScroller, lparams(-1, 220));
        gap(receive, 12);
        LinearLayout actions = horizontal();
        Button clear = btn("清空 TX/RX", false), export = btn("导出 TXT", false);
        addWeighted(actions, clear); addWeighted(actions, export);
        receive.addView(actions);
        clear.setOnClickListener(v -> clearMonitoring());
        export.setOnClickListener(v -> exportLog());
        page.addView(receiveScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout dock = vertical();
        dock.setPadding(dp(12), dp(5), dp(12), dp(5));
        dock.setBackgroundColor(BG);
        LinearLayout send = card(dock, "数据发送");
        sendInput = input("输入串口指令，例如 LED_ON", true);
        sendInput.setMinLines(1);
        sendInput.setMaxLines(2);
        send.addView(sendInput, lparams(-1, 53));
        gap(send, 5);
        sendSuffixLabel = text("", 12, TEAL, true);
        send.addView(sendSuffixLabel);
        gap(send, 7);
        LinearLayout row = horizontal();
        sendAsHex = new CheckBox(this); sendAsHex.setText("HEX 发送"); sendAsHex.setTextSize(12);
        row.addView(sendAsHex, new LinearLayout.LayoutParams(0, -2, 1));
        Button sendButton = btn("发送  ↗", true);
        row.addView(sendButton, lparams(105, 45));
        send.addView(row);
        sendAsHex.setOnCheckedChangeListener((v, checked) -> refreshSendSuffix());
        sendButton.setOnClickListener(v -> manualSend());
        page.addView(dock, lparams(-1, -2));
        refreshSendSuffix();
        return page;
    }

    private void refreshSendSuffix() {
        if (sendSuffixLabel == null) return;
        if (sendAsHex != null && sendAsHex.isChecked()) {
            sendSuffixLabel.setText("HEX 模式：按原始字节发送，不添加结束符"); return;
        }
        int selected = preferences.getInt("suffix", 1);
        String shown = selected == 0 ? "无" : selected == 2 ? "\\r\\n" : selected == 3 ? "\\r" : "\\n";
        sendSuffixLabel.setText("本次发送自动追加：" + shown + "  （设置页修改后实时生效）");
    }

    private View createControlPage() {
        ScrollView page = new ScrollView(this);
        LinearLayout body = pageBody(page);
        LinearLayout remoteCard = card(body, "小车遥控 · 三种控制模式");
        notice(remoteCard, "三种模式共用 J,{x},{y} 数据包（可自定义）：X 转向、Y 前后速度。允许边前进边转向，手机端自动丢弃过期运动状态；STM32 必须使用组合差速而非 F/L 单独覆盖。");
        gap(remoteCard, 10);
        Spinner controlMode = new Spinner(this);
        String[] controlChoices = {"360° 单摇杆", "双摇杆（左前后 / 右转向）", "四按键（长按加速）"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, controlChoices);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        controlMode.setAdapter(modeAdapter);
        remoteCard.addView(controlMode, lparams(-1, 48));
        gap(remoteCard, 12);

        singleModePanel = vertical();
        notice(singleModePanel, "360°：向上前进、向下后退、左右转向；拖动越远，速度/转向越大。松开回中。");
        gap(singleModePanel, 8);
        LinearLayout singleRow = horizontal();
        singleRow.setGravity(Gravity.CENTER);
        singleJoystick = new JoystickView(this);
        singleRow.addView(singleJoystick, lparams(265, 265));
        singleModePanel.addView(singleRow);
        singleJoystick.setListener((x, y, released) -> {
            steering = x;
            throttle = y;
            sendDriveState(steering, throttle, released);
        });
        remoteCard.addView(singleModePanel);

        dualModePanel = vertical();
        notice(dualModePanel, "双指独立操作：左手控制前后速度，右手控制转向；各自松开只归零该轴。");
        gap(dualModePanel, 8);
        LinearLayout both = horizontal();
        LinearLayout leftBox = vertical(); leftBox.setGravity(Gravity.CENTER);
        LinearLayout rightBox = vertical(); rightBox.setGravity(Gravity.CENTER);
        leftBox.addView(text("左 · 前后速度", 13, TEAL, true));
        rightBox.addView(text("右 · 转向", 13, TEAL, true));
        throttleJoystick = new AxisJoystickView(this, AxisJoystickView.Axis.VERTICAL);
        steeringJoystick = new AxisJoystickView(this, AxisJoystickView.Axis.HORIZONTAL);
        leftBox.addView(throttleJoystick, lparams(-1, 155));
        rightBox.addView(steeringJoystick, lparams(-1, 155));
        both.addView(leftBox, new LinearLayout.LayoutParams(0, -2, 1));
        both.addView(rightBox, new LinearLayout.LayoutParams(0, -2, 1));
        dualModePanel.addView(both);
        remoteCard.addView(dualModePanel);
        throttleJoystick.setListener((value, released) -> {
            throttle = value;
            sendDriveState(steering, throttle, released);
        });
        steeringJoystick.setListener((value, released) -> {
            steering = value;
            sendDriveState(steering, throttle, released);
        });

        buttonModePanel = vertical();
        notice(buttonModePanel, "左列按住前进/后退，右列按住左转/右转。按住越久数值逐步增加，直到 100；松开立即归零对应方向。可以双指同时按两列。");
        gap(buttonModePanel, 10);
        LinearLayout buttons = horizontal();
        LinearLayout speedButtons = vertical();
        LinearLayout turnButtons = vertical();
        speedButtons.addView(text("左 · 前后", 13, TEAL, true));
        turnButtons.addView(text("右 · 左右", 13, TEAL, true));
        Button forward = btn("▲ 前进", false);
        Button backward = btn("▼ 后退", false);
        Button left = btn("◀ 左转", false);
        Button right = btn("右转 ▶", false);
        speedButtons.addView(forward, lparams(-1, 61));
        gap(speedButtons, 10);
        speedButtons.addView(backward, lparams(-1, 61));
        turnButtons.addView(left, lparams(-1, 61));
        gap(turnButtons, 10);
        turnButtons.addView(right, lparams(-1, 61));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.setMargins(dp(3), 0, dp(3), 0);
        buttons.addView(speedButtons, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1);
        half2.setMargins(dp(3), 0, dp(3), 0);
        buttons.addView(turnButtons, half2);
        buttonModePanel.addView(buttons);
        bindDirectionButton(forward, 0);
        bindDirectionButton(backward, 1);
        bindDirectionButton(left, 2);
        bindDirectionButton(right, 3);
        remoteCard.addView(buttonModePanel);

        int savedMode = preferences.getInt("control_mode", 1);
        savedMode = Math.max(0, Math.min(2, savedMode));
        showControlMode(savedMode);
        controlMode.setSelection(savedMode);
        controlMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private int current = controlMode.getSelectedItemPosition();
            @Override public void onItemSelected(AdapterView<?> parent, View view, int mode, long id) {
                if (mode != current) {
                    stopAllMotion();
                    current = mode;
                    preferences.edit().putInt("control_mode", mode).apply();
                }
                showControlMode(mode);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        gap(remoteCard, 10);
        joyReadout = text("前后速度 Y: 0     转向 X: 0", 13, TEAL, true);
        joyReadout.setGravity(Gravity.CENTER);
        remoteCard.addView(joyReadout);
        gap(remoteCard, 10);
        Button emergency = btn("■ 急停 · 输出 X=0,Y=0", true);
        remoteCard.addView(emergency, lparams(-1, 49));
        emergency.setOnClickListener(v -> stopAllMotion());
        gap(remoteCard, 8);
        notice(remoteCard, "松手、离开遥控页面或切换模式，都会立即发送归零指令。单片机还必须启用通信超时停车保护。");

        LinearLayout macrosCard = card(body, "自定义快捷按键");
        notice(macrosCard, "点击发送，长按编辑或删除；点“+ 添加按键”最多 64 项。");
        gap(macrosCard, 8);
        macroList = vertical(); macrosCard.addView(macroList);
        Button addMacro = btn("＋ 添加按键", true);
        macrosCard.addView(addMacro, lparams(-1, 46));
        addMacro.setOnClickListener(v -> editMacro(null));
        renderMacros();

        LinearLayout slidersCard = card(body, "参数滑杆");
        notice(slidersCard, "每个滑杆可设置名称、命令前缀和范围；修改立即保存。可添加或删除。");
        gap(slidersCard, 8);
        sliderList = vertical(); slidersCard.addView(sliderList);
        Button addSliderButton = btn("＋ 添加滑杆", true);
        slidersCard.addView(addSliderButton, lparams(-1, 46));
        addSliderButton.setOnClickListener(v -> editSlider(null));
        renderSliders();
        return page;
    }

    private void showControlMode(int mode) {
        if (singleModePanel == null || dualModePanel == null || buttonModePanel == null) return;
        singleModePanel.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        dualModePanel.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        buttonModePanel.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
    }

    private boolean anyDirectionHeld() {
        for (boolean holding : directionHeld) if (holding) return true;
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindDirectionButton(Button button, int index) {
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                directionHeld[index] = true;
                directionPressedAt[index] = SystemClock.uptimeMillis();
                sendButtonMotion(false);
                handler.removeCallbacks(buttonHeartbeat);
                handler.postDelayed(buttonHeartbeat, 90);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
                directionHeld[index] = false;
                // Preserve an independently held button on the other axis.
                sendButtonMotion(true);
                if (!anyDirectionHeld()) handler.removeCallbacks(buttonHeartbeat);
                if (action == MotionEvent.ACTION_UP) view.performClick();
                return true;
            }
            return true;
        });
    }

    private void sendButtonMotion(boolean urgent) {
        long now = SystemClock.uptimeMillis();
        int y = DriveControlMath.buttonAxis(directionHeld[0], directionPressedAt[0],
                directionHeld[1], directionPressedAt[1], now);
        int x = DriveControlMath.buttonAxis(directionHeld[3], directionPressedAt[3],
                directionHeld[2], directionPressedAt[2], now);
        throttle = y;
        steering = x;
        sendDriveState(x, y, urgent);
    }

    /** One wire protocol for all three control modes. Released axes use urgent sends. */
    private void sendDriveState(int x, int y, boolean urgent) {
        if (joyReadout != null) joyReadout.setText("前后速度 Y: " + y + "     转向 X: " + x);
        String command = joystickCommand(x, y);
        if (urgent) sendUrgentCommand(command);
        else if (client.isConnected()) {
            String value = appendSuffix(unescape(command));
            client.sendDrive(value.getBytes(StandardCharsets.UTF_8));
            addLog("TX", value.replace("\r", "\\r").replace("\n", "\\n"));
        }
    }

    private void clearMonitoring() {
        transmittedCount = 0;
        receivedCount = 0;
        logText.setLength(0);
        if (outputLog != null) outputLog.setText("等待串口数据…\n");
        if (logScroller != null) logScroller.scrollTo(0, 0);
        updateCounters();
    }

    private void loadControls() {
        macros.clear();
        String macroJson = preferences.getString(MACRO_STORE, null);
        if (macroJson == null) {
            for (int i = 0; i < MACRO_LABELS.length; i++)
                macros.add(new MacroEntry(preferences.getString("macro_label_" + i, MACRO_LABELS[i]),
                    preferences.getString("macro_cmd_" + i, MACRO_COMMANDS[i])));
        } else try {
            JSONArray array = new JSONArray(macroJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                macros.add(new MacroEntry(o.optString("label", "按键"), o.optString("command", "")));
            }
        } catch (Exception e) { toast("按键设置损坏，已恢复默认"); resetMacroDefaults(); }

        sliders.clear();
        String sliderJson = preferences.getString(SLIDER_STORE, null);
        if (sliderJson == null) {
            sliders.add(legacySlider("pwm", "电机 PWM", "PWM,", 0, 100));
            sliders.add(legacySlider("servo", "舵机角度", "SERVO,", 0, 180));
            sliders.add(legacySlider("aux", "辅助参数", "AUX,", 0, 100));
        } else try {
            JSONArray array = new JSONArray(sliderJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                int min = o.optInt("min", 0), max = o.optInt("max", 100);
                if (min < -10000 || max > 10000 || max <= min) continue;
                int value = Math.max(min, Math.min(max, o.optInt("value", min)));
                sliders.add(new SliderEntry(o.optString("id", UUID.randomUUID().toString()),
                    o.optString("label", "参数"), o.optString("prefix", "V,"), min, max, value));
            }
        } catch (Exception e) { toast("滑杆设置损坏，已恢复默认"); sliders.clear();
            sliders.add(new SliderEntry("pwm", "电机 PWM", "PWM,", 0, 100, 0)); }
    }
    private SliderEntry legacySlider(String key, String label, String prefix, int min, int max) {
        int a=preferences.getInt(key + "_min", min), b=preferences.getInt(key + "_max", max);
        if (a < -10000 || b > 10000 || b <= a) { a=min; b=max; }
        return new SliderEntry(key, preferences.getString(key + "_name", label),
            preferences.getString(key + "_prefix", prefix), a, b,
            Math.max(a, Math.min(b, preferences.getInt(key + "_value", a))));
    }
    private void saveMacros() {
        JSONArray array = new JSONArray();
        for (MacroEntry m : macros) {
            JSONObject o = new JSONObject();
            try { o.put("label", m.label); o.put("command", m.command); array.put(o); }
            catch (Exception ignored) { }
        }
        preferences.edit().putString(MACRO_STORE, array.toString()).apply();
    }
    private void saveSliders() {
        JSONArray array = new JSONArray();
        for (SliderEntry item : sliders) {
            JSONObject o = new JSONObject();
            try { o.put("id", item.id); o.put("label", item.label);
                o.put("prefix", item.prefix); o.put("min", item.min); o.put("max", item.max);
                o.put("value", item.value); array.put(o); }
            catch (Exception ignored) { }
        }
        preferences.edit().putString(SLIDER_STORE, array.toString()).apply();
    }
    private void resetMacroDefaults() {
        macros.clear();
        for (int i = 0; i < MACRO_LABELS.length; i++) macros.add(new MacroEntry(MACRO_LABELS[i], MACRO_COMMANDS[i]));
        saveMacros();
    }
    private void renderMacros() {
        if (macroList == null) return;
        macroList.removeAllViews();
        for (int start = 0; start < macros.size(); start += 2) {
            LinearLayout line = horizontal();
            int end = Math.min(start + 2, macros.size());
            for (int index = start; index < end; index++) {
                MacroEntry item = macros.get(index);
                Button button = btn(item.label, "S".equals(item.command));
                addWeighted(line, button);
                button.setOnClickListener(v -> sendCommand(item.command));
                button.setOnLongClickListener(v -> { editMacro(item); return true; });
            }
            if (end - start == 1) line.addView(new View(this), new LinearLayout.LayoutParams(0, dp(47), 1));
            macroList.addView(line); gap(macroList, 7);
        }
    }
    private void editMacro(MacroEntry existing) {
        if (existing == null && macros.size() >= 64) { toast("最多添加 64 个快捷按键"); return; }
        LinearLayout form = vertical(); form.setPadding(dp(18), dp(6), dp(18), dp(3));
        form.addView(text("名称", 13, MUTED, false));
        EditText title = input("例如 LED 开", false); title.setText(existing == null ? "新按键" : existing.label);
        form.addView(title, lparams(-1, 48)); gap(form, 10);
        form.addView(text("串口命令", 13, MUTED, false));
        EditText cmd = input("例如 LED_ON", false); cmd.setText(existing == null ? "" : existing.command);
        form.addView(cmd, lparams(-1, 48));
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(existing == null ? "添加按键" : "编辑按键")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (existing != null) builder.setNeutralButton("删除", (d, which) ->
            new AlertDialog.Builder(this).setMessage("确认删除该快捷按键？")
                .setPositiveButton("删除", (d2, w2) -> { macros.remove(existing); saveMacros(); renderMacros(); })
                .setNegativeButton("取消", null).show());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = title.getText().toString().trim(), command = cmd.getText().toString();
            if (label.isEmpty() || label.length() > 30 || command.trim().isEmpty() || command.length() > 200) {
                toast("名称 1～30 字，命令 1～200 字"); return;
            }
            if (existing == null) macros.add(new MacroEntry(label, command));
            else { existing.label = label; existing.command = command; }
            saveMacros(); renderMacros(); dialog.dismiss();
        }));
        dialog.show();
    }
    private void renderSliders() {
        if (sliderList == null) return;
        sliderList.removeAllViews();
        for (SliderEntry item : sliders) {
            LinearLayout heading = horizontal();
            TextView label = text(item.label, 14, TEXT, true);
            heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView number = text(String.valueOf(item.value), 13, TEAL, true);
            heading.addView(number, lparams(68, -2));
            Button edit = btn("编辑", false); edit.setTextSize(12);
            heading.addView(edit, lparams(60, 42));
            sliderList.addView(heading);
            SeekBar seek = new SeekBar(this);
            seek.setMax(item.max - item.min);
            seek.setProgress(item.value - item.min);
            sliderList.addView(seek, lparams(-1, 41));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                long last = 0;
                @Override public void onProgressChanged(SeekBar v, int progress, boolean fromUser) {
                    item.value = item.min + progress;
                    number.setText(String.valueOf(item.value));
                    if (!fromUser) return;
                    long now = SystemClock.uptimeMillis();
                    if (now - last > 90) { last = now; sendCommand(item.prefix + item.value); }
                }
                @Override public void onStartTrackingTouch(SeekBar v) { }
                @Override public void onStopTrackingTouch(SeekBar v) {
                    sendCommand(item.prefix + item.value); saveSliders();
                }
            });
            edit.setOnClickListener(v -> editSlider(item));
            gap(sliderList, 12);
        }
    }
    private void editSlider(SliderEntry existing) {
        if (existing == null && sliders.size() >= 64) { toast("最多添加 64 个滑杆"); return; }
        LinearLayout form = vertical(); form.setPadding(dp(18), dp(6), dp(18), dp(3));
        EditText name = input("滑杆名称", false);
        name.setText(existing == null ? "新参数" : existing.label);
        form.addView(text("名称", 13, MUTED, false)); form.addView(name, lparams(-1, 46)); gap(form, 8);
        EditText prefix = input("例如 PWM,", false);
        prefix.setText(existing == null ? "V," : existing.prefix);
        form.addView(text("命令前缀", 13, MUTED, false)); form.addView(prefix, lparams(-1, 46)); gap(form, 8);
        LinearLayout limits = horizontal();
        EditText minInput = input("最小值", false), maxInput = input("最大值", false);
        minInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        maxInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        minInput.setText(String.valueOf(existing == null ? 0 : existing.min));
        maxInput.setText(String.valueOf(existing == null ? 100 : existing.max));
        addWeighted(limits, minInput); addWeighted(limits, maxInput);
        form.addView(text("最小值 / 最大值", 13, MUTED, false)); form.addView(limits);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(existing == null ? "添加滑杆" : "编辑滑杆")
            .setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (existing != null) builder.setNeutralButton("删除", (d, w) ->
            new AlertDialog.Builder(this).setMessage("确认删除该滑杆？")
                .setPositiveButton("删除", (d2, w2) -> { sliders.remove(existing); saveSliders(); renderSliders(); })
                .setNegativeButton("取消", null).show());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int min, max;
            try { min = Integer.parseInt(minInput.getText().toString());
                max = Integer.parseInt(maxInput.getText().toString()); }
            catch (NumberFormatException ex) { toast("请输入有效整数"); return; }
            String label = name.getText().toString().trim(), pre = prefix.getText().toString();
            if (min < -10000 || max > 10000 || max <= min || label.isEmpty() || label.length() > 30 || pre.length() > 120) {
                toast("检查名称、范围（-10000～10000）和前缀"); return;
            }
            if (existing == null) sliders.add(new SliderEntry(UUID.randomUUID().toString(), label, pre, min, max, min));
            else { existing.label=label; existing.prefix=pre; existing.min=min; existing.max=max;
                existing.value = Math.max(min, Math.min(max, existing.value)); }
            saveSliders(); renderSliders(); dialog.dismiss();
        }));
        dialog.show();
    }

    private View createPlotPage() {
        ScrollView page = new ScrollView(this);
        LinearLayout body = pageBody(page);
        LinearLayout panel = card(body, "实时波形 · 最多 4 通道");
        chartCounter = text("已接收 0 帧数值", 12, MUTED, false);
        panel.addView(chartCounter);
        gap(panel, 12);
        chart = new OscilloscopeView(this);
        panel.addView(chart, lparams(-1, 280));
        gap(panel, 11);
        LinearLayout buttons = horizontal();
        Button pause = btn("暂停", false), clear = btn("清空", false), demo = btn("演示曲线", true);
        addWeighted(buttons, pause); addWeighted(buttons, clear); addWeighted(buttons, demo);
        panel.addView(buttons);
        pause.setOnClickListener(v -> { chartPaused = !chartPaused; pause.setText(chartPaused ? "继续" : "暂停"); });
        clear.setOnClickListener(v -> { chart.clear(); updateChartCount(); });
        demo.setOnClickListener(v -> {
            demoMode = !demoMode;
            demo.setText(demoMode ? "停止演示" : "演示曲线");
            if (demoMode) handler.post(demoRunner);
        });
        LinearLayout help = card(body, "单片机数据格式");
        notice(help, "每行代表一个采样帧，必须以换行符结束。支持纯数字 CSV 或 key=value 格式。绘图与终端接收同时进行。");
        gap(help, 10);
        TextView example = text("23.5\\n\n23.5,56.1\\n\nax=0.5,ay=-1.2,az=9.8\\n", 13, NAVY, false);
        example.setTypeface(Typeface.MONOSPACE);
        example.setPadding(dp(13), dp(11), dp(13), dp(11));
        example.setBackground(shape(0xfff2f8fb, 0, 11));
        help.addView(example);
        gap(help, 9);
        notice(help, "绘图会自动缩放 Y 轴，只保留最近 180 帧；建议 STM32 每 20～100 ms 发送一帧。");
        return page;
    }

    private View createSettingsPage() {
        ScrollView page = new ScrollView(this);
        LinearLayout body = pageBody(page);
        LinearLayout connect = card(body, "连接设置");
        notice(connect, "默认 SPP 服务 UUID 适用于常见 HC-05 / HC-06 类模块。只有模块使用其他 UUID 时才需要修改。");
        gap(connect, 12);
        uuidEdit = input("SPP UUID", false);
        uuidEdit.setText(preferences.getString("spp_uuid", DEFAULT_UUID));
        connect.addView(uuidEdit, lparams(-1, 49));
        gap(connect, 10);
        Button save = btn("保存 UUID", true);
        connect.addView(save, lparams(-1, 47));
        save.setOnClickListener(v -> {
            String uuid = uuidEdit.getText().toString().trim();
            try { UUID.fromString(uuid); }
            catch (IllegalArgumentException ex) { toast("UUID 格式不正确"); return; }
            preferences.edit().putString("spp_uuid", uuid).apply();
            toast("已保存，下一次连接生效");
        });
        LinearLayout serial = card(body, "串口发送选项");
        serial.addView(text("自动发送结束符", 14, TEXT, true));
        gap(serial, 7);
        endSelection = new Spinner(this);
        String[] variants = {"无", "LF (\\n)", "CRLF (\\r\\n)", "CR (\\r)"};
        ArrayAdapter<String> endings = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, variants);
        endings.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        endSelection.setAdapter(endings);
        endSelection.setSelection(preferences.getInt("suffix", 1));
        serial.addView(endSelection, lparams(-1, 48));
        endSelection.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                preferences.edit().putInt("suffix", pos).apply();
                refreshSendSuffix();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });
        gap(serial, 7);
        notice(serial, "遥杆、方向键、宏按键、滑杆和文本发送均使用此结束符。HEX 手动发送除外。");

        LinearLayout remote = card(body, "统一小车遥控协议 · v1.3");
        notice(remote, "三种遥控模式统一发送一条命令；{x} 为转向（左负右正），{y} 为前后速度（后退负前进正），范围均为 -100～100。四按键根据按住时间平滑加速。");
        gap(remote, 9);
        remote.addView(text("三种模式共用的指令模板", 13, TEXT, true));
        gap(remote, 5);
        EditText joystickFormat = input("例如 J,{x},{y}", false);
        joystickFormat.setText(preferences.getString("joy_format", "J,{x},{y}"));
        remote.addView(joystickFormat, lparams(-1, 47));
        gap(remote, 9);
        Button saveRemote = btn("保存统一遥控协议", true);
        remote.addView(saveRemote, lparams(-1, 46));
        saveRemote.setOnClickListener(v -> {
            String template = joystickFormat.getText().toString();
            if (!template.contains("{x}") || !template.contains("{y}") || template.length() > 120) {
                toast("模板必须包含 {x}、{y}，且不超过120字符"); return;
            }
            preferences.edit().putString("joy_format", template).apply();
            toast("已保存：三种控制模式立即共用此协议");
        });
        gap(remote, 8);
        Button resetRemote = btn("恢复默认 J,{x},{y}", false);
        remote.addView(resetRemote, lparams(-1, 44));
        resetRemote.setOnClickListener(v -> {
            preferences.edit().remove("joy_format").apply();
            joystickFormat.setText("J,{x},{y}");
            toast("已恢复统一协议");
        });
        LinearLayout about = card(body, "说明与兼容性");
        notice(about, "• 支持 Android 8.0+ 蓝牙经典 SPP。\n• 支持已配对设备列表及附近设备发现。\n• 不支持 BLE-only 的蓝牙 GATT 模块。\n• 波特率由蓝牙模块 UART 侧与 MCU 匹配，手机端无需设置。\n• 数据只保存在本机内存或您主动导出的 TXT 文件，不上传服务器。\n• 控制电机时务必实现通信超时急停和硬件电源开关。");
        gap(about, 13);
        Button reset = btn("恢复默认快捷按键", false);
        about.addView(reset, lparams(-1, 46));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("恢复默认命令？")
            .setMessage("将重置快捷按键为默认 8 项，滑杆设置不变。")
            .setPositiveButton("恢复", (d, w) -> { resetMacroDefaults(); renderMacros(); })
            .setNegativeButton("取消", null).show());
        return page;
    }

    private void manualSend() {
        String content = sendInput.getText().toString();
        if (content.isEmpty()) return;
        if (!client.isConnected()) { toast("请先连接蓝牙设备"); return; }
        if (!sendAsHex.isChecked()) { sendRaw(appendSuffix(unescape(content))); return; }
        String compact = content.replaceAll("(?i)0x", "").replaceAll("\\s+", "");
        if ((compact.length() & 1) == 1 || compact.isEmpty() || !compact.matches("[0-9a-fA-F]+")) {
            toast("HEX 格式错误，例如 AA 55 0F"); return;
        }
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        transmit(bytes, hex(bytes));
    }

    private String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
    }
    private String suffix() {
        return SerialProtocol.suffix(preferences.getInt("suffix", 1));
    }
    private String appendSuffix(String text) {
        return SerialProtocol.appendSuffix(text, preferences.getInt("suffix", 1));
    }
    private String joystickCommand(int x, int y) {
        return SerialProtocol.twoAxis(preferences.getString("joy_format", "J,{x},{y}"), x, y);
    }
    private void sendCommand(String command) {
        if (!client.isConnected()) return; // Avoid flooding error dialogs during a joystick drag.
        sendRaw(appendSuffix(unescape(command)));
    }
    private void sendUrgentCommand(String command) {
        if (!client.isConnected()) return;
        String value = appendSuffix(unescape(command));
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        client.sendUrgent(bytes);
        addLog("TX!", value.replace("\r", "\\r").replace("\n", "\\n"));
    }
    private void sendRaw(String content) {
        transmit(content.getBytes(StandardCharsets.UTF_8), content.replace("\r", "\\r").replace("\n", "\\n"));
    }
    private void transmit(byte[] payload, String display) {
        if (!client.isConnected()) { toast("蓝牙未连接"); return; }
        client.send(payload);
        addLog("TX", display);
    }
    private void onReceiveBytes(byte[] bytes) {
        receivedCount += bytes.length;
        updateCounters();
        String plain = decoder.decode(bytes);
        addLog("RX", showRxHex.isChecked() ? hex(bytes) : plain.replace("\r", "\\r").replace("\n", "\\n"));
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '\n') {
                parsePlotLine(lineBuffer.toString().trim());
                lineBuffer.setLength(0);
            } else if (c != '\r') {
                lineBuffer.append(c);
                if (lineBuffer.length() > 4096) lineBuffer.delete(0, lineBuffer.length() - 2048);
            }
        }
    }
    private void parsePlotLine(String line) {
        if (chartPaused || line.isEmpty()) return;
        String[] parts = line.split("[,;\\s]+");
        float[] data = new float[4];
        int count = 0;
        for (String part : parts) {
            int equals = Math.max(part.lastIndexOf('='), part.lastIndexOf(':'));
            if (equals >= 0) part = part.substring(equals + 1);
            try {
                float number = Float.parseFloat(part);
                if (Float.isFinite(number)) data[count++] = number;
                if (count == 4) break;
            } catch (NumberFormatException ignored) { }
        }
        if (count == 0) return;
        float[] reduced = new float[count];
        System.arraycopy(data, 0, reduced, 0, count);
        chart.addSample(reduced);
        updateChartCount();
    }
    private void updateChartCount() { chartCounter.setText("已接收 " + chart.size() + " 帧数值"); }
    private void updateCounters() {
        counterLabel.setText("↑ TX " + transmittedCount + " B      ↓ RX " + receivedCount + " B");
    }
    private static String hex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 3);
        for (byte b : raw) out.append(String.format(Locale.US, "%02X ", b & 0xff));
        return out.toString().trim();
    }
    private void addLog(String category, String content) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        logText.append('[').append(stamp).append("] ").append(category).append("  ").append(content).append('\n');
        if (logText.length() > MAX_LOG) logText.delete(0, logText.length() - 20000);
        if (outputLog != null) {
            outputLog.setText(logText);
            if (autoScroll != null && autoScroll.isChecked())
                logScroller.post(() -> logScroller.fullScroll(View.FOCUS_DOWN));
        }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private boolean hasPermissions() {
        for (String permission : neededPermissions())
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private void chooseDevice() {
        if (bluetoothAdapter == null) { toast("本机不支持蓝牙"); return; }
        if (!hasPermissions()) { requestPermissions(neededPermissions(), REQ_PERMISSION); return; }
        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE); return;
        }
        showDeviceDialog();
    }

    @SuppressLint("MissingPermission")
    private void showDeviceDialog() {
        devices.clear();
        try {
            for (BluetoothDevice b : bluetoothAdapter.getBondedDevices()) devices.put(b.getAddress(), b);
        } catch (SecurityException exception) { toast("请授权附近设备权限"); return; }
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        deviceDialog = new AlertDialog.Builder(this).setTitle("选择经典蓝牙设备")
            .setAdapter(deviceAdapter, (dialog, which) -> {
                if (which >= keys.size()) return;
                BluetoothDevice device = devices.get(keys.get(which));
                if (device == null) return;
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    try {
                        device.createBond();
                        toast("请完成配对，然后重新选择设备连接");
                    } catch (SecurityException ex) { toast("缺少蓝牙配对权限"); }
                    return;
                }
                decoder.reset(); lineBuffer.setLength(0);
                client.connect(bluetoothAdapter, device, preferences.getString("spp_uuid", DEFAULT_UUID));
            })
            .setPositiveButton("扫描附近", null)
            .setNegativeButton("关闭", null)
            .create();
        deviceDialog.setOnDismissListener(d -> deviceDialog = null);
        deviceDialog.show();
        deviceDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> startDiscovery());
        updateDeviceList();
    }
    @SuppressLint("MissingPermission")
    private void updateDeviceList() {
        if (deviceAdapter == null || deviceDialog == null || !deviceDialog.isShowing() || !hasPermissions()) return;
        deviceDialog.setTitle(scanning ? "正在搜索附近设备…" : "选择经典蓝牙设备");
        deviceAdapter.clear(); keys.clear();
        for (Map.Entry<String, BluetoothDevice> item : devices.entrySet()) {
            BluetoothDevice device = item.getValue();
            try {
                String name = device.getName();
                String bonded = device.getBondState() == BluetoothDevice.BOND_BONDED ? "已配对" : "未配对";
                keys.add(item.getKey());
                deviceAdapter.add((name == null ? "未知设备" : name) + "  ·  " + bonded + "\n" + item.getKey());
            } catch (SecurityException ignored) { }
        }
        deviceAdapter.notifyDataSetChanged();
    }
    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        if (!hasPermissions()) { requestPermissions(neededPermissions(), REQ_PERMISSION); return; }
        try {
            bluetoothAdapter.cancelDiscovery();
            scanning = bluetoothAdapter.startDiscovery();
            updateDeviceList();
            if (!scanning) toast("扫描未启动，请检查手机蓝牙是否已开启");
        } catch (SecurityException exception) { toast("扫描权限不足"); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSION) return;
        if (hasPermissions()) chooseDevice();
        else new AlertDialog.Builder(this).setTitle("需要蓝牙权限")
            .setMessage("Android 12 及以上需允许“附近的设备”，才能连接 SPP 蓝牙模块。旧版 Android 扫描附近设备需要位置权限。")
            .setPositiveButton("知道了", null).show();
    }
    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "bluetooth-log.txt");
        startActivityForResult(intent, REQ_EXPORT);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE && resultCode == RESULT_OK) chooseDevice();
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new java.io.IOException("无法写入文件");
                out.write(logText.toString().getBytes(StandardCharsets.UTF_8));
                toast("日志导出成功");
            } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
        }
    }
    private void stopAllMotion() {
        handler.removeCallbacks(buttonHeartbeat);
        for (int i = 0; i < directionHeld.length; i++) directionHeld[i] = false;
        if (singleJoystick != null) singleJoystick.releaseControl();
        if (throttleJoystick != null) throttleJoystick.releaseControl();
        if (steeringJoystick != null) steeringJoystick.releaseControl();
        throttle = 0;
        steering = 0;
        sendDriveState(0, 0, true);
    }
    @Override protected void onPause() {
        stopAllMotion();
        super.onPause();
    }
    @Override protected void onDestroy() {
        stopAllMotion();
        demoMode = false;
        handler.removeCallbacks(demoRunner);
        if (bluetoothAdapter != null && hasPermissions()) {
            try { bluetoothAdapter.cancelDiscovery(); } catch (SecurityException ignored) { }
        }
        client.shutdown();
        if (receiverRegistered) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
