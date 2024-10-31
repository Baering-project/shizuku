package rikka.rish;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

public class RishConfig {

    // 로그를 위해 태그를 설정
    private static final String TAG = "RISHConfig";

    // 상수로 선언된 트랜잭션 코드, 각각의 기능을 나타냄
    static final int TRANSACTION_createHost = 0;     // 호스트 생성 트랜잭션 코드
    static final int TRANSACTION_setWindowSize = 1;  // 창 크기 설정 트랜잭션 코드
    static final int TRANSACTION_getExitCode = 2;    // 종료 코드 가져오기 트랜잭션 코드

    // 바인더 객체, 인터페이스 토큰, 트랜잭션 코드 시작 번호, 라이브러리 경로 선언
    private static IBinder binder;            // 바인더 객체, 다른 프로세스와의 통신에 사용
    private static String interfaceToken;     // 인터페이스 식별을 위한 토큰
    private static int transactionCodeStart;  // 트랜잭션 코드의 시작 번호
    private static String libraryPath;        // 라이브러리 파일 경로

    // 바인더 객체를 반환하는 메서드
    static IBinder getBinder() {
        return binder;
    }

    // 인터페이스 토큰을 반환하는 메서드
    static String getInterfaceToken() {
        return interfaceToken;
    }

    // 트랜잭션 코드를 반환하는 메서드, 트랜잭션 코드의 시작 번호와 코드 값을 더하여 반환
    static int getTransactionCode(int code) {
        return transactionCodeStart + code;
    }

    // 외부에서 라이브러리 경로를 설정할 수 있게 하는 메서드 (ShizukuService에서 경로 설정)
    public static void setLibraryPath(String path) {
        libraryPath = path;
    }

    // 라이브러리를 로드하는 메서드, 라이브러리 경로가 null일 때는 "rish" 기본 라이브러리를 로드 **중요** 여기서 librish.so파일 지정 경로를 로드함!! AAB빌드일경우 경로를 못찾으므로 아래 소스를 이용할것
    // APK 빌드인경우는 잘 인식하는듯
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private static void loadLibrary() {
        if (libraryPath == null) {
            System.loadLibrary("rish");
        } else {
            System.load(libraryPath + "/librish.so");
        }
    }

    // AAB빌드일경우 아래 소스를 이용할것
    // AAB빌드인경우 librish.so를 위 소스로 하면 찾지를 못한다.
    // ABI별로 APK들을 전부 조회하여 librish.so파일을 복제해서 앱 내에 넣어줘야한다. 그리고 그 경로를 참고하게 해야한다.
    // 그러므로 나는 Starter.kt소스 안에 initializeLibraries메소드와 extractSoFile메소드를 추가해주었다. 그곳에 복제한 경로를 로그로 확인할 수 있다.
//    @SuppressLint("UnsafeDynamicallyLoadedCode")
//    private static void loadLibrary() {
//        if (libraryPath == null) {
//            // 기본 경로에 있는 rish 라이브러리를 로드
//            System.loadLibrary("rish");
//        } else {
//
//            // 지정된 libraryPath 경로에서 "librish.so"를 로드
//            try {
//                // 지정된 libraryPath 경로에서 "librish.so"에 실행 권한 설정
//                //씨발 권한을 주니까 되잖아;;
//                Os.chmod("/복제한경로(AAB빌드는 .so파일이 APK기준이랑 달라서 못찾으므로 전체 ABI별 .so를 찾아서 새로 복제해두고 복제한 경로를 넣으세요/librish.so", 0777);
//                // 지정된 경로에서 "librish.so"를 로드
//                System.load("복제한경로/librish.so");
//            } catch (ErrnoException e) {
//                e.printStackTrace();
//                // 필요한 경우 추가 오류 처리를 여기에 작성
//            }
//        }
//    }

    // 서버 모드에서 초기화하는 메서드
    public static void init(String interfaceToken, int transactionCodeStart) {
        Log.d(TAG, "init (server) " + interfaceToken + " " + transactionCodeStart); // 초기화 로그
        RishConfig.interfaceToken = interfaceToken;                                      // 인터페이스 토큰 설정
        RishConfig.transactionCodeStart = transactionCodeStart;                          // 트랜잭션 코드 시작 번호 설정
        loadLibrary();  // 라이브러리 로드
    }

    // 클라이언트 모드에서 초기화하는 메서드
    public static void init(IBinder binder, String interfaceToken, int transactionCodeStart) {
        Log.d(TAG, "init (client) " + binder + " " + interfaceToken + " " + transactionCodeStart); // 초기화 로그
        RishConfig.binder = binder; // 바인더 객체 설정
        RishConfig.interfaceToken = interfaceToken; // 인터페이스 토큰 설정
        RishConfig.transactionCodeStart = transactionCodeStart; // 트랜잭션 코드 시작 번호 설정
        loadLibrary(); // 라이브러리 로드
    }
}
