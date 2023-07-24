package org.stypox.bluetooth_terminal.activity;

import static java.lang.Math.PI;

import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.github.controlwear.virtual.joystick.android.JoystickView;
import org.stypox.bluetooth_terminal.DeviceData;
import org.stypox.bluetooth_terminal.R;
import org.stypox.bluetooth_terminal.Utils;
import org.stypox.bluetooth_terminal.bluetooth.DeviceConnector;
import org.stypox.bluetooth_terminal.bluetooth.DeviceListActivity;

public final class DeviceControlActivity extends BaseActivity {
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String LOG = "LOG";

    // Подсветка crc
    private static final String CRC_OK = "#FFFF00";
    private static final String CRC_BAD = "#FF0000";

    private static final SimpleDateFormat timeformat = new SimpleDateFormat("HH:mm:ss.SSS");

    private static String MSG_NOT_CONNECTED;
    private static String MSG_CONNECTING;
    private static String MSG_CONNECTED;

    private static DeviceConnector connector;
    private static BluetoothResponseHandler mHandler;

    private StringBuilder logHtml;
    private TextView logTextView;
    private ScrollView scrollView;

    // Настройки приложения
    private int logLimitSize;
    private boolean hexMode, checkSum, needClean, logLimit;
    private boolean show_timings, show_direction;
    private String command_ending;
    private String deviceName;

