package rikka.shizuku;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import moe.shizuku.api.BinderContainer;
import rikka.sui.Sui;

/**
 * <p>
 * This provider receives binder from Shizuku server. When app process starts,
 * Shizuku server (it runs under adb/root) will send the binder to client apps with this provider.
 * </p>
 * <p>
 * Add the provider to your manifest like this:
 * </p>
 * <pre class="prettyprint">&lt;manifest&gt;
 *    ...
 *    &lt;application&gt;
 *        ...
 *        &lt;provider
 *            android:name="rikka.shizuku.ShizukuProvider"
 *            android:authorities="${applicationId}.shizuku"
 *            android:exported="true"
 *            android:multiprocess="false"
 *            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
 *        &lt;/provider&gt;
 *        ...
 *    &lt;/application&gt;
 * &lt;/manifest&gt;</pre>
 *
 * <p>
 * There are something needs you attention:
 * </p>
 * <ol>
 * <li><code>android:permission</code> shoule be a permission that granted to Shell (com.android.shell)
 * but not normal apps (e.g., android.permission.INTERACT_ACROSS_USERS_FULL), so that it can only
 * be used by the app itself and Shizuku server.</li>
 * <li><code>android:exported</code> must be <code>true</code> so that the provider can be accessed
 * from Shizuku server runs under adb.</li>
 * <li><code>android:multiprocess</code> must be <code>false</code>
 * since Shizuku server only gets uid when app starts.</li>
 * </ol>
 * <p>
 * If your app runs in multiple processes, this provider also provides the functionality of sharing
 * the binder across processes. See {@link #enableMultiProcessSupport(boolean)}.
 * </p>
 */

/**
 * ShizukuProvider는 Shizuku 서버로부터 바인더를 수신하는 Content Provider입니다.
 * 이 프로바이더는 클라이언트 앱이 Shizuku 서버와 통신할 수 있게 바인더를 전달합니다.
 * 또한 여러 프로세스 간 바인더 객체를 공유할 수 있는 기능도 제공합니다.
 */

//ShizukuProvider는 Shizuku 서버와의 IPC 통신을 관리하고, 여러 프로세스 간 바인더 객체를 공유하기 위한 중요한 역할을 합니다.
public class ShizukuProvider extends ContentProvider {

    private static final String TAG = "ShizukuProvider"; // 로그 출력을 위한 태그

    // Shizuku 서버가 바인더를 전달하기 위한 메소드 이름
    public static final String METHOD_SEND_BINDER = "sendBinder";

    // 프로세스 간 바인더 공유를 위한 메소드 이름
    public static final String METHOD_GET_BINDER = "getBinder";

    // 바인더 수신 시 사용하는 액션 이름
    public static final String ACTION_BINDER_RECEIVED = "moe.shizuku.api.action.BINDER_RECEIVED";
    // 바인더를 전달할 때 사용하는 Intent의 추가 정보 키
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    // Shizuku 권한을 요청하기 위한 퍼미션
    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    // Shizuku 관리 앱의 애플리케이션 ID
    public static final String MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api";

    private static boolean enableMultiProcess = false;       // 다중 프로세스 지원 여부

    private static boolean isProviderProcess = false;        // 현재 프로세스가 Shizuku 프로바이더 프로세스인지 여부

    private static boolean enableSuiInitialization = true;   // Sui 초기화 활성화 여부

    // 현재 프로세스가 프로바이더 프로세스인지 설정
    public static void setIsProviderProcess(boolean isProviderProcess) {
        ShizukuProvider.isProviderProcess = isProviderProcess;
    }

    /**
     * Enables built-in multi-process support.
     * <p>
     * This method MUST be called as early as possible (e.g., static block in Application).
     */

    /**
     * 다중 프로세스 지원을 활성화하는 메소드.
     * 애플리케이션이 시작할 때 가능한 한 빨리 호출해야 합니다.
     */
    public static void enableMultiProcessSupport(boolean isProviderProcess) {
        Log.d(TAG, "Enable built-in multi-process support (from " + (isProviderProcess ? "provider process" : "non-provider process") + ")");

        ShizukuProvider.isProviderProcess = isProviderProcess;
        ShizukuProvider.enableMultiProcess = true;
    }

    /**
     * Disable automatic Sui initialization.
     */

