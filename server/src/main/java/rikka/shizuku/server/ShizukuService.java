package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;
import static rikka.shizuku.server.ServerConstants.MANAGER_APPLICATION_ID;
import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.ddm.DdmHandleAppName;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import kotlin.collections.ArraysKt;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.common.util.BuildUtils;
import moe.shizuku.common.util.OsUtils;
import moe.shizuku.server.IShizukuApplication;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.rish.RishConfig;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.IContentProviderUtils;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.UserHandleCompat;


/**
 * ShizukuService 클래스: 실제 Shizuku 서버의 핵심 기능을 처리하는 서비스 클래스.
 * 이 클래스는 원격으로 연결된 클라이언트와 상호작용하며, 권한 확인, 사용자 서비스 관리,
 * 클라이언트에게 바인더 전달 등의 작업을 수행합니다.
 */
public class ShizukuService extends Service<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> {

    /**
     * main 메서드는 서버의 엔트리 포인트로, 메인 루퍼를 준비하고 ShizukuService 인스턴스를 생성 후 루프를 시작함.
     * 이 메서드는 Android 시스템의 백그라운드에서 계속 실행됩니다.
     */

    // 메인 메서드: 서비스의 진입점
    public static void main(String[] args) {
        // DDM에서 앱 이름 설정 (디버깅을 위해)
        DdmHandleAppName.setAppName("shizuku_server", 0);          // DDM(Debugging Data Model)에서 앱 이름을 설정
        RishConfig.setLibraryPath(System.getProperty("shizuku.library.path")); // 라이브러리 경로 설정

        Looper.prepareMainLooper();    // 메인 루퍼를 준비 (이벤트 루프를 시작하기 전 단계)
        new ShizukuService();          // ShizukuService 인스턴스를 생성
        Looper.loop();                 // 이벤트 루프를 시작 (계속해서 메시지 처리)
    }

