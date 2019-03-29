/*
 * Copyright (C) 2014 Slimroms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.slim.device;

import android.app.ActivityManagerNative;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.IAudioService;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;

import com.android.internal.os.DeviceKeyHandler;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.candy.FileUtils;
import com.android.internal.util.candy.ActionConstants;
import com.android.internal.util.candy.Action;

public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();
    private static final boolean DEBUG = true;
    protected static final int GESTURE_REQUEST = 1;
    private static final int GESTURE_WAKELOCK_DURATION = 2000;
    private static final String KEY_CONTROL_PATH = "/proc/touchpanel/key_disable";
    private static final String FPC_CONTROL_PATH = "/sys/module/fpc1020_tee/parameters/ignor_home_for_ESD";

    // Supported scancodes
    //#define KEY_GESTURE_CIRCLE      250 // draw circle to lunch camera
    //#define KEY_GESTURE_TWO_SWIPE	251 // swipe two finger vertically to play/pause
    //#define KEY_GESTURE_V           252 // draw v to toggle flashlight
    //#define KEY_GESTURE_LEFT_V      253 // draw left arrow for previous track
    //#define KEY_GESTURE_RIGHT_V     254 // draw right arrow for next track
    //#define MODE_TOTAL_SILENCE 600
    //#define MODE_ALARMS_ONLY 601
    //#define MODE_PRIORITY_ONLY 602
    //#define MODE_NONE 603

    private static final int GESTURE_CIRCLE_SCANCODE = 250;
    private static final int GESTURE_V_SCANCODE = 252;
    private static final int GESTURE_II_SCANCODE = 251;
    private static final int GESTURE_LEFT_V_SCANCODE = 253;
    private static final int GESTURE_RIGHT_V_SCANCODE = 254;
    private static final int GESTURE_V_UP_SCANCODE = 255;

    private static final int KEY_DOUBLE_TAP = 143;

    private static final int KEY_HOME = 102;
    private static final int KEY_BACK = 158;
    private static final int KEY_RECENTS = 580;

    private static final int KEY_SLIDER_TOP = 603;
    private static final int KEY_SLIDER_CENTER = 602;
    private static final int KEY_SLIDER_BOTTOM = 601;
    private static final int MODE_TOTAL_SILENCE = 600;

    private static final int[] sSupportedGestures = new int[]{
        GESTURE_CIRCLE_SCANCODE,
        GESTURE_V_SCANCODE,
        KEY_DOUBLE_TAP,
        GESTURE_II_SCANCODE,
        GESTURE_LEFT_V_SCANCODE,
        GESTURE_RIGHT_V_SCANCODE,
        GESTURE_V_UP_SCANCODE,
        KEY_SLIDER_TOP,
        KEY_SLIDER_CENTER,
        KEY_SLIDER_BOTTOM
    };

    private static final int[] sHandledGestures = new int[]{
        GESTURE_V_SCANCODE,
        GESTURE_II_SCANCODE,
        GESTURE_LEFT_V_SCANCODE,
        GESTURE_RIGHT_V_SCANCODE,
        GESTURE_V_UP_SCANCODE,
        KEY_SLIDER_TOP,
        KEY_SLIDER_CENTER,
        KEY_SLIDER_BOTTOM
    };

    private static final int[] sProxiCheckedGestures = new int[]{
        GESTURE_CIRCLE_SCANCODE,
        GESTURE_V_SCANCODE,
        KEY_DOUBLE_TAP,
        GESTURE_II_SCANCODE,
        GESTURE_LEFT_V_SCANCODE,
        GESTURE_RIGHT_V_SCANCODE,
        GESTURE_V_UP_SCANCODE
    };

    protected final Context mContext;
    private final PowerManager mPowerManager;
    private EventHandler mEventHandler;
    private WakeLock mGestureWakeLock;
    private Handler mHandler = new Handler();
    private SettingsObserver mSettingsObserver;
    private static boolean mButtonDisabled;
    private final NotificationManager mNoMan;
    private final AudioManager mAudioManager;
    private CameraManager mCameraManager;
    private String mRearCameraId;
    private boolean mTorchEnabled;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private boolean mProxyIsNear;
    private boolean mUseProxiCheck;
    WakeLock mProximityWakeLock;

    private SensorEventListener mProximitySensor = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mProxyIsNear = event.values[0] < mSensor.getMaximumRange();
            if (DEBUG) Log.d(TAG, "mProxyIsNear = " + mProxyIsNear);
            if(FileUtils.fileWritable(FPC_CONTROL_PATH)) {
                FileUtils.writeValue(FPC_CONTROL_PATH, mProxyIsNear ? "1" : "0");
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.HARDWARE_KEYS_DISABLE),
                    false, this);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DEVICE_PROXI_CHECK_ENABLED),
                    false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            setButtonDisable(mContext);
            mUseProxiCheck = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.DEVICE_PROXI_CHECK_ENABLED, 1,
                    UserHandle.USER_CURRENT) == 1;
        }
    }

    private class MyTorchCallback extends CameraManager.TorchCallback {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (!cameraId.equals(mRearCameraId))
                return;
            mTorchEnabled = enabled;
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (!cameraId.equals(mRearCameraId))
                return;
            mTorchEnabled = false;
        }
    }

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
         @Override
         public void onReceive(Context context, Intent intent) {
             if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                 onDisplayOn();
             } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                 onDisplayOff();
             }
         }
    };

    public KeyHandler(Context context) {
        mContext = context;
        mEventHandler = new EventHandler();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "GestureWakeLock");
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        mNoMan = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mCameraManager.registerTorchCallback(new MyTorchCallback(), mEventHandler);
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
    }

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            KeyEvent event = (KeyEvent) msg.obj;
            handleKey(event.getScanCode());
        }
    }

    private void handleKey(int scanCode) {
        switch(scanCode) {
        case GESTURE_V_UP_SCANCODE:
            if (DEBUG) Log.i(TAG, "GESTURE_V_UP_SCANCODE");
            String rearCameraId = getRearCameraId();
            if (rearCameraId != null) {
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                try {
                    mCameraManager.setTorchMode(rearCameraId, !mTorchEnabled);
                    mTorchEnabled = !mTorchEnabled;
                } catch (Exception e) {
                    // Ignore
                }
            }
            break;
        case GESTURE_II_SCANCODE:
            if (DEBUG) Log.i(TAG, "GESTURE_II_SCANCODE");
            mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
            dispatchMediaKeyWithWakeLockToAudioService(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            break;
        case GESTURE_LEFT_V_SCANCODE:
            if (isMusicActive()) {
                if (DEBUG) Log.i(TAG, "GESTURE_LEFT_V_SCANCODE");
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                dispatchMediaKeyWithWakeLockToAudioService(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
            break;
        case GESTURE_RIGHT_V_SCANCODE:
            if (isMusicActive()) {
                if (DEBUG) Log.i(TAG, "GESTURE_RIGHT_V_SCANCODE");
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                dispatchMediaKeyWithWakeLockToAudioService(KeyEvent.KEYCODE_MEDIA_NEXT);
            }
            break;
        case GESTURE_V_SCANCODE:
            if (isMusicActive()) {
                if (DEBUG) Log.i(TAG, "GESTURE_V_SCANCODE");
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                dispatchMediaKeyWithWakeLockToAudioService(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
            break;
        case KEY_SLIDER_TOP:
            if (DEBUG) Log.i(TAG, "KEY_SLIDER_TOP");
            mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
            doHandleSliderAction(0);
            break;
        case KEY_SLIDER_CENTER:
            if (DEBUG) Log.i(TAG, "KEY_SLIDER_CENTER");
            mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
            doHandleSliderAction(1);
            break;
        case KEY_SLIDER_BOTTOM:
            if (DEBUG) Log.i(TAG, "KEY_SLIDER_BOTTOM");
            mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
            doHandleSliderAction(2);
            break;
        }
    }

    public KeyEvent handleKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();
        boolean isKeySupported = ArrayUtils.contains(sSupportedGestures, scanCode);
        if (!isKeySupported) {
            return event;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return null;
        }
        if (isKeySupported && !mEventHandler.hasMessages(GESTURE_REQUEST)) {
            Message msg = getMessageForKeyEvent(event);
            if (scanCode < MODE_TOTAL_SILENCE && mProximitySensor != null) {
                mEventHandler.sendMessageDelayed(msg, 200);
                processEvent(event);
            } else {
                mEventHandler.sendMessage(msg);
            }
        }
        return null;
    }

    public boolean canHandleKeyEvent(KeyEvent event) {
        return ArrayUtils.contains(sSupportedGestures, event.getScanCode());
    }

    public boolean isDisabledKeyEvent(KeyEvent event) {
        boolean isProxyCheckRequired = mUseProxiCheck &&
                ArrayUtils.contains(sProxiCheckedGestures, event.getScanCode());
        if (mProxyIsNear && isProxyCheckRequired) {
            if (DEBUG) Log.i(TAG, "isDisabledKeyEvent: blocked by proxi sensor - scanCode=" + event.getScanCode());
            return true;
        }
        return false;
    }

    private Message getMessageForKeyEvent(KeyEvent keyEvent) {
        Message msg = mEventHandler.obtainMessage(GESTURE_REQUEST);
        msg.obj = keyEvent;
        return msg;
    }

    public static void setButtonDisable(Context context) {
        mButtonDisabled = Settings.System.getIntForUser(
                context.getContentResolver(), Settings.Secure.HARDWARE_KEYS_DISABLE, 0,
                UserHandle.USER_CURRENT) == 1;
        if (DEBUG) Log.i(TAG, "setButtonDisable=" + mButtonDisabled);
        if(mButtonDisabled)
            FileUtils.writeValue(KEY_CONTROL_PATH, "1");
        else
            FileUtils.writeValue(KEY_CONTROL_PATH, "0");
    }

    public boolean isCameraLaunchEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        return event.getScanCode() == GESTURE_CIRCLE_SCANCODE;
    }

    public boolean isWakeEvent(KeyEvent event){
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        return event.getScanCode() == KEY_DOUBLE_TAP;
    }

    public Intent isActivityLaunchEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return null;
        }
        return null;
    }

    private IAudioService getAudioService() {
        IAudioService audioService = IAudioService.Stub
                .asInterface(ServiceManager.checkService(Context.AUDIO_SERVICE));
        if (audioService == null) {
            Log.w(TAG, "Unable to find IAudioService interface.");
        }
        return audioService;
    }

    boolean isMusicActive() {
        return mAudioManager.isMusicActive();
    }

    private void dispatchMediaKeyWithWakeLockToAudioService(int keycode) {
        if (ActivityManagerNative.isSystemReady()) {
            IAudioService audioService = getAudioService();
            if (audioService != null) {
                KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                        keycode, 0);
                dispatchMediaKeyEventUnderWakelock(event);
                event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
                dispatchMediaKeyEventUnderWakelock(event);
            }
        }
    }

    private void dispatchMediaKeyEventUnderWakelock(KeyEvent event) {
        if (ActivityManagerNative.isSystemReady()) {
            MediaSessionLegacyHelper.getHelper(mContext).sendMediaButtonEvent(event, true);
        }
    }

    private String getRearCameraId() {
        if (mRearCameraId == null) {
            try {
                for (final String cameraId : mCameraManager.getCameraIdList()) {
                    CameraCharacteristics c = mCameraManager.getCameraCharacteristics(cameraId);
                    Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
                    if (flashAvailable != null && flashAvailable
                            && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        mRearCameraId = cameraId;
                        break;
                    }
                }
            } catch (CameraAccessException e) {
                // Ignore
            }
        }
        return mRearCameraId;
    }

    private void onDisplayOn() {
        if (mUseProxiCheck) {
            if (DEBUG) Log.d(TAG, "Display on");
            mSensorManager.unregisterListener(mProximitySensor, mSensor);
        }
    }

    private void onDisplayOff() {
        if (mUseProxiCheck) {
            if (DEBUG) Log.d(TAG, "Display off");
            mSensorManager.registerListener(mProximitySensor, mSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private int getSliderAction(int position) {
        String value = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.BUTTON_EXTRA_KEY_MAPPING,
                    UserHandle.USER_CURRENT);
        final String defaultValue = "5,3,0";

        if (value == null) {
            value = defaultValue;
        } else if (value.indexOf(",") == -1) {
            value = defaultValue;
        }
        try {
            String[] parts = value.split(",");
            return Integer.valueOf(parts[position]);
        } catch (Exception e) {
        }
        return 0;
    }

    private void doHandleSliderAction(int position) {
        int action = getSliderAction(position);
        if ( action == 0) {
            mNoMan.setZenMode(Global.ZEN_MODE_OFF, null, TAG);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        } else if (action == 1) {
            mNoMan.setZenMode(Global.ZEN_MODE_OFF, null, TAG);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
        } else if (action == 2) {
            mNoMan.setZenMode(Global.ZEN_MODE_OFF_ONLY, null, TAG);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
        } else if (action == 3) {
            mNoMan.setZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, TAG);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        } else if (action == 4) {
            mNoMan.setZenMode(Global.ZEN_MODE_ALARMS, null, TAG);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        } else if (action == 5) {
            mNoMan.setZenMode(Global.ZEN_MODE_NO_INTERRUPTIONS, null, TAG);
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        }
    }

    private void processEvent(final KeyEvent keyEvent) {
        mProximityWakeLock.acquire();
        mSensorManager.registerListener(new SensorEventListener() {

            @Override
            public void onSensorChanged(SensorEvent event) {
                mProximityWakeLock.release();
                mSensorManager.unregisterListener(this);
                if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
                    // The sensor took to long, ignoring.
                    return;
                }
                mEventHandler.removeMessages(GESTURE_REQUEST);
                if (event.values[0] == mSensor.getMaximumRange()) {
                    Message msg = getMessageForKeyEvent(keyEvent);
                    mEventHandler.sendMessage(msg);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        }, mSensor, SensorManager.SENSOR_DELAY_FASTEST);
    }
}