    /**
     * Sui 자동 초기화를 비활성화하는 메소드.
     */
    public static void disableAutomaticSuiInitialization() {
        ShizukuProvider.enableSuiInitialization = false;
    }

    /**
     * Require binder for non-provider process, should have {@link #enableMultiProcessSupport(boolean)} called first.
     *
     * @param context Context
     */

    /**
     * 프로바이더 프로세스가 아닌 경우 바인더를 요청하는 메소드.
     * 다중 프로세스 지원이 먼저 활성화되어 있어야 합니다.
     */
    public static void requestBinderForNonProviderProcess(@NonNull Context context) {
        if (isProviderProcess) {  // 현재 프로세스가 프로바이더 프로세스라면 리턴
            return;
        }

        Log.d(TAG, "request binder in non-provider process");

        // BroadcastReceiver를 사용해 바인더를 수신함
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BinderContainer container = intent.getParcelableExtra(EXTRA_BINDER);
                if (container != null && container.binder != null) {
                    Log.i(TAG, "binder received from broadcast");
                    Shizuku.onBinderReceived(container.binder, context.getPackageName());
                }
            }
        };

        // Android 버전에 따라 BroadcastReceiver 등록
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED));
        }

        // ContentProvider에서 바인더 요청
        Bundle reply;
        try {
            reply = context.getContentResolver().call(Uri.parse("content://" + context.getPackageName() + ".shizuku"),
                    ShizukuProvider.METHOD_GET_BINDER, null, new Bundle());
        } catch (Throwable tr) {
            reply = null;
        }

        // 바인더를 수신했는지 확인 후 처리
        if (reply != null) {
            reply.setClassLoader(BinderContainer.class.getClassLoader());

            BinderContainer container = reply.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                Log.i(TAG, "Binder received from other process");
                Shizuku.onBinderReceived(container.binder, context.getPackageName());
            }
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);

        // multiprocess가 true이면 예외 발생
        if (info.multiprocess)
            throw new IllegalStateException("android:multiprocess must be false");

        // exported가 false이면 예외 발생
        if (!info.exported)
            throw new IllegalStateException("android:exported must be true");

        isProviderProcess = true;  // 현재 프로세스를 프로바이더 프로세스로 설정
    }

    @Override
    public boolean onCreate() {
        // Sui가 활성화되어 있지 않다면 초기화
        if (enableSuiInitialization && !Sui.isSui()) {
            boolean result = Sui.init(getContext().getPackageName());
            Log.d(TAG, "Initialize Sui: " + result);
        }
        return true;
    }

    // Shizuku 프로바이더에서 메소드 호출에 따른 처리
    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (Sui.isSui()) {
            Log.w(TAG, "Provider called when Sui is available. Are you using Shizuku and Sui at the same time?");
            return new Bundle();
        }

        if (extras == null) {
            return null;
        }

        extras.setClassLoader(BinderContainer.class.getClassLoader());

        Bundle reply = new Bundle();
        switch (method) {
            case METHOD_SEND_BINDER: {    // 바인더 전송 처리
                handleSendBinder(extras);
                break;
            }
            case METHOD_GET_BINDER: {     // 바인더 요청 처리
                if (!handleGetBinder(reply)) {
                    return null;
                }
                break;
            }
        }
        return reply;
    }

    // 바인더를 받았을 때 처리
    private void handleSendBinder(@NonNull Bundle extras) {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "sendBinder is called when already a living binder");
            return;
        }

        BinderContainer container = extras.getParcelable(EXTRA_BINDER);

        // 다중 프로세스 지원이 활성화되어 있으면 바인더를 브로드캐스트
        if (container != null && container.binder != null) { // 바인더가 있으면 Shizuku에 전달
            Log.d(TAG, "binder received");

            Shizuku.onBinderReceived(container.binder, getContext().getPackageName());

            if (enableMultiProcess) {
                Log.d(TAG, "broadcast binder");

                Intent intent = new Intent(ACTION_BINDER_RECEIVED)
                        .putExtra(EXTRA_BINDER, container)
                        .setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);
            }
        }
    }

    // 바인더 요청 처리
    private boolean handleGetBinder(@NonNull Bundle reply) {
        // Other processes in the same app can read the provider without permission
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder())
            return false;

        // 바인더가 존재하면 reply에 추가하여 반환
        reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
        return true;
    }

    // 아래의 기본적인 ContentProvider 메소드는 사용하지 않음
    @Nullable
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public final String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