    /**
     * 시스템 서비스가 시작될 때까지 대기하는 메서드.
     * name으로 전달된 서비스가 null이 아니면 루프를 탈출함.
     */
    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {  // 서비스가 시작되었는지 확인
            try {
                LOGGER.i("service " + name + " is not started, wait 1s.");  // 로그 기록
                Thread.sleep(1000);         // 1초 대기 후 다시 시도
            } catch (InterruptedException e) {
                LOGGER.w(e.getMessage(), e);      // 예외 발생 시 경고 로그 기록
            }
        }
    }

    /**
     * 관리 앱의 ApplicationInfo를 가져오는 메서드.
     * 관리 앱이 존재하지 않으면 null을 반환합니다.
     */
    public static ApplicationInfo getManagerApplicationInfo() {
        return PackageManagerApis.getApplicationInfoNoThrow(MANAGER_APPLICATION_ID, 0, 0);
    }

    // 메인 스레드에서 메시지를 처리하기 위한 핸들러 선언
    @SuppressWarnings({"FieldCanBeLocal"})
    private final Handler mainHandler = new Handler(Looper.myLooper());
    //private final Context systemContext = HiddenApiBridge.getSystemContext();
    // Shizuku 서비스에서 사용하는 두 가지 주요 매니저 클래스
    private final ShizukuClientManager clientManager; // 클라이언트 관리
    private final ShizukuConfigManager configManager; // 설정 관리
    private final int managerAppId; // 관리 앱의 UID

    /**
     * ShizukuService 생성자.
     * 서비스가 시작되면서 여러 초기화 작업을 수행하고, 클라이언트에게 바인더를 전송하는 작업을 합니다.
     */
    public ShizukuService() {
        super(); // 부모 클래스 Service의 생성자를 호출

        HandlerUtil.setMainHandler(mainHandler); // 메인 핸들러를 설정

        LOGGER.i("starting server...");     // 서버 시작 로그 기록

        // 필수 시스템 서비스들이 시작될 때까지 대기
        waitSystemService("package");
        waitSystemService(Context.ACTIVITY_SERVICE);
        waitSystemService(Context.USER_SERVICE);
        waitSystemService(Context.APP_OPS_SERVICE);

        // 관리 앱의 ApplicationInfo 가져오기
        ApplicationInfo ai = getManagerApplicationInfo();
        if (ai == null) {
            System.exit(ServerConstants.MANAGER_APP_NOT_FOUND); // 관리 앱이 없으면 서버 종료
        }

        assert ai != null;     // ai가 null이 아님을 확인
        managerAppId = ai.uid; // 관리 앱의 UID 저장

        // 설정 및 클라이언트 매니저 초기화
        configManager = getConfigManager();
        clientManager = getClientManager();

        // APK 파일 변경 감시 시작 (관리 앱이 제거되면 서버 종료)
        ApkChangedObservers.start(ai.sourceDir, () -> {
            if (getManagerApplicationInfo() == null) {
                LOGGER.w("manager app is uninstalled in user 0, exiting...");
                System.exit(ServerConstants.MANAGER_APP_NOT_FOUND); // 관리 앱이 제거되면 서버 종료
            }
        });

        // 바인더 등록
        BinderSender.register(this);

        // 메인 핸들러를 통해 클라이언트와 관리자에게 바인더 전송
        mainHandler.post(() -> {
            sendBinderToClient();  // 클라이언트에게 바인더 전송
            sendBinderToManager(); // 관리자에게 바인더 전송
        });
    }

    // 이하의 메서드들은 부모 클래스에서 상속받은 메서드들을 재정의하는 부분입니다.
    @Override
    public ShizukuUserServiceManager onCreateUserServiceManager() {
        return new ShizukuUserServiceManager(); // 사용자 서비스 매니저 생성
    }

    @Override
    public ShizukuClientManager onCreateClientManager() {
        return new ShizukuClientManager(getConfigManager()); // 클라이언트 매니저 생성
    }

    @Override
    public ShizukuConfigManager onCreateConfigManager() {
        return new ShizukuConfigManager();   // 설정 매니저 생성
    }


    /**
     * 호출자의 UID가 관리 앱의 UID와 일치하는지 확인하여 권한을 확인하는 메서드.
     */
    @Override
    public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
        return UserHandleCompat.getAppId(callingUid) == managerAppId; // UID가 일치하면 true 반환
    }

    /**
     * 호출자의 권한을 확인하는 메서드.
     */
    private int checkCallingPermission() {
        try {
            return ActivityManagerApis.checkPermission(ServerConstants.PERMISSION,
                    Binder.getCallingPid(),
                    Binder.getCallingUid()); // 호출자의 PID와 UID를 기반으로 권한을 확인
        } catch (Throwable tr) {
            LOGGER.w(tr, "checkCallingPermission"); // 예외 발생 시 경고 로그 기록
            return PackageManager.PERMISSION_DENIED;    // 권한이 없으면 PERMISSION_DENIED 반환
        }
    }

    /**
     * 호출자의 권한을 확인하는 메서드. 클라이언트 기록이 있는 경우 추가로 확인.
     */
    @Override
    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        if (UserHandleCompat.getAppId(callingUid) == managerAppId) {
            return true;  // 관리 앱에서 호출된 경우 권한 있음
        }
        if (clientRecord == null && checkCallingPermission() == PackageManager.PERMISSION_GRANTED) {
            return true;  // 클라이언트 기록이 없고 권한이 있는 경우 true 반환
        }
        return false;     // 그 외의 경우 권한 없음
    }

    /**
     * 서버를 종료하는 메서드. 관리 앱의 권한을 확인한 후 서버를 종료함.
     */
    @Override
    public void exit() {
        enforceManagerPermission("exit"); // 관리자 권한 확인
        LOGGER.i("exit");                 // 로그 기록
        System.exit(0);                  // 서버 종료
    }

    /**
     * 사용자 서비스를 바인더로 연결하는 메서드.
     */
    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        enforceManagerPermission("func");    // 관리자 권한 확인

        super.attachUserService(binder, options); // 부모 클래스의 attachUserService 호출
    }

    /**
     * 애플리케이션을 바인딩하는 메서드. 클라이언트의 정보를 저장하고, 필요한 권한을 설정한 후 바인딩을 처리함.
     */
    @Override
    public void attachApplication(IShizukuApplication application, Bundle args) {
        if (application == null || args == null) {
            return; // application 또는 args가 null이면 메서드를 종료
        }

        String requestPackageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);  // 요청 패키지 이름 가져오기
        if (requestPackageName == null) {
            return; // 패키지 이름이 없으면 종료
        }
        int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);  // API 버전 가져오기

        int callingPid = Binder.getCallingPid(); // 호출자의 PID 가져오기
        int callingUid = Binder.getCallingUid(); // 호출자의 UID 가져오기
        boolean isManager;                       // 관리 앱 여부를 저장할 변수
        ClientRecord clientRecord = null;        // 클라이언트 기록

        // UID에 해당하는 패키지 이름들의 리스트를 가져옴
        // UID는 Android에서 각 앱에 부여하는 고유 사용자 ID임
        // 호출자의 UID와 요청된 패키지 이름이 일치하는지 확인
        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid);
        // 요청된 패키지 이름이 해당 UID에 속한 패키지들에 포함되어 있는지 확인
        // 포함되어 있지 않으면, 이는 요청된 패키지가 해당 UID에 속하지 않는다는 의미임
        if (!packages.contains(requestPackageName)) {
            // 패키지가 UID에 속하지 않으면 경고 로그를 출력
            LOGGER.w("Request package " + requestPackageName + "does not belong to uid " + callingUid);
            // 패키지와 UID가 일치하지 않는 경우, 보안 예외를 발생시킴
            throw new SecurityException("Request package " + requestPackageName + "does not belong to uid " + callingUid);
        }
        // 요청된 패키지가 관리 앱인지 확인
        isManager = MANAGER_APPLICATION_ID.equals(requestPackageName); // 요청 패키지 이름이 관리 앱의 패키지 이름과 동일한지 확인


        // 호출자의 UID와 PID를 사용하여 클라이언트 매니저에서 클라이언트를 찾음
        // 해당 클라이언트가 없으면 새로운 클라이언트를 추가해야 함
        if (clientManager.findClient(callingUid, callingPid) == null) {
            synchronized (this) { // 여러 스레드에서 동시에 접근하지 않도록 동기화 처리
                // 클라이언트 매니저에 새로운 클라이언트를 추가하고, 그 기록을 반환
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion);
            }
            // 클라이언트 추가에 실패한 경우
            if (clientRecord == null) {
                LOGGER.w("Add client failed"); // 경고 로그 출력
                return;                             // 메서드 종료
            }
        }

        // 디버그 로그 출력: 애플리케이션이 바인딩되었음을 나타냄
        LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid);

        // 서버 버전을 결정 (API 버전과 관련)
        int replyServerVersion = ShizukuApiConstants.SERVER_VERSION;  // 기본적으로 서버 버전을 상수 값으로 설정
        if (apiVersion == -1) {  // API 버전이 -1인 경우, 이는 클라이언트가 서버와의 버전 호환성에 문제가 있다는 의미임
            // ShizukuBinderWrapper has adapted API v13 in dev.rikka.shizuku:api 12.2.0, however
            // attachApplication in 12.2.0 is still old, so that server treat the client as pre 13.
            // This finally cause transactRemote fails.
            // So we can pass 12 here to pretend we are v12 server.
            replyServerVersion = 12; // 서버 버전을 임시로 12로 설정 (이전 버전으로 호환성을 유지하기 위해)
        }

        // 클라이언트에 응답할 정보를 담는 Bundle 객체 생성
        Bundle reply = new Bundle();
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());                                   // 서버 UID를 응답에 추가
        reply.putInt(BIND_APPLICATION_SERVER_VERSION, replyServerVersion);                             // 서버 버전을 응답에 추가
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());               // 서버의 SELinux 컨텍스트를 응답에 추가
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION); // 서버 패치 버전을 응답에 추가
        if (!isManager) { // 요청된 패키지가 관리 앱이 아닌 경우
            reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, Objects.requireNonNull(clientRecord).allowed); // 클라이언트 기록에 저장된 권한 정보를 응답에 추가
            reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);                  // 권한 설명을 보여줘야 하는지 여부를 false로 설정
        }
        try {
            application.bindApplication(reply); // 애플리케이션에 바인딩 정보를 전달
        } catch (Throwable e) {
            LOGGER.w(e, "attachApplication");
        }
    }

    @Override
    public void showPermissionConfirmation(int requestCode, @NonNull ClientRecord clientRecord, int callingUid, int callingPid, int userId) {
        // 클라이언트 기록에서 패키지 이름을 가져와 해당 애플리케이션의 정보(ApplicationInfo)를 가져옴
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(clientRecord.packageName, 0, userId);
        if (ai == null) {
            // 애플리케이션 정보가 없으면 메서드를 종료
            return;
        }

        // 관리 앱의 패키지 정보(PackageInfo)를 가져옴
        PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(MANAGER_APPLICATION_ID, 0, userId);
        // 사용자 정보(UserInfo)를 가져옴
        UserInfo userInfo = UserManagerApis.getUserInfo(userId);

        // 해당 사용자가 작업 프로파일(Work Profile) 사용자인지 여부를 확인
        boolean isWorkProfileUser = BuildUtils.atLeast30() ?
                "android.os.usertype.profile.MANAGED".equals(userInfo.userType) :
                (userInfo.flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;

        // 관리 앱이 없고 사용자가 작업 프로파일 사용자가 아닌 경우
        if (pi == null && !isWorkProfileUser) {
            LOGGER.w("Manager not found in non work profile user %d. Revoke permission", userId);
            // 클라이언트에게 권한이 없음을 알림
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        // 권한 요청을 위한 인텐트를 생성하고 필요한 정보를 추가
        Intent intent = new Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
                .setPackage(MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", callingUid)
                .putExtra("pid", callingPid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai);

        // 작업 프로파일 여부에 따라 적절한 사용자 ID로 인텐트를 시작
        ActivityManagerApis.startActivityNoThrow(intent, null, isWorkProfileUser ? 0 : userId);
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws RemoteException {
        // 호출자가 관리 앱에서 왔는지 확인, 그렇지 않으면 로그 출력 후 종료
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("dispatchPermissionConfirmationResult called not from the manager package");
            return;
        }

        // 데이터가 null이면 메서드 종료
        if (data == null) {
            return;
        }

        // 권한 요청이 허용되었는지와 1회용인지 여부를 확인
        boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED);
        boolean onetime = data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME);

        // 로그 출력
        LOGGER.i("dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
                requestUid, requestPid, requestCode, Boolean.toString(allowed), Boolean.toString(onetime));

        // 요청 UID에 해당하는 클라이언트 목록을 가져옴
        List<ClientRecord> records = clientManager.findClients(requestUid);
        List<String> packages = new ArrayList<>();
        // 클라이언트가 없는 경우 로그 출력
        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid);
        } else {
            // 각 클라이언트 기록에 대해 패키지 이름과 권한 여부를 업데이트
            for (ClientRecord record : records) {
                packages.add(record.packageName);
                record.allowed = allowed;
                // 요청된 PID와 일치하는 경우 결과 전달
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(requestCode, allowed);
                }
            }
        }

        // 1회용 요청이 아니면 설정 매니저에 업데이트
        if (!onetime) {
            configManager.update(requestUid, packages, ConfigManager.MASK_PERMISSION, allowed ? ConfigManager.FLAG_ALLOWED : ConfigManager.FLAG_DENIED);
        }

        // 1회용이 아니고 권한이 허용된 경우 런타임 권한을 설정
        if (!onetime && allowed) {
            int userId = UserHandleCompat.getUserId(requestUid);

            // UID에 대한 패키지 리스트를 가져와 런타임 권한 설정
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(requestUid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT

                // 권한 허용 및 해제 처리
                if (allowed) {
                    PermissionManagerApis.grantRuntimePermission(packageName, PERMISSION, userId);
                } else {
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, userId);
                }
            }
        }
    }

    // UID의 플래그를 내부적으로 가져오는 메서드, 런타임 권한 허용 여부도 처리
    private int  getFlagsForUidInternal(int uid, int mask, boolean allowRuntimePermission) {
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        if (entry != null) {
            return entry.flags & mask; // UID에 해당하는 플래그 반환
        }

        // 런타임 권한이 허용된 경우 권한 확인
        if (allowRuntimePermission && (mask & ConfigManager.MASK_PERMISSION) != 0) {
            int userId = UserHandleCompat.getUserId(uid);
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                // 권한이 허용된 경우 플래그 설정
                try {
                    if (PermissionManagerApis.checkPermission(PERMISSION, uid) == PackageManager.PERMISSION_GRANTED) {
                        return ConfigManager.FLAG_ALLOWED;
                    }
                } catch (Throwable e) {
                    LOGGER.w("getFlagsForUid");
                }
            }
        }
        return 0; // 권한이 없으면 0 반환
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        // 호출자가 관리 앱에서 왔는지 확인
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return 0;
        }
        return getFlagsForUidInternal(uid, mask, true); // 내부 메서드 호출
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) throws RemoteException {
        // 호출자가 관리 앱에서 호출했는지 확인. 호출자의 UID와 관리 앱 UID가 일치하지 않으면 경고 로그를 출력하고 메서드를 종료
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return;
        }

        // UID를 기반으로 사용자의 ID를 가져옴
        int userId = UserHandleCompat.getUserId(uid);

        // 권한을 허용/해제할 때 사용하는 플래그 처리
        if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
            boolean allowed = (value & ConfigManager.FLAG_ALLOWED) != 0; // 허용 여부를 확인
            boolean denied = (value & ConfigManager.FLAG_DENIED) != 0;   // 거부 여부를 확인

            // 주어진 UID와 일치하는 클라이언트 레코드를 찾음
            List<ClientRecord> records = clientManager.findClients(uid);
            // 모든 클라이언트 레코드에 대해 권한을 업데이트
            for (ClientRecord record : records) {
                if (allowed) {
                    // 권한이 허용된 경우 해당 클라이언트의 허용 상태를 true로 설정
                    record.allowed = true;
                } else {
                    // 권한이 거부된 경우 허용 상태를 false로 설정하고 패키지를 강제 종료
                    record.allowed = false;
                    ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid));
                    onPermissionRevoked(record.packageName);
                }
            }

            // UID에 해당하는 모든 패키지를 가져와 권한을 처리
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue; // 권한 정보가 없으면 다음 패키지로 넘어감
                }

                int deviceId = 0;// 기본 기기 ID 설정
                // 권한 허용 또는 해제
                if (allowed) {
                    // 권한이 허용된 경우 런타임 권한을 부여
                    PermissionManagerApis.grantRuntimePermission(packageName, PERMISSION, userId);
                } else {
                    // 권한이 거부된 경우 런타임 권한을 철회
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, userId);
                }

                // TODO 사용자 서비스를 중지하는 로직 추가 필요
            }
        }

        // 설정 매니저에 플래그 업데이트를 요청
        configManager.update(uid, null, mask, value);
    }

    // 권한이 철회되었을 때 호출되는 메서드
    private void onPermissionRevoked(String packageName) {
        // TODO: 런타임 권한 리스너 추가 필요
        // 해당 패키지에 연결된 사용자 서비스를 제거
        getUserServiceManager().removeUserServicesForPackage(packageName);
    }

    // 지정된 사용자 ID에 대한 애플리케이션 목록을 가져오는 메서드
    private ParcelableListSlice<PackageInfo> getApplications(int userId) {
        List<PackageInfo> list = new ArrayList<>(); // 애플리케이션 정보를 저장할 리스트
        List<Integer> users = new ArrayList<>();    // 사용자 ID 리스트
        if (userId == -1) {
            // 사용자 ID가 -1인 경우 모든 사용자를 대상으로 처리
            users.addAll(UserManagerApis.getUserIdsNoThrow());
        } else {
            // 특정 사용자 ID만 처리
            users.add(userId);
        }

        // 각 사용자에 대해 설치된 패키지 정보를 가져옴
        for (int user : users) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, user)) {
                // 관리 앱은 제외
                if (Objects.equals(MANAGER_APPLICATION_ID, pi.packageName)) continue;
                // 애플리케이션 정보가 없는 경우 제외
                if (pi.applicationInfo == null) continue;

                int uid = pi.applicationInfo.uid; // 패키지의 UID를 가져옴
                int flags = 0;                    // 권한 플래그를 초기화
                ShizukuConfig.PackageEntry entry = configManager.find(uid);
                // 해당 UID에 대한 플래그 설정이 있는지 확인
                if (entry != null) {
                    if (entry.packages != null && !entry.packages.contains(pi.packageName))
                        continue; // 설정된 패키지가 포함되지 않으면 건너뜀
                    flags = entry.flags & ConfigManager.MASK_PERMISSION; // 플래그를 적용
                }

                // 권한 플래그가 있는 경우 리스트에 추가
                if (flags != 0) {
                    list.add(pi);
                } else if (pi.applicationInfo.metaData != null
                        && pi.applicationInfo.metaData.getBoolean("moe.shizuku.client.V3_SUPPORT", false)
                        && pi.requestedPermissions != null
                        && ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    // Shizuku 권한이 있는 경우에도 리스트에 추가
                    list.add(pi);
                }
            }

        }
        // 결과 리스트를 Parcelable 형태로 반환
        return new ParcelableListSlice<>(list);
    }

    // 트랜잭션 코드에 따라 처리를 분기하는 메서드
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        //LOGGER.d("transact: code=%d, calling uid=%d", code, Binder.getCallingUid());
        // 요청된 코드가 애플리케이션을 가져오는 트랜잭션인지 확인
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR); // 인터페이스 유효성 검사
            int userId = data.readInt();                                  // 사용자 ID를 읽어옴
            ParcelableListSlice<PackageInfo> result = getApplications(userId); // 해당 사용자 ID에 대한 애플리케이션 목록을 가져옴
            reply.writeNoException();                                          // 예외 없이 트랜잭션 성공 처리
            result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE); // 결과를 반환
            return true;
        }
        // 그 외의 경우는 부모 클래스의 onTransact를 호출
        return super.onTransact(code, data, reply, flags);
    }

    // 모든 사용자에게 바인더를 전송하는 메서드
    void sendBinderToClient() {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(this, userId);
        }
    }

    // 특정 사용자에게 바인더를 전송하는 메서드
    private static void sendBinderToClient(Binder binder, int userId) {
        // 각 사용자의 패키지 목록을 가져옴
        try {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId)) {
                if (pi == null || pi.requestedPermissions == null)
                    continue; // 패키지 또는 권한 정보가 없으면 건너뜀

                // 해당 패키지가 Shizuku 권한을 요청했는지 확인
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    sendBinderToUserApp(binder, pi.packageName, userId); // 바인더 전송
                }
            }
        } catch (Throwable tr) {
            // 패키지 목록을 가져오는 도중 예외 발생 시 로그 기록
            LOGGER.e("exception when call getInstalledPackages", tr);
        }
    }

    // 관리자에게 바인더를 전송하는 메서드
    void sendBinderToManager() {
        sendBinderToManger(this); // 현재 인스턴스를 바인더로 사용하여 전송
    }

    // 특정 사용자에게 바인더를 전송하는 메서드 (관리자용)
    private static void sendBinderToManger(Binder binder) {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToManger(binder, userId); // 각 사용자에게 바인더 전송
        }
    }

    // 특정 사용자와 관리 앱에 바인더 전송
    static void sendBinderToManger(Binder binder, int userId) {
        sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId); // 관리자 패키지에 바인더 전송
    }

    // 주어진 패키지와 사용자에게 바인더를 전송하는 메서드
    static void sendBinderToUserApp(Binder binder, String packageName, int userId) {
        sendBinderToUserApp(binder, packageName, userId, true);
    }

    static void sendBinderToUserApp(Binder binder, String packageName, int userId, boolean retry) {
        try {
            // DeviceIdleControllerApis를 사용하여 해당 패키지를 임시 화이트리스트에 추가, 30초 동안 절전 모드에서 제외
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
                    316/* PowerExemptionManager#REASON_SHELL */, "shell");
            // 로그를 남겨 30초 동안 절전 모드 화이트리스트에 추가되었음을 알림
            LOGGER.v("Add %d:%s to power save temp whitelist for 30s", userId, packageName);
        } catch (Throwable tr) {
            // 절전 모드 화이트리스트 추가 중 오류가 발생하면 예외를 기록
            LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName);
        }

        // 패키지 이름에 ".shizuku"를 추가하여 ContentProvider의 이름을 생성
        String name = packageName + ".shizuku";
        IContentProvider provider = null;

        /*
         When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process)
         will always get a new instance of android.os.BinderProxy.

         In the implementation of getContentProviderExternal and removeContentProviderExternal, received
         IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so
         removeContentProviderExternal will never work.

         Luckily, we can pass null. When token is token, count will be used.
         */
        /*
         바인더 객체를 실제로 다른 프로세스로 전달할 때,
         받는 쪽(여기서는 system_server 프로세스)은 항상 새로운 android.os.BinderProxy 인스턴스를 받습니다.

         getContentProviderExternal 및 removeContentProviderExternal 구현에서,
         받은 IBinder가 HashMap의 키로 사용되는데, BinderProxy는 hashCode()를 구현하지 않기 때문에
         removeContentProviderExternal이 제대로 작동하지 않을 수 있습니다.

         다행히도, null을 전달하면 카운트가 사용되므로 문제를 회피할 수 있습니다.
        */
        IBinder token = null;

        try {
            // 패키지 이름과 사용자 ID를 사용하여 외부 ContentProvider를 가져옴
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name);
            // 제공된 ContentProvider가 없으면 오류 로그를 남기고 메서드를 종료
            if (provider == null) {
                LOGGER.e("provider is null %s %d", name, userId);
                return;
            }
            // 가져온 ContentProvider의 바인더가 살아 있는지 확인, pingBinder()가 false이면 죽은 것으로 간주
            if (!provider.asBinder().pingBinder()) {
                LOGGER.e("provider is dead %s %d", name, userId);

                // 만약 retry가 true라면 Shizuku 앱을 강제 종료하고 다시 시도
                if (retry) {
                    // For unknown reason, sometimes this could happens
                    // Kill Shizuku app and try again could work
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId); // 패키지 강제 종료
                    LOGGER.e("kill %s in user %d and try again", packageName, userId);
                    // 1초 대기 후 다시 시도
                    Thread.sleep(1000);
                    // 다시 시도할 때 retry는 false로 설정
                    sendBinderToUserApp(binder, packageName, userId, false);
                }
                return;
            }

            // retry가 false로 설정된 경우 성공했음을 알리는 로그를 출력
            if (!retry) {
                LOGGER.e("retry works");
            }

            // 추가로 전달할 데이터를 Bundle에 담음. 바인더 객체도 함께 전달
            Bundle extra = new Bundle();
            extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new BinderContainer(binder));

            // ContentProvider를 통해 "sendBinder" 호출을 수행하고 응답을 받음
            Bundle reply = IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
            // 응답이 null이 아니면 바인더 전송이 성공했음을 알리는 로그 출력
            if (reply != null) {
                LOGGER.i("send binder to user app %s in user %d", packageName, userId);
            } else {
                // 실패한 경우 경고 로그를 남김
                LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId);
            }
        } catch (Throwable tr) {
            // 바인더 전송 중 오류 발생 시 로그를 기록
            LOGGER.e(tr, "failed send binder to user app %s in user %d", packageName, userId);
        } finally {
            // provider가 null이 아니면 ContentProvider 연결을 해제
            if (provider != null) {
                try {
                    // 연결된 ContentProvider를 해제
                    ActivityManagerApis.removeContentProviderExternal(name, token);
                } catch (Throwable tr) {
                    // 해제 중 오류가 발생하면 경고 로그를 남김
                    LOGGER.w(tr, "removeContentProviderExternal");
                }
            }
        }
    }

    // ------ Sui only ------

    @Override
    public void dispatchPackageChanged(Intent intent) throws RemoteException {
        // 이 메서드는 Sui(특정 환경에서만 실행되는 기능)를 위한 메서드이며, 현재 아무 작업도 하지 않음
    }

    @Override
    public boolean isHidden(int uid) throws RemoteException {
        // 이 메서드는 Sui 전용이며, 항상 false를 반환 (숨겨진 상태가 아님을 나타냄)
        return false;
    }
}
