package org.pixelexperience.faceunlock.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import org.pixelexperience.faceunlock.AppConstants;
import org.pixelexperience.faceunlock.camera.CameraFaceAuthController;
import org.pixelexperience.faceunlock.camera.CameraFaceEnrollController;
import org.pixelexperience.faceunlock.util.NotificationUtils;
import org.pixelexperience.faceunlock.util.SharedUtil;
import org.pixelexperience.faceunlock.util.Util;
import com.motorola.faceunlock.vendor.ArcImpl;
import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.internal.util.custom.faceunlock.IFaceServiceReceiver;

import java.util.Random;

@SuppressWarnings("SynchronizeOnNonFinalField")
public class FaceAuthService extends Service {
    private static final String ALARM_FAIL_TIMEOUT_LOCKOUT = "org.pixelexperience.faceunlock.ACTION_LOCKOUT_RESET";
    private static final String ALARM_TIMEOUT_FREEZED = "org.pixelexperience.faceunlock.freezedtimeout";
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 14400000;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30000;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 3;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 3;
    private static final int MSG_CHALLENGE_TIMEOUT = 100;
    private static final String TAG = FaceAuthService.class.getName();
    private AlarmManager mAlarmManager;
    private Integer mAuthErrorCount = 0;
    private Integer mAuthErrorThrottleCount = 0;
    private CameraFaceAuthController mCameraAuthController;
    private CameraFaceEnrollController mCameraEnrollController;
    private long mChallenge = 0;
    private int mChallengeCount = 0;
    private byte[] mEnrollToken;
    private ArcImpl mFaceAuth;
    private PendingIntent mIdleTimeoutIntent;
    private Boolean mLockout = false;
    private final BroadcastReceiver mTimeoutBroadcastReciever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(FaceAuthService.ALARM_TIMEOUT_FREEZED)) {
                Log.d(FaceAuthService.TAG, "ALARM_TIMEOUT_FREEZED");
                synchronized (mLockout) {
                    mLockout = true;
                }
            } else if (action.equals(FaceAuthService.ALARM_FAIL_TIMEOUT_LOCKOUT)) {
                Log.d(FaceAuthService.TAG, "ALARM_FAIL_TIMEOUT_LOCKOUT");
                synchronized (mLockout) {
                    mLockout = false;
                }
                synchronized (mAuthErrorCount) {
                    mAuthErrorCount = 0;
                }
            }
        }
    };
    private PendingIntent mLockoutTimeoutIntent;
    private IFaceServiceReceiver mFaceReceiver;
    private boolean mOnIdleTimer = false;
    private boolean mOnLockoutTimer = false;
    private FaceAuthServiceWrapper mService;
    private SharedUtil mShareUtil;
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "OnReceive intent = " + intent);
            }
            if (mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID) > -1) {
                if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                    if (!mOnIdleTimer) {
                        startIdleTimer();
                    }
                } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                    cancelIdleTimer();
                    resetLockoutCount();
                }
            }
        }
    };
    private int mUserId;
    private final CameraFaceAuthController.ServiceCallback mCameraAuthControllerCallback = new CameraFaceAuthController.ServiceCallback() {

        @Override
        public int handlePreviewData(byte[] bArr, int i, int i2) {
            int[] iArr = new int[20];
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "handleData start");
            }
            int compare = mFaceAuth.compare(bArr, i, i2, 0, true, true, iArr);
            if (Util.DEBUG) {
                Log.d(TAG, "handleData result = " + compare + " run: fake = " + iArr[0] + ", low = " + iArr[1] + ", compare score:" + iArr[2] + " live score:" + (((double) iArr[3]) / 100.0d));
            }
            try {
                synchronized (this) {
                    if (mCameraAuthController == null) {
                        return -1;
                    }
                    if (compare == 0) {
                        mFaceReceiver.onAuthenticated(mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID), mUserId, mShareUtil.getByteArrayValueByKey(AppConstants.SHARED_KEY_ENROLL_TOKEN));
                        resetLockoutCount();
                        stopAuthrate();
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return compare;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void setDetectArea(Camera.Size size) {
            mFaceAuth.setDetectArea(0, 0, size.height, size.width);
        }

        @Override
        public void onTimeout(boolean z) {
            try {
                mFaceReceiver.onAuthenticated(0, -1, mShareUtil.getByteArrayValueByKey(AppConstants.SHARED_KEY_ENROLL_TOKEN));
                if (z) {
                    increaseAndCheckLockout();
                }
                stopAuthrate();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            stopAuthrate();
        }

        @Override
        public void onCameraError() {
            try {
                mFaceReceiver.onError(5, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            stopAuthrate();
        }
    };
    private final CameraFaceEnrollController.CameraCallback mCameraEnrollServiceCallback = new CameraFaceEnrollController.CameraCallback() {
        static final int FEATURE_SIZE = 10000;
        final byte[] mImage = new byte[40000];
        final byte[] mSavedFeature = new byte[FEATURE_SIZE];

        @Override
        public void handleSaveFeatureResult(int i) {
        }

        @Override
        public void onFaceDetected() {
        }

        @Override
        public int handleSaveFeature(byte[] bArr, int i, int i2, int i3) {
            int[] iArr = new int[1];
            int saveFeature = mFaceAuth.saveFeature(bArr, i, i2, i3, true, mSavedFeature, mImage, iArr);
            synchronized (this) {
                if (mCameraEnrollController == null) {
                    return -1;
                }
                try {
                    int i4 = iArr[0] + 1;
                    if (saveFeature == 0) {
                        int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
                        if (intValue > 0) {
                            mFaceAuth.deleteFeature(intValue);
                        }
                        if (i4 > 0) {
                            mShareUtil.saveIntValue(AppConstants.SHARED_KEY_FACE_ID, i4);
                            mShareUtil.saveByteArrayValue(AppConstants.SHARED_KEY_ENROLL_TOKEN, mEnrollToken);
                            Util.setFaceUnlockAvailable(getApplicationContext());
                            NotificationUtils.checkAndShowNotification(getApplicationContext());
                        }
                        stopEnroll();
                        mFaceReceiver.onEnrollResult(i4, mUserId, 0);
                    } else if (saveFeature == 19) {
                        mFaceReceiver.onEnrollResult(i4, mUserId, 1);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return saveFeature;
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void setDetectArea(Camera.Size size) {
            mFaceAuth.setDetectArea(0, 0, size.height, size.width);
        }

        @Override
        public void onTimeout() {
            try {
                stopEnroll();
                mFaceReceiver.onError(3, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCameraError() {
            try {
                stopEnroll();
                mFaceReceiver.onError(5, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    };
    private FaceHandler mWorkHandler;

    @Override
    public int onStartCommand(Intent intent, int i, int i2) {
        return START_REDELIVER_INTENT;
    }

    @Override
    @Nullable
    public IBinder onBind(Intent intent) {
        if (Util.DEBUG) {
            Log.i(TAG, "onBind");
        }
        return mService;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Util.DEBUG) {
            Log.i(TAG, "onCreate start");
        }
        mService = new FaceAuthServiceWrapper();
        HandlerThread handlerThread = new HandlerThread(TAG, -2);
        handlerThread.start();
        mWorkHandler = new FaceHandler(handlerThread.getLooper());
        mShareUtil = new SharedUtil(this);
        mFaceAuth = new ArcImpl(this);
        mUserId = Util.getUserId(this);
        if (Util.isFaceUnlockAvailable(this) && Util.isFaceUnlockEnrolled(this)) {
            mWorkHandler.post(() -> mFaceAuth.init());
        }
        mAlarmManager = getSystemService(AlarmManager.class);
        mIdleTimeoutIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(ALARM_TIMEOUT_FREEZED), 0);
        mLockoutTimeoutIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(ALARM_FAIL_TIMEOUT_LOCKOUT), 0);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_TIMEOUT_FREEZED);
        intentFilter.addAction(ALARM_FAIL_TIMEOUT_LOCKOUT);
        registerReceiver(mTimeoutBroadcastReciever, intentFilter);
        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mScreenReceiver, intentFilter);
        if (Util.DEBUG) {
            Log.d(TAG, "OnCreate end");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Util.DEBUG) {
            Log.d(TAG, "onDestroy");
        }
        mFaceAuth.release();
        unregisterReceiver(mTimeoutBroadcastReciever);
        unregisterReceiver(mScreenReceiver);
    }

    private void stopEnroll() {
        if (mCameraEnrollController != null) {
            mCameraEnrollController.stop(mCameraEnrollServiceCallback);
        }
        mCameraEnrollController = null;
        mEnrollToken = null;
        mFaceAuth.saveFeatureStop();
    }

    private void stopAuthrate() {
        synchronized (this) {
            if (mCameraAuthController != null) {
                mCameraAuthController.stop();
            }
            mCameraAuthController = null;
        }
        mFaceAuth.compareStop();
    }

    private void stopCurrentWork() {
        if (mCameraAuthController != null) {
            try {
                mFaceReceiver.onError(10, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            stopAuthrate();
        }
        if (mCameraEnrollController != null) {
            try {
                mFaceReceiver.onError(10, 0);
            } catch (RemoteException e2) {
                e2.printStackTrace();
            }
            stopEnroll();
        }
    }

    private void startIdleTimer() {
        mOnIdleTimer = true;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + DEFAULT_IDLE_TIMEOUT_MS, mIdleTimeoutIntent);
    }

    private void cancelIdleTimer() {
        mOnIdleTimer = false;
        mAlarmManager.cancel(mIdleTimeoutIntent);
    }

    private void startLockoutTimer() {
        long elapsedRealtime = SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS;
        mOnLockoutTimer = true;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, elapsedRealtime, mLockoutTimeoutIntent);
    }

    private void cancelLockoutTimer() {
        if (mOnLockoutTimer) {
            mAlarmManager.cancel(mLockoutTimeoutIntent);
            mOnLockoutTimer = false;
        }
    }

    private void increaseAndCheckLockout() {
        synchronized (mAuthErrorCount) {
            mAuthErrorCount = mAuthErrorCount + 1;
            mAuthErrorThrottleCount = mAuthErrorThrottleCount + 1;
            if (mAuthErrorThrottleCount >= MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
                synchronized (mLockout) {
                    Log.d(TAG, "Too many attempts, lockout permanent");
                    mLockout = true;
                }
            } else if (mAuthErrorCount >= MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED) {
                synchronized (mLockout) {
                    Log.d(TAG, "Too many attempts, lockout for 30s");
                    mLockout = true;
                }
                mAuthErrorCount = 0;
                startLockoutTimer();
            }
        }
    }

    private void resetLockoutCount() {
        synchronized (mAuthErrorCount) {
            mAuthErrorCount = 0;
            mAuthErrorThrottleCount = 0;
        }
        cancelLockoutTimer();
    }

    private final class FaceAuthServiceWrapper extends IFaceService.Stub {
        private FaceAuthServiceWrapper() {
        }

        @Override
        public boolean getFeature(int i, int i2) {
            return false;
        }

        @Override
        public void setFeature(int i, boolean z, byte[] bArr, int i2) {
        }

        @Override
        public void setCallback(IFaceServiceReceiver faceServiceReceiver) {
            mFaceReceiver = faceServiceReceiver;
        }

        @Override
        public void enroll(byte[] bArr, int i, int[] iArr) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "enroll");
            }
            boolean z = true;
            if (!Util.isFaceUnlockAvailable(FaceAuthService.this) || mChallenge == 0 || bArr == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("enroll error ! hasChallenge = ");
                sb.append(mChallenge != 0);
                sb.append(" hasCryptoToken = ");
                if (bArr == null) {
                    z = false;
                }
                sb.append(z);
                Log.e(TAG, sb.toString());
                try {
                    mFaceReceiver.onError(3, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                mEnrollToken = bArr;
                int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
                if (intValue > 0) {
                    mFaceAuth.deleteFeature(intValue - 1);
                    mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_FACE_ID);
                    mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_ENROLL_TOKEN);
                }
                resetLockoutCount();
                mWorkHandler.post(() -> {
                    mFaceAuth.saveFeatureStart();
                    synchronized (this) {
                        if (mCameraEnrollController == null) {
                            mCameraEnrollController = CameraFaceEnrollController.getInstance();
                        }
                        mCameraEnrollController.start(mCameraEnrollServiceCallback, 0);
                    }
                });
            }
        }

        @Override
        public void cancel() {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "cancel");
            }
            mWorkHandler.post(() -> {
                if (mCameraAuthController != null) {
                    stopAuthrate();
                }
                if (mCameraEnrollController != null) {
                    stopEnroll();
                }
                try {
                    mFaceReceiver.onError(5, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public void authenticate(long j) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "authenticate");
            }
            if (!Util.isFaceUnlockAvailable(FaceAuthService.this)) {
                try {
                    mFaceReceiver.onError(5, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else if (mLockout) {
                try {
                    mFaceReceiver.onError(8, 0);
                } catch (RemoteException e2) {
                    e2.printStackTrace();
                }
            } else {
                mWorkHandler.post(() -> {
                    mFaceAuth.compareStart();
                    synchronized (this) {
                        if (mCameraAuthController == null) {
                            mCameraAuthController = new CameraFaceAuthController(FaceAuthService.this, mCameraAuthControllerCallback);
                        } else {
                            mCameraAuthController.stop();
                        }
                        mCameraAuthController.start();
                    }
                });
            }
        }

        @Override
        public void remove(final int i) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "remove");
            }
            mWorkHandler.post(() -> {
                int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
                if (!(i == 0 || intValue == i)) {
                    Log.e(TAG, "Remove unsaved feature! " + i);
                }
                mFaceAuth.deleteFeature(intValue - 1);
                mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_FACE_ID);
                mShareUtil.removeSharePreferences(AppConstants.SHARED_KEY_ENROLL_TOKEN);
                Util.setFaceUnlockAvailable(getApplicationContext());
                try {
                    mFaceReceiver.onRemoved(new int[]{i}, mUserId);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public int enumerate() {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "enumerate");
            }
            int intValue = mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
            final int[] iArr = intValue > -1 ? new int[]{intValue} : new int[0];
            mWorkHandler.post(() -> {
                try {
                    if (mFaceReceiver != null) {
                        mFaceReceiver.onEnumerate(iArr, mUserId);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
            return 0;
        }

        @Override
        public int getFeatureCount() {
            return mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID) > -1 ? 1 : 0;
        }

        @Override
        public long generateChallenge(int i) {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "generateChallenge + " + i);
            }
            if (mChallengeCount <= 0 || mChallenge == 0) {
                mChallenge = new Random().nextLong();
            }
            mChallengeCount += 1;
            mWorkHandler.removeMessages(MSG_CHALLENGE_TIMEOUT);
            mWorkHandler.sendEmptyMessageDelayed(MSG_CHALLENGE_TIMEOUT, i * 1000);
            return mChallenge;
        }

        @Override
        public int revokeChallenge() {
            if (Util.DEBUG) {
                Log.d(FaceAuthService.TAG, "revokeChallenge");
            }
            mChallengeCount -= 1;
            if (mChallengeCount <= 0 && mChallenge != 0) {
                mChallenge = 0;
                mChallengeCount = 0;
                mWorkHandler.removeMessages(MSG_CHALLENGE_TIMEOUT);
                stopCurrentWork();
            }
            return 0;
        }

        @Override
        public int getAuthenticatorId() {
            return mShareUtil.getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID);
        }

        @Override
        public void resetLockout(byte[] bArr) {
            synchronized (mLockout) {
                mLockout = false;
            }
        }
    }

    private class FaceHandler extends Handler {
        public FaceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            if (message.what == MSG_CHALLENGE_TIMEOUT) {
                mChallenge = 0;
                mChallengeCount = 0;
                stopCurrentWork();
            }
        }
    }
}