    private enum CarMode {
        DIRECTIONS_4,
        DIRECTIONS_8,
        CONTINUOUS,
    }
    private CarMode carMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.settings_activity, false);

        if (mHandler == null) mHandler = new BluetoothResponseHandler(this);
        else mHandler.setTarget(this);

        MSG_NOT_CONNECTED = getString(R.string.msg_not_connected);
        MSG_CONNECTING = getString(R.string.msg_connecting);
        MSG_CONNECTED = getString(R.string.msg_connected);

        setContentView(R.layout.activity_terminal);
        if (isConnected() && (savedInstanceState != null)) {
            setDeviceName(savedInstanceState.getString(DEVICE_NAME));
        } else getActionBar().setSubtitle(MSG_NOT_CONNECTED);

        this.logHtml = new StringBuilder();
        if (savedInstanceState != null) this.logHtml.append(savedInstanceState.getString(LOG));

        this.logTextView = (TextView) findViewById(R.id.log_textview);
        this.logTextView.setMovementMethod(new ScrollingMovementMethod());
        this.logTextView.setText(Html.fromHtml(logHtml.toString()));

        this.scrollView = (ScrollView) findViewById(R.id.scrollview);

        ((JoystickView) findViewById(R.id.joystick)).setOnMoveListener(this::onMove);

        Spinner spinner = findViewById(R.id.spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.car_modes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                carMode = position == 2 ? CarMode.CONTINUOUS : position == 1 ? CarMode.DIRECTIONS_8
                        : CarMode.DIRECTIONS_4;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                carMode = CarMode.DIRECTIONS_4;
            }
        });
    }
    // ==========================================================================

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(DEVICE_NAME, deviceName);
        if (logTextView != null) {
            outState.putString(LOG, logHtml.toString());
        }
    }
    // ============================================================================


    /**
     * Проверка готовности соединения
     */
    private boolean isConnected() {
        return (connector != null) && (connector.getState() == DeviceConnector.STATE_CONNECTED);
    }
    // ==========================================================================


    /**
     * Разорвать соединение
     */
    private void stopConnection() {
        if (connector != null) {
            connector.stop();
            connector = null;
            deviceName = null;
        }
    }
    // ==========================================================================


    /**
     * Список устройств для подключения
     */
    private void startDeviceListActivity() {
        stopConnection();
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }
    // ============================================================================


    /**
     * Обработка аппаратной кнопки "Поиск"
     *
     * @return
     */
    @Override
    public boolean onSearchRequested() {
        if (super.isAdapterReady()) startDeviceListActivity();
        return false;
    }
    // ==========================================================================


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.device_control_activity, menu);
        final MenuItem bluetooth = menu.findItem(R.id.menu_search);
        if (bluetooth != null) bluetooth.setIcon(this.isConnected() ?
                R.drawable.ic_action_device_bluetooth_connected :
                R.drawable.ic_action_device_bluetooth);
        return true;
    }
    // ============================================================================


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_search:
                if (super.isAdapterReady()) {
                    if (isConnected()) stopConnection();
                    else startDeviceListActivity();
                } else {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    if (checkBluetoothPermission(REQUEST_ENABLE_BT)) startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                }
                return true;

            case R.id.menu_clear:
                if (logTextView != null) logTextView.setText("");
                return true;

            case R.id.menu_send:
                if (logTextView != null) {
                    final String msg = logTextView.getText().toString();
                    final Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, msg);
                    startActivity(Intent.createChooser(intent, getString(R.string.menu_send)));
                }
                return true;

            case R.id.menu_settings:
                final Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }
    // ============================================================================


    @Override
    public void onStart() {
        super.onStart();

        // checksum
        final String checkSum = Utils.getPrefence(this, getString(R.string.pref_checksum_mode));
        this.checkSum = "Modulo 256".equals(checkSum);

        // Окончание строки
        this.command_ending = getCommandEnding();

        // Формат отображения лога команд
        this.show_timings = Utils.getBooleanPrefence(this, getString(R.string.pref_log_timing));
        this.show_direction = Utils.getBooleanPrefence(this, getString(R.string.pref_log_direction));
        this.needClean = Utils.getBooleanPrefence(this, getString(R.string.pref_need_clean));
        this.logLimit = Utils.getBooleanPrefence(this, getString(R.string.pref_log_limit));
        this.logLimitSize = Utils.formatNumber(Utils.getPrefence(this, getString(R.string.pref_log_limit_size)));
    }
    // ============================================================================


    /**
     * Получить из настроек признак окончания команды
     */
    private String getCommandEnding() {
        String result = Utils.getPrefence(this, getString(R.string.pref_commands_ending));
        if (result.equals("\\r\\n")) result = "\r\n";
        else if (result.equals("\\n")) result = "\n";
        else if (result.equals("\\r")) result = "\r";
        else result = "";
        return result;
    }
    // ============================================================================


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    BluetoothDevice device = btAdapter.getRemoteDevice(address);
                    if (super.isAdapterReady() && (connector == null)) setupConnector(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                super.pendingRequestEnableBt = false;
                if (resultCode != Activity.RESULT_OK) {
                    Utils.log("BT not enabled");
                }
                break;
        }
    }
    // ==========================================================================


    /**
     * Установка соединения с устройством
     */
    private void setupConnector(BluetoothDevice connectedDevice) {
        stopConnection();
        try {
            String emptyName = getString(R.string.empty_device_name);
            DeviceData data = new DeviceData(connectedDevice, emptyName);
            connector = new DeviceConnector(data, mHandler);
            connector.connect();
        } catch (IllegalArgumentException e) {
            Utils.log("setupConnector failed: " + e.getMessage());
        }
    }
    // ==========================================================================

    private double trim(double x) {
        return Math.max(-1.0, Math.min(x, 1.0));
    }

    private void onMove(int angle, int strength) {
        if (carMode == CarMode.CONTINUOUS) {
            angle += 270;
            angle %= 360;
            angle = (angle > 180 ? angle - 360 : angle);
            double fangle = angle / 360.0 * 2 * PI;
            double fstrength = strength / 100.0;

            long a = Math.round((fstrength * trim(3.0 - Math.abs(fangle) / PI * 4.0)) * 7.0);
            long b = Math.round((fstrength * trim(1.0 - Math.abs(fangle) / PI * 4.0)) * 7.0);
            if (angle < 0) {
                long tmp = a;
                a = b;
                b = tmp;
            }

            byte command = (byte) (((a & 0x0f) << 4) | (b & 0x0f));
            sendCommand(command);

        } else if (strength > 50) {
            if (carMode == CarMode.DIRECTIONS_4) {
                angle += 360 - 360 / 4 / 2;
                angle %= 360;
                if (angle < 90) {
                    sendCommand("F");
                } else if (angle < 180) {
                    sendCommand("L");
                } else if (angle < 270) {
                    sendCommand("B");
                } else {
                    sendCommand("R");
                }
            } else /* CarMode.DIRECTIONS_8 */ {
                angle += 360 - 360 / 8 / 2 * 3;
                angle %= 360;
                if (angle < 45) {
                    sendCommand("F");
                } else if (angle < 90) {
                    sendCommand("2");
                } else if (angle < 135) {
                    sendCommand("L");
                } else if (angle < 180) {
                    sendCommand("3");
                } else if (angle < 225) {
                    sendCommand("B");
                } else if (angle < 270) {
                    sendCommand("4");
                } else if (angle < 315) {
                    sendCommand("R");
                } else {
                    sendCommand("1");
                }
            }
        } else if (strength < 5) {
            sendCommand("S");
        }
    }

    public void sendCommand(byte commandByte) {
        sendCommand(new byte[] {commandByte}, Utils.byteToHexDesc(commandByte));
    }

    public void sendCommand(String commandString) {
        sendCommand(commandString.getBytes(), commandString);
    }

    public void sendCommand(byte[] command, String log) {
        if (isConnected()) {
            connector.write(command);
            appendLog(log, false, true, needClean);
        } else {
            appendLog("(?) " + log, false, true, needClean);
        }
    }
    // ==========================================================================


    /**
     * Добавление ответа в лог
     *
     * @param message  - текст для отображения
     * @param outgoing - направление передачи
     * @param clean - удалять команду из поля ввода после отправки
     */
    void appendLog(String message, boolean hexMode, boolean outgoing, boolean clean) {

        boolean autoScroll =
                (logTextView.getBottom() - (scrollView.getHeight() + scrollView.getScrollY()))
                        <= logTextView.getLineHeight() * 4;

        // если установлено ограничение на логи, проверить и почистить
        if (this.logLimit && this.logLimitSize > 0
                && logTextView.getLineCount() > this.logLimitSize) {
            logTextView.setText("");
        }

        StringBuilder msg = new StringBuilder();
        if (show_timings) msg.append("[").append(timeformat.format(new Date())).append("]");
        if (show_direction) {
            final String arrow = (outgoing ? " << " : " >> ");
            msg.append(arrow);
        } else msg.append(" ");

        // Убрать символы переноса строки \r\n
        message = message.replace("\r", "").replace("\n", "");

        // Проверка контрольной суммы ответа
        String crc = "";
        boolean crcOk = false;
        if (checkSum) {
            int crcPos = message.length() - 2;
            crc = message.substring(crcPos);
            message = message.substring(0, crcPos);
            crcOk = outgoing || crc.equals(Utils.calcModulo256(message).toUpperCase());
            if (hexMode) crc = Utils.printHex(crc.toUpperCase());
        }

        // Лог в html
        msg.append("<b>")
                .append(hexMode ? Utils.printHex(message) : message)
                .append(checkSum ? Utils.mark(crc, crcOk ? CRC_OK : CRC_BAD) : "")
                .append("</b>")
                .append("<br>");

        logHtml.append(msg);
        logTextView.append(Html.fromHtml(msg.toString()));


        if (autoScroll) {
            scrollView.post(() -> scrollView.scrollTo(0, logTextView.getHeight()));
        }
    }
    // =========================================================================


    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        getActionBar().setSubtitle(deviceName);
    }

    // ==========================================================================

    /**
     * Обработчик приёма данных от bluetooth-потока
     */
    private static class BluetoothResponseHandler extends Handler {
        private WeakReference<DeviceControlActivity> mActivity;

        public BluetoothResponseHandler(DeviceControlActivity activity) {
            mActivity = new WeakReference<DeviceControlActivity>(activity);
        }

        public void setTarget(DeviceControlActivity target) {
            mActivity.clear();
            mActivity = new WeakReference<DeviceControlActivity>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            DeviceControlActivity activity = mActivity.get();
            if (activity != null) {
                switch (msg.what) {
                    case MESSAGE_STATE_CHANGE:

                        Utils.log("MESSAGE_STATE_CHANGE: " + msg.arg1);
                        final ActionBar bar = activity.getActionBar();
                        switch (msg.arg1) {
                            case DeviceConnector.STATE_CONNECTED:
                                bar.setSubtitle(MSG_CONNECTED);
                                break;
                            case DeviceConnector.STATE_CONNECTING:
                                bar.setSubtitle(MSG_CONNECTING);
                                break;
                            case DeviceConnector.STATE_NONE:
                                bar.setSubtitle(MSG_NOT_CONNECTED);
                                break;
                        }
                        activity.invalidateOptionsMenu();
                        break;

                    case MESSAGE_READ:
                        final String readMessage = (String) msg.obj;
                        if (readMessage != null) {
                            activity.appendLog(readMessage, false, false, false);
                        }
                        break;

                    case MESSAGE_DEVICE_NAME:
                        activity.setDeviceName((String) msg.obj);
                        break;

                    case MESSAGE_WRITE:
                        // stub
                        break;

                    case MESSAGE_TOAST:
                        // stub
                        break;
                }
            }
        }
    }
    // ==========================================================================
}