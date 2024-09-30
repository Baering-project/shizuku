package rikka.shizuku;

// 상수를 정의하는 부분. Shizuku API에서 사용하는 상수들을 임포트.
import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;

import android.content.ComponentName;      // 컴포넌트 이름 클래스
import android.content.ServiceConnection;  // 서비스 연결 인터페이스
import android.content.pm.PackageManager;  // 패키지 매니저로 권한 확인
import android.os.Bundle;                  // 데이터를 번들로 저장
import android.os.Handler;                 // 메시지 핸들러
import android.os.IBinder;                 // 바인더 인터페이스
import android.os.Looper;                  // 메시지 루퍼
import android.os.Parcel;                  // 데이터 송수신에 사용되는 Parcel
import android.os.RemoteException;         // 원격 호출 실패 예외
import android.util.Log;                   // 로그를 기록하기 위한 유틸리티

import androidx.annotation.NonNull;        // null 이 될 수 없는 어노테이션
import androidx.annotation.Nullable;       // null 이 될 수 있는 어노테이션
import androidx.annotation.RestrictTo;     // 라이브러리 내에서만 사용할 수 있도록 제한하는 어노테이션

import java.util.ArrayList;         // 리스트 구현
import java.util.List;              // 리스트 인터페이스
import java.util.Objects;           // 객체 비교 유틸리티

import moe.shizuku.server.IShizukuApplication; // Shizuku 서버 애플리케이션 인터페이스
import moe.shizuku.server.IShizukuService;     // Shizuku 서버 서비스 인터페이스

// Shizuku 클래스: Shizuku 서비스와 연결하고 상호작용하는 API를 제공
public class Shizuku {

    // Shizuku 서비스와 바인더에 대한 참조 변수
    private static IBinder binder;           // 서비스 바인더 인터페이스
    private static IShizukuService service;  // Shizuku 서비스 인터페이스

    // 서버의 UID, API 버전, 패치 버전, SELinux 컨텍스트, 권한 상태 등의 정보를 저장하는 변수들
    private static int serverUid = -1;                                      // 서버의 UID (초기값 -1)
    private static int serverApiVersion = -1;                               // 서버의 API 버전 (초기값 -1)
    private static int serverPatchVersion = -1;                             // 서버 패치 버전 (초기값 -1)
    private static String serverContext = null;                             // 서버의 SELinux 컨텍스트
    private static boolean permissionGranted = false;                       // 권한 부여 여부
    private static boolean shouldShowRequestPermissionRationale = false;    // 권한 요청 전에 설명 표시 여부
    private static boolean preV11 = false;                                  // API 11 이전 버전인지 여부
    private static boolean binderReady = false;                             // 바인더가 준비되었는지 여부

    // Shizuku 애플리케이션 인터페이스. 서버와의 상호작용을 위한 Stub 구현.
    private static final IShizukuApplication SHIZUKU_APPLICATION = new IShizukuApplication.Stub() {

        // 애플리케이션이 바인딩될 때 서버로부터 정보를 받아옴
        @Override
        public void bindApplication(Bundle data) {
            // 서버에서 받아온 UID, API 버전, 패치 버전, SELinux 컨텍스트, 권한 여부를 설정
            serverUid = data.getInt(BIND_APPLICATION_SERVER_UID, -1);
            serverApiVersion = data.getInt(BIND_APPLICATION_SERVER_VERSION, -1);
            serverPatchVersion = data.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION, -1);
            serverContext = data.getString(BIND_APPLICATION_SERVER_SECONTEXT);
            permissionGranted = data.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false);
            shouldShowRequestPermissionRationale = data.getBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);

            // 바인더 수신 리스너를 실행 예약
            scheduleBinderReceivedListeners();
        }

        // 권한 요청 결과를 처리하는 메서드
        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false);
            // 권한 요청 결과 리스너 실행
            scheduleRequestPermissionResultListener(requestCode, allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }

        // 권한 확인 다이얼로그를 표시하는 메서드 (비앱 환경에서 사용됨)
        @Override
        public void showPermissionConfirmation(int requestUid, int requestPid, String requestPackageName, int requestCode) {
            // 앱이 아닌 경우 처리 없음
        }
    };

    // 바인더가 죽었을 때 호출되는 DeathRecipient 인터페이스 구현
    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        binderReady = false;                              // 바인더가 준비되지 않음을 나타냄
        onBinderReceived(null, null);// 바인더를 초기화하고 상태 갱신
    };

    // API 13 이상에서 애플리케이션을 바인딩하는 메서드
    private static boolean attachApplicationV13(IBinder binder, String packageName) throws RemoteException {
        boolean result; // 결과 저장 변수

        // 서버에 전달할 번들 생성
        Bundle args = new Bundle();
        args.putInt(ATTACH_APPLICATION_API_VERSION, ShizukuApiConstants.SERVER_VERSION); // API 버전 추가
        args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName);                    // 패키지 이름 추가

        // 데이터 송수신을 위한 Parcel 객체 생성
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            // 인터페이스 토큰 설정, 애플리케이션 바인더 추가, 버전 정보 추가
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
            data.writeInt(1);
            args.writeToParcel(data, 0);
            // 서버와의 트랜잭션 수행
            result = binder.transact(18 /*IShizukuService.Stub.TRANSACTION_attachApplication*/, data, reply, 0);
            reply.readException();       // 예외 처리
        } finally {
            reply.recycle();  // Parcel 객체 해제
            data.recycle();   // Parcel 객체 해제
        }

        return result; // 트랜잭션 결과 반환
    }

    // API 11 이상에서 애플리케이션을 바인딩하는 메서드 (API 13 이전)
    private static boolean attachApplicationV11(IBinder binder, String packageName) throws RemoteException {
        boolean result; // 결과 저장 변수

        Parcel data = Parcel.obtain();  // 데이터 송수신을 위한 Parcel 객체 생성
        Parcel reply = Parcel.obtain(); // 결과 수신을 위한 Parcel 객체 생성
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService"); // 인터페이스 토큰 설정
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());                     // 애플리케이션 바인더 추가
            data.writeString(packageName);                                              // 패키지 이름 추가
            result = binder.transact(14 /*IShizukuService.Stub.TRANSACTION_attachApplication*/, data, reply, 0); // 트랜잭션 실행
            reply.readException();    // 예외 처리
        } finally {
            reply.recycle();       // Parcel 객체 해제
            data.recycle();        // Parcel 객체 해제
        }

        return result;             // 트랜잭션 결과 반환
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        if (binder == newBinder) return; // 이미 동일한 바인더를 수신했다면 리턴

        if (newBinder == null) {
            // 새로운 바인더가 null인 경우, 기존 바인더와 서비스 초기화
            binder = null;
            service = null;
            serverUid = -1;
            serverApiVersion = -1;
            serverContext = null;

            scheduleBinderDeadListeners(); // 바인더 죽음 리스너 실행 예약
        } else {
            if (binder != null) {
                // 기존 바인더가 있으면 죽음 리스너 제거
                binder.unlinkToDeath(DEATH_RECIPIENT, 0); // 바인더에 죽음 리스너 연결
            }
            binder = newBinder;                                    // 새로운 바인더 설정
            service = IShizukuService.Stub.asInterface(newBinder); // 새로운 서비스 설정

            try {
                binder.linkToDeath(DEATH_RECIPIENT, 0); // 바인더에 죽음 리스너 연결
            } catch (Throwable e) {
                Log.i("ShizukuApplication", "attachApplication");  // 예외 발생 시 로그 기록
            }

            try {
                // API 13 및 11 이상에서 애플리케이션 바인딩 시도
                if (!attachApplicationV13(binder, packageName) && !attachApplicationV11(binder, packageName)) {
                    preV11 = true; // 실패 시 API 11 이하 버전으로 설정
                }
                Log.i("ShizukuApplication", "attachApplication"); // 성공 시 로그 기록
            } catch (Throwable e) {
                Log.w("ShizukuApplication", Log.getStackTraceString(e)); // 예외 발생 시 경고 로그 기록
            }

            if (preV11) {
                binderReady = true; // API 11 이하에서 바인더 준비 완료 설정
                scheduleBinderReceivedListeners(); // 바인더 수신 리스너 실행 예약
            }
        }
    }

    // Shizuku 서비스와 상호작용하는 다양한 리스너 인터페이스를 정의하는 부분입니다.
    public interface OnBinderReceivedListener {
        // 바인더가 수신되었을 때 호출되는 리스너
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        // 바인더가 더 이상 유효하지 않을 때 호출되는 리스너
        void onBinderDead();
    }

    public interface OnRequestPermissionResultListener {

        /**
         * 권한 요청의 결과를 처리하기 위한 콜백.
         *
         * @param requestCode 요청에 전달된 코드입니다. {@link #requestPermission(int)}.
         * @param grantResult 권한 부여 결과로, {@link android.content.pm.PackageManager#PERMISSION_GRANTED}
         *                    또는  {@link android.content.pm.PackageManager#PERMISSION_DENIED}. 중 하나입니다.
         */
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    // 리스너를 담기 위한 Holder 클래스. 각 리스너와 관련된 핸들러를 함께 보관.
    private static class ListenerHolder<T> {

        private final T listener;      // 리스너 객체
        private final Handler handler; // 핸들러 (리스너를 실행할 스레드 컨텍스트)

        // 생성자
        private ListenerHolder(@NonNull T listener, @Nullable Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ListenerHolder<?> that = (ListenerHolder<?>) o;
            return Objects.equals(listener, that.listener) && Objects.equals(handler, that.handler);
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener, handler);
        }
    }

    // 바인더 수신 리스너, 바인더 사망 리스너, 권한 결과 리스너를 저장하는 리스트
    private static final List<ListenerHolder<OnBinderReceivedListener>> RECEIVED_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnBinderDeadListener>> DEAD_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnRequestPermissionResultListener>> PERMISSION_LISTENERS = new ArrayList<>();
    // 메인 스레드에서 메시지를 처리하기 위한 핸들러
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 바인더가 수신되었을 때 호출될 리스너를 추가합니다.
     * <p>
     * Shizuku API는 바인더를 수신한 후에만 사용할 수 있습니다. 그렇지 않으면 IllegalStateException이 발생합니다.
     * {@link IllegalStateException} will be thrown.
     *
     * <p>Note:</p>
     * <ul>
     * <li>The listener will be called in main thread.</li>
     * <li>The listener could be called multiply times. For example, user restarts Shizuku when app is running.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListener(listener, null); // 핸들러 없이 기본 메서드 호출
    }

    /**
     * 바인더가 수신되었을 때 호출될 리스너를 추가합니다.
     * 핸들러를 지정하여 리스너가 실행될 스레드를 설정할 수 있습니다.
     * Add a listener that will be called when binder is received.
     * <p>
     * Shizuku APIs can only be used when the binder is received, or a
     * {@link IllegalStateException} will be thrown.
     *
     * <p>Note:</p>
     * <ul>
     * <li>The listener could be called multiply times. For example, user restarts Shizuku when app is running.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     * @param handler  Where the listener would be called. If null, the listener will be called in main thread.
     */
    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), false, handler);
    }

    /**
     * 바인더가 이미 수신된 경우 리스너를 즉시 호출하는 버전입니다.
     * Same to {@link #addBinderReceivedListener(OnBinderReceivedListener)} but only call the listener
     * immediately if the binder is already received.
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListenerSticky(Objects.requireNonNull(listener), null);
    }

    /**
     * 바인더가 이미 수신된 경우 리스너를 즉시 호출하는 버전입니다.
     * 핸들러를 지정하여 리스너가 실행될 스레드를 설정할 수 있습니다.
     * Same to {@link #addBinderReceivedListener(OnBinderReceivedListener)} but only call the listener
     * immediately if the binder is already received.
     *
     * @param listener OnBinderReceivedListener
     * @param handler  Where the listener would be called. If null, the listener will be called in main thread.
     */
    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), true, handler);
    }

    // 내부적으로 리스너를 추가하는 메서드. sticky 매개변수로 바인더가 수신된 상태인지 여부를 제어.
    private static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, boolean sticky, @Nullable Handler handler) {
        if (sticky && binderReady) {
            // 바인더가 이미 준비된 상태라면 리스너 즉시 호출
            if (handler != null) {
                handler.post(listener::onBinderReceived); // 핸들러에 의해 리스너 호출
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived(); // 메인 스레드에서 바로 실행
            } else {
                MAIN_HANDLER.post(listener::onBinderReceived); // 메인 핸들러에서 실행
            }
        }
        // 리스너 리스트에 추가
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    /**
     * 등록된 바인더 수신 리스너를 제거합니다.
     * Remove the listener added by {@link #addBinderReceivedListener(OnBinderReceivedListener)}
     * or {@link #addBinderReceivedListenerSticky(OnBinderReceivedListener)}.
     *
     * @param listener OnBinderReceivedListener
     * @return 리스너가 제거되었는지 여부를 반환
     */
    public static boolean removeBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        // 리스트에서 해당 리스너 제거
        synchronized (RECEIVED_LISTENERS) {
            return RECEIVED_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    // 바인더가 수신되었을 때 모든 등록된 리스너를 실행하는 메서드
    private static void scheduleBinderReceivedListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderReceivedListener> holder : RECEIVED_LISTENERS) {
                // 핸들러가 설정되어 있으면 핸들러를 통해 실행
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderReceived);
                } else {
                    // 메인 스레드 또는 핸들러에서 리스너 호출
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderReceived();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderReceived);
                    }
                }
            }
        }
        binderReady = true; // 바인더 준비 완료 표시
    }

    /**
     * 바인더가 더 이상 유효하지 않을 때 호출될 리스너를 추가합니다.
     * <p>Note:</p>
     * <ul>
     * <li>The listener will be called in main thread.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        addBinderDeadListener(listener, null); // 기본적으로 핸들러는 null
    }

    /**
     * * 바인더가 더 이상 유효하지 않을 때 호출될 리스너를 추가합니다.
     *  * 핸들러를 지정하여 리스너가 실행될 스레드를 설정할 수 있습니다.
     * Add a listener that will be called when binder is dead.
     *
     * @param listener OnBinderReceivedListener
     * @param handler  Where the listener would be called. If null, the listener will be called in main thread.
     */
    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            DEAD_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    /**
     * 등록된 바인더 삭제 리스너를 제거합니다.
     * Remove the listener added by {@link #addBinderDeadListener(OnBinderDeadListener)}.
     *
     * @param listener OnBinderDeadListener 삭제 리스너
     * @return 리스너가 제거되었는지 여부
     */
    public static boolean removeBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return DEAD_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    // 바인더가 더 이상 유효하지 않을 때 모든 등록된 리스너를 실행하는 메서드
    private static void scheduleBinderDeadListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderDeadListener> holder : DEAD_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderDead);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderDead();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderDead);
                    }
                }

            }
        }
    }

    /**
     * 권한 요청 결과를 처리할 리스너를 추가합니다.
     * Add a listener to receive the result of {@link #requestPermission(int)}.
     * <p>Note:</p>
     * <ul>
     * <li>The listener will be called in main thread.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        addRequestPermissionResultListener(listener, null); // 기본 핸들러는 null
    }

    /**
     * 권한 요청 결과를 처리할 리스너를 추가합니다.
     * 핸들러를 지정하여 리스너가 실행될 스레드를 설정할 수 있습니다.
     * Add a listener to receive the result of {@link #requestPermission(int)}.
     *
     * @param listener OnBinderReceivedListener
     * @param handler  Where the listener would be called. If null, the listener will be called in main thread.
     */
    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            PERMISSION_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    /**
     * 등록된 권한 요청 결과 리스너를 제거합니다.
     * Remove the listener added by {@link #addRequestPermissionResultListener(OnRequestPermissionResultListener)}.
     *
     * @param listener OnRequestPermissionResultListener
     * @return If the listener is removed.
     */
    public static boolean removeRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return PERMISSION_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    // 권한 요청 결과를 각 리스너에 전달하는 메서드
    private static void scheduleRequestPermissionResultListener(int requestCode, int result) {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnRequestPermissionResultListener> holder : PERMISSION_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onRequestPermissionResult(requestCode, result);
                    } else {
                        MAIN_HANDLER.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                    }
                }
            }
        }
    }

    @NonNull
    protected static IShizukuService requireService() {
        // Shizuku 서비스가 아직 null이라면 예외를 발생시킴
        if (service == null) {
            throw new IllegalStateException("binder haven't been received");
        }
        return service; // 서비스 객체를 반환
    }

    /**
     * 바인더 객체를 반환
     * <p>
     * 일반적인 앱은 이 메서드를 사용하지 않아야 함
     */
    @Nullable
    public static IBinder getBinder() {
        return binder; // 현재 바인더 객체를 반환 (null일 수 있음)
    }

    /**
     * 바인더가 유효한지(ping이 가능한지) 확인
     * <p>
     * 일반적인 앱은 이 메서드를 매번 호출하는 대신 리스너를 사용하는 것이 좋음.
     *
     * @see #addBinderReceivedListener(OnBinderReceivedListener)
     * @see #addBinderReceivedListenerSticky(OnBinderReceivedListener)
     * @see #addBinderDeadListener(OnBinderDeadListener)
     */
    public static boolean pingBinder() {
        // 바인더가 null이 아니고, 바인더가 ping이 가능하면 true 반환
        return binder != null && binder.pingBinder();
    }

    // RemoteException을 RuntimeException으로 변환하여 던지는 메서드
    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }

    /**
     * 원격 서비스에서 IBinder의 transact 메서드를 호출
     * Call {@link IBinder#transact(int, Parcel, Parcel, int)} at remote service.
     * <p>
     * {@link ShizukuBinderWrapper} 원래의 바인더 객체를 감싸는 ShizukuBinderWrapper를 사용해야 함
     *
     * @see ShizukuBinderWrapper
     */
    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            // 원격 서비스에서 transact 호출
            requireService().asBinder().transact(ShizukuApiConstants.BINDER_TRANSACTION_transact, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e); // 예외 발생 시 변환하여 던짐
        }
    }

    /**
     * 원격 서비스에서 새 프로세스를 시작. 매개변수는 {@link Runtime#exec(String, String[], java.io.File)}에 전달됨
     * 버전 11부터는 호출자가 죽으면 프로세스도 자동으로 종료됨.
     * <br>From version 11, like "su", the process will be killed when the caller process is dead. If you have complicated
     * requirements, use {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)}.
     * <p>
     * 여러 스레드에서 RemoteProcess 스트림을 읽고 쓰는 것이 필요할 수 있음.
     * </p>
     *
     * @return 원격 프로세스를 담고 있는 ShizukuRemoteProcess 객체 반환
     * @deprecated 이 메서드는 "su"에서 전환하는 동안에만 사용해야 함.
     * Use {@link Shizuku#transactRemote(Parcel, Parcel, int)} for binder calls and {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)}
     * for complicated requirements.
     * <p>This method is planned to be removed from Shizuku API 14.
     */
    private static ShizukuRemoteProcess newProcess(@NonNull String[] cmd, @Nullable String[] env, @Nullable String dir) {
        try {
            // 새로운 원격 프로세스를 생성하여 반환
            return new ShizukuRemoteProcess(requireService().newProcess(cmd, env, dir));
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e); // 예외 처리
        }
    }

    /**
     * 원격 서비스의 UID를 반환
     * Returns uid of remote service.
     *
     * @return uid
     * @throws IllegalStateException 바인더가 수신되기 전에 호출되었을 때 발생
     */
    public static int getUid() {
        // 이미 UID를 알고 있으면 바로 반환
        if (serverUid != -1) return serverUid;
        try {
            // 원격 서비스에서 UID를 받아와 저장 후 반환
            serverUid = requireService().getUid();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e); // 예외 처리
        } catch (SecurityException e) {
            // Shizuku pre-v11 and permission is not granted
            // Shizuku의 이전 버전에서 권한이 부여되지 않은 경우
            return -1;
        }
        return serverUid;
    }

    /**
     * 원격 서비스 버전을 반환
     *
     * @return 서버 버전
     */
    public static int getVersion() {
        // 이미 서버 버전을 알고 있으면 바로 반환
        if (serverApiVersion != -1) return serverApiVersion;
        try {
            // 원격 서비스에서 버전을 받아와 저장 후 반환
            serverApiVersion = requireService().getVersion();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e); // 예외 처리
        } catch (SecurityException e) {
            // Shizuku pre-v11 and permission is not granted
            // Shizuku 이전 버전 및 권한이 부여되지 않은 경우
            return -1;
        }
        return serverApiVersion;
    }

    /**
     * 원격 서비스 버전이 11 미만인지 확인
     *
     * @return 11 미만이면 true, 그렇지 않으면 false
     */
    public static boolean isPreV11() {
        return preV11; // 버전 11 미만인지 여부를 반환
    }

    /**
     * 이 라이브러리가 릴리스될 당시 최신 서비스 버전을 반환
     *
     * @return 최신 서비스 버전
     * @see Shizuku#getVersion()
     */
    public static int getLatestServiceVersion() {
        return ShizukuApiConstants.SERVER_VERSION; // 상수로 정의된 최신 서비스 버전을 반환
    }

    /**
     * Shizuku 서버 프로세스의 SELinux 컨텍스트를 반환.
     *
     * <p>For adb, context should always be <code>u:r:shell:s0</code>.
     * <br>For root, context depends on su the user uses. E.g., context of Magisk is <code>u:r:magisk:s0</code>.
     * If the user's su does not allow binder calls between su and app, Shizuku will switch to context <code>u:r:shell:s0</code>.
     * </p>
     *
     * @return SELinux context
     * @since Added from version 6
     */
    public static String getSELinuxContext() {
        // 이미 컨텍스트를 알고 있으면 바로 반환
        if (serverContext != null) return serverContext;
        try {
            // 원격 서비스에서 SELinux 컨텍스트를 받아옴
            serverContext = requireService().getSELinuxContext();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } catch (SecurityException e) {
            // Shizuku pre-v11 and permission is not granted
            // Shizuku 이전 버전 및 권한이 부여되지 않은 경우
            return null;
        }
        return serverContext;
    }

    // UserServiceArgs 클래스는 원격 서비스에 전달되는 인자들을 정의함
    public static class UserServiceArgs {

        final ComponentName componentName; // 컴포넌트 이름
        int versionCode = 1;               // 버전 코드
        String processName;                // 프로세스 이름
        String tag;                        // 서비스 태그
        boolean debuggable = false;        // 디버그 가능 여부
        boolean daemon = true;             // 데몬 모드 여부
        boolean use32BitAppProcess = false;// 32비트 앱 프로세스 사용 여부

        // 컴포넌트 이름을 받아 생성자에서 초기화
        public UserServiceArgs(@NonNull ComponentName componentName) {
            this.componentName = componentName;
        }

        /**
         * 데몬 모드 설정. 데몬 모드에서는 프로세스가 영구 실행되며, 비데몬 모드에서는 프로세스가 종료됨.
         * Daemon controls if the service should be run as daemon mode.
         * <br>Under non-daemon mode, the service will be stopped when the app process is dead.
         * <br>Under daemon mode, the service will run forever until {@link Shizuku#unbindUserService(UserServiceArgs, ServiceConnection, boolean)} is called.
         * <p>For upward compatibility reason, {@code daemon} is {@code true} by default.
         *
         * @param daemon Daemon
         */
        public UserServiceArgs daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * 서비스 태그 설정. 이 태그는 서비스 식별에 사용됨.
         * Tag is used to distinguish different services.
         * <p>If you want to obfuscate the user service class, you need to set a stable tag.
         * <p>By default, user service is shared by the same packages installed in all users.
         *
         * @param tag Tag
         */
        public UserServiceArgs tag(@NonNull String tag) {
            this.tag = tag;
            return this;
        }

        /**
         * 서비스의 버전 코드를 설정.
         * 이 버전 코드는 서비스 코드가 업데이트될 때마다 달라져야 함.
         * Version code is used to distinguish different services.
         * <p>Use a different version code when the service code is updated, so that
         * the Shizuku or Sui server can recreate the user service for you.
         *
         * @param versionCode Version code
         */
        public UserServiceArgs version(int versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        /**
         * 서비스가 디버그 가능한지 여부를 설정.
         * Set if the service is debuggable. The process can be found when "Show all processes" is enabled.
         *
         * @param debuggable Debuggable 디버그 가능 여부
         */
        public UserServiceArgs debuggable(boolean debuggable) {
            this.debuggable = debuggable;
            return this;
        }

        /**
         * 사용자 서비스 프로세스의 이름 접미사를 설정.
         * Set if the name suffix of the user service process. The final process name will like
         * <code>com.example:suffix</code>.
         *
         * @param processNameSuffix Name suffix
         */
        public UserServiceArgs processNameSuffix(String processNameSuffix) {
            this.processName = processNameSuffix;
            return this;
        }

        /**
         * 64비트 장치에서 32비트 app_process를 사용할지 여부를 설정.
         * Set if the 32-bits app_process should be used on 64-bits devices.
         * <p>This method will not work on 64-bits only devices.
         * <p>You should NEVER use this method unless if you have special requirements.
         * <p><strong>Reasons:</strong>
         * <p><a href="https://developer.android.com/distribute/best-practices/develop/64-bit">Google has required since August 2019 that all apps submitted to Google Play are 64-bit.</a>
         * <p><a href="https://www.arm.com/blogs/blueprint/64-bit">ARM announced that all Arm Cortex-A CPU mobile cores will be 64-bit only from 2023.</a>
         *
         * @param use32BitAppProcess Use 32bit app_process
         */
        private UserServiceArgs use32BitAppProcess(boolean use32BitAppProcess) {
            this.use32BitAppProcess = use32BitAppProcess;
            return this;
        }

        // 서비스 추가를 위한 옵션을 번들로 반환
        private Bundle forAdd() {
            Bundle options = new Bundle();
            options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, debuggable);
            options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, versionCode);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, daemon);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, use32BitAppProcess);
            options.putString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
                    Objects.requireNonNull(processName, "process name suffix must not be null"));
            if (tag != null) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag);
            }
            return options;
        }

        // 서비스 제거를 위한 옵션을 번들로 반환
        private Bundle forRemove(boolean remove) {
            Bundle options = new Bundle();
            options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName);
            if (tag != null) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag);
            }
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE, remove);
            return options;
        }
    }

    /**
     * User Service는 Android의 Bound Services와 유사합니다.
     * 그러나 이 서비스는 별도의 프로세스에서 실행되며 root(UID 0) 또는 shell(UID 2000)의 권한으로 실행됩니다.
     * <p>
     * 사용자 서비스는 "데몬 모드"에서 실행될 수 있습니다.
     * 기본적으로 데몬 모드에서는 서비스가 영구적으로 실행되며, 비데몬 모드에서는 서비스를 호출한 프로세스가 종료되면 서비스도 종료됩니다.
     * <p>
     * unbind 메서드를 호출해도 사용자 서비스는 자동으로 종료되지 않으므로, 서비스에서 destroy 메서드를 구현해야 합니다.
     * destroy 메서드는 트랜잭션 코드 {@code 16777115} (aidl에서는 {@code 16777114})로 호출되며,
     * 이 메서드에서 정리 작업을 수행한 후 System.exit()를 호출할 수 있습니다.
     * <p>
     * Shizuku 서비스가 중지되거나 다시 시작되면 데몬 모드 여부와 관계없이 사용자 서비스는 종료됩니다.
     * Shizuku는 모든 Shizuku 앱에 바인더를 보내므로, 서비스는 다시 시작할 수 있습니다.
     * <p>
     * <b>사용자 서비스에서 Android API 사용:</b>
     * <p>
     * 사용자 서비스 프로세스에서는 비-SDK API에 대한 제한이 없지만, 정식 Android 애플리케이션 프로세스가 아니기 때문에
     * 일부 API (Context#registerReceiver, Context#getContentResolver 등)는 제대로 동작하지 않습니다.
     * Android 소스 코드를 깊이 분석하여 안전하고 우아하게 구현해야 합니다.
     * <p>
     * 최신 코드를 사용자 서비스에서 사용하려면 Android Studio에서 "Run/Debug configurations"에서
     * "Always install with package manager" 옵션을 활성화해야 합니다.
     *
     * User Service is similar to <a href="https://developer.android.com/guide/components/bound-services">Bound Services</a>.
     * The difference is that the service runs in a different process and as
     * the identity (Linux UID) of root (UID 0) or shell (UID 2000, if the
     * backend is Shizuku and user starts Shizuku with adb).
     * <p>
     * The user service can run under "Daemon mode".
     * Under "Daemon mode" (default behavior), the service will run forever
     * until you call the "unbind" method. Under "Non-daemon mode", the service
     * will be stopped when the process which called the "bind" method is dead.
     * <p>
     * When the "unbind" method is called, the user service will NOT be killed.
     * You need to implement a "destroy" method in your service. The transaction
     * code for that method is {@code 16777115} (use {@code 16777114} in aidl).
     * In this method, you can do some cleanup jobs and call
     * {@link System#exit(int)} in the end.
     * <p>
     * If the backend is Shizuku, whether in daemon mode or not, user service
     * will be killed when Shizuku service is stopped or restarted.
     * Shizuku sends binder to all Shizuku apps. Therefore, you only need to
     * start the user service again.
     * <p>
     * <b>Use Android APIs in user service:</b>
     * <p>
     * There is no restrictions on non-SDK APIs in user service process.
     * However, it is not an valid Android application process. Therefore,
     * even you can acquire an {@code Context} instance, many APIs, such as
     * {@code Context#registerReceiver} and {@code Context#getContentResolver}
     * will not work. You will need to dig into Android source code to find
     * out how things works, so that you will be able to implement your service
     * safely and elegantly.
     * <p>
     * Be aware that, to let the UserService to use the latest code, "Run/Debug congfigurations" -
     * "Always install with package manager" in Android Studio should be checked.
     *
     * @see UserServiceArgs
     * @since Added from version 10
     */
    public static void bindUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        // 주어진 UserServiceArgs를 이용해 ShizukuServiceConnection 객체를 가져옴
        ShizukuServiceConnection connection = ShizukuServiceConnections.get(args);
        // ServiceConnection을 추가함
        connection.addConnection(conn);
        try {
            // 원격 서비스에서 사용자의 서비스를 추가함
            requireService().addUserService(connection, args.forAdd());
        } catch (RemoteException e) {
            // RemoteException 발생 시 런타임 예외로 변환하여 던짐
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Similar to {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)}, 와 유사하지만,
     * but does not start user service if it is not running. 서비스가 실행 중이지 않으면 서비스를 시작하지 않음.
     *
     * @return service version if the service is running, -1 if the service is not running. 서비스가 실행 중이면 서비스 버전을 반환, 실행 중이 아니면 -1을 반환.
     * For Shizuku pre-v13, version is always 0 if service is running. pre-v13 Shizuku의 경우, 서비스가 실행 중이면 항상 0을 반환.
     * @see Shizuku#bindUserService(UserServiceArgs, ServiceConnection)
     * @since Added from version 12 버전 12에서 추가됨
     */
    public static int peekUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        // ShizukuServiceConnection 객체를 가져오고 연결을 추가함
        ShizukuServiceConnection connection = ShizukuServiceConnections.get(args);
        connection.addConnection(conn);
        int result;
        try {
            // 서비스 실행 여부를 확인하는데 사용될 번들을 생성
            Bundle bundle = args.forAdd();
            bundle.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true);
            // 서비스에 사용자 서비스를 추가하고 그 결과를 저장
            result = requireService().addUserService(connection, bundle);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }

        // 버전이 13 이상인 경우 결과 반환
        boolean atLeast13 = !Shizuku.isPreV11() && Shizuku.getVersion() >= 13;
        if (atLeast13) {
            return result;
        }

        // On pre-13, 0 is running
        // pre-13의 경우, 0은 실행 중을 의미
        if (result == 0) {
            return 0;
        }
        // Others are not running
        // 그 외에는 실행 중이 아님을 의미
        return -1;
    }

    /**
     * Remove user service. 사용자 서비스를 제거함.
     * <p>
     * You need to implement a "destroy" method in your service, 서비스에서 "destroy" 메서드를 구현해야 서비스가 종료됨.
     * or the service will not be killed.
     *
     * @param remove Remove (kill) the remote user service. 원격 사용자 서비스를 제거할지 여부
     * @see Shizuku#bindUserService(UserServiceArgs, ServiceConnection)
     */
    public static void unbindUserService(@NonNull UserServiceArgs args, @Nullable ServiceConnection conn, boolean remove) {
        if (remove) {
            try {
                // 원격 서비스에서 사용자 서비스를 제거함
                requireService().removeUserService(null /* (unused) */, args.forRemove(true));
            } catch (RemoteException e) {
                throw rethrowAsRuntimeException(e);
            }
        } else {
            /*
             * When unbindUserService remove=false is called, although the ShizukuServiceConnection
             * instance is removed from ShizukuServiceConnections, it still exists (since its a Binder),
             * and it will still receive "connected" "died" from the service, and then call the callback
             * of its ServiceConnection connections[].
             * This finally leads to the ServiceConnection#onServiceConnected/onServiceDisconnected being
             * called multiple times after bindUserService is called later, which is not expected.
             */

            // remove=false일 때의 처리를 위한 블록
            ShizukuServiceConnection connection = ShizukuServiceConnections.get(args);

            /*
             * For newer versions of the server, we can just call removeUserService with remove=false.
             * This will not kill the service, but will remove the ShizukuServiceConnection instance
             * from the server.
             */

            // 서버가 최신 버전일 경우 remove=false로 사용자 서비스를 제거함
            if (Shizuku.getVersion() >= 14 || Shizuku.getVersion() == 13 && Shizuku.getServerPatchVersion() >= 4) {
                try {
                    requireService().removeUserService(connection, args.forRemove(false));
                } catch (RemoteException e) {
                    throw rethrowAsRuntimeException(e);
                }
            }

            /*
             * As a solution for older versions of the server, we can clear the connections[] here.
             */
            // 구버전 서버의 경우, 연결을 초기화하고 제거함
            connection.clearConnections();
            ShizukuServiceConnections.remove(connection);
        }
    }

    /**
     * Check if remote service has specific permission. 원격 서비스가 특정 권한을 가지고 있는지 확인함.
     *
     * @param permission permission name 확인할 권한 이름
     * @return PackageManager.PERMISSION_DENIED or PackageManager.PERMISSION_GRANTED
     */
    public static int checkRemotePermission(String permission) {
        if (serverUid == 0) return PackageManager.PERMISSION_GRANTED;
        try {
            return requireService().checkPermission(permission);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Request permission. 권한 요청을 수행.
     * <p>
     * Different from runtime permission, you need to add a listener to receive
     * 런타임 권한과는 다르게, 요청 결과를 받으려면 리스너를 추가해야 함.
     * the result.
     *
     * @param requestCode Application specific request code to match with a result    requestCode 요청 코드 (앱에서 특정 요청을 식별하는 데 사용)
     *                    reported to {@link OnRequestPermissionResultListener#onRequestPermissionResult(int, int)}.
     * @see #addRequestPermissionResultListener(OnRequestPermissionResultListener)
     * @see #removeRequestPermissionResultListener(OnRequestPermissionResultListener)
     * @since Added from version 11  버전 11에서 추가됨
     */
    public static void requestPermission(int requestCode) {
        try {
            // 원격 서비스에서 권한 요청을 보냄
            requireService().requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Check if self has permission. 자신의 권한을 확인함.
     *
     * @return Either {@link android.content.pm.PackageManager#PERMISSION_GRANTED}
     * or {@link android.content.pm.PackageManager#PERMISSION_DENIED}.
     * @since Added from version 11
     */
    public static int checkSelfPermission() {
        // 이미 권한이 부여된 경우 바로 반환
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
        try {
            // 원격 서비스에서 권한을 확인하고 결과를 저장
            permissionGranted = requireService().checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return permissionGranted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    /**
     * Should show UI with rationale before requesting the permission.
     * 권한 요청 전 설명 UI를 표시해야 하는지 여부를 확인함.
     *
     * @since Added from version 11   버전 11에서 추가됨
     */
    public static boolean shouldShowRequestPermissionRationale() {
        // 이미 권한이 부여된 경우 설명 UI를 표시할 필요 없음
        if (permissionGranted) return false;
        if (shouldShowRequestPermissionRationale) return true;
        try {
            // 원격 서비스에서 설명 UI 표시 여부를 확인하고 결과를 저장
            shouldShowRequestPermissionRationale = requireService().shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return shouldShowRequestPermissionRationale;
    }

    // --------------------- non-app ----------------------

    /**
     * Shizuku 서비스 종료.
     */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void exit() {
        try {
            requireService().exit();  // 원격 서비스 종료
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * 사용자 서비스를 연결.
     *
     * @param binder 바인더 객체
     * @param options 옵션 번들
     */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void attachUserService(@NonNull IBinder binder, @NonNull Bundle options) {
        try {
            requireService().attachUserService(binder, options); // 원격 서비스에 사용자 서비스 연결
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * 권한 확인 요청 결과 처리.
     *
     * @param requestUid 요청한 UID
     * @param requestPid 요청한 PID
     * @param requestCode 요청 코드
     * @param data 결과 데이터 번들
     */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, @NonNull Bundle data) {
        try {
            requireService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * UID의 플래그를 가져옴.
     *
     * @param uid 확인할 UID
     * @param mask 플래그 마스크
     * @return 플래그 값
     */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getFlagsForUid(int uid, int mask) {
        try {
            return requireService().getFlagsForUid(uid, mask); // 원격 서비스에서 UID에 대한 플래그 확인
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * UID의 플래그를 업데이트.
     *
     * @param uid 업데이트할 UID
     * @param mask 플래그 마스크
     * @param value 업데이트할 값
     */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void updateFlagsForUid(int uid, int mask, int value) {
        try {
            requireService().updateFlagsForUid(uid, mask, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * 서버의 패치 버전을 반환.
     *
     * @return 서버 패치 버전
     */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getServerPatchVersion() {
        return serverPatchVersion;
    }
}
