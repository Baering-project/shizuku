package moe.shizuku.manager;

import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.util.Locale;

import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;      // 사용자 정의 Empty SharedPreferences 구현
import moe.shizuku.manager.utils.EnvironmentUtils;                // 환경 관련 유틸리티

import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    // SharedPreferences 파일의 이름
    public static final String NAME = "settings";
    // 야간 모드 설정 키
    public static final String NIGHT_MODE = "night_mode";
    // 언어 설정 키
    public static final String LANGUAGE = "language";
    // 부팅 시 자동 시작 설정 키
    public static final String KEEP_START_ON_BOOT = "start_on_boot";

    // SharedPreferences 객체를 저장할 변수
    private static SharedPreferences sPreferences;

    // SharedPreferences 객체를 반환하는 메서드. 저장된 설정 값을 가져오는 데 사용.
    public static SharedPreferences getPreferences() {
        return sPreferences;
    }


    // 설정을 저장할 때 사용할 적절한 Context를 반환하는 메서드
    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        // Android 7.0 (API 24) 이상일 경우 기기 보호 저장소 컨텍스트를 사용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        // SharedPreferences 호출 시 에러를 처리하기 위해 ContextWrapper를 사용
        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    // 일반적인 SharedPreferences 반환
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // 사용자가 잠금 해제되기 전까지 SharedPreferences가 사용 불가능할 때, EmptySharedPreferences 반환
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    // ShizukuSettings를 초기화하는 메서드. 초기화되지 않았다면 SharedPreferences를 설정함.
    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    // 런치 메서드를 정의하는 IntDef 어노테이션
    @IntDef({
            LaunchMethod.UNKNOWN,  // 런치 방법을 모르는 경우
            LaunchMethod.ROOT,     // 루트 권한으로 실행된 경우
            LaunchMethod.ADB,      // ADB로 실행된 경우
    })
    @Retention(SOURCE)             // 어노테이션이 컴파일 시점에서만 유지됨
    public @interface LaunchMethod {
        int UNKNOWN = -1;    // 알 수 없음
        int ROOT = 0;        // 루트 모드
        int ADB = 1;         // ADB 모드
    }

    // 마지막 실행 모드를 가져오는 메서드
    @LaunchMethod
    public static int getLastLaunchMode() {
        // 기본값은 UNKNOWN, mode라는 키로 저장된 값을 가져옴
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    // 마지막 실행 모드를 저장하는 메서드
    public static void setLastLaunchMode(@LaunchMethod int method) {
        // mode 키로 실행 모드를 저장하고 즉시 적용(apply)
        getPreferences().edit().putInt("mode", method).apply();
    }

    // 현재 설정된 야간 모드 값을 반환하는 메서드
    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        // 기본값은 시스템 설정에 따름 (MODE_NIGHT_FOLLOW_SYSTEM)
        int defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        // 현재 기기가 Wear OS인 경우 야간 모드를 항상 활성화함
        if (EnvironmentUtils.isWatch(ActivityThread.currentActivityThread().getApplication())) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES;
        }
        // SharedPreferences에서 야간 모드 값을 반환, 기본값은 defValue
        return getPreferences().getInt(NIGHT_MODE, defValue);
    }

    // 현재 설정된 언어(Locale) 값을 반환하는 메서드
    public static Locale getLocale() {
        // SharedPreferences에서 언어 설정 값을 가져옴
        String tag = getPreferences().getString(LANGUAGE, null);
        // 만약 언어 설정 값이 없거나 'SYSTEM'이면 시스템 기본 언어 반환
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            //이 메서드는 현재 기기(시스템)의 기본 언어를 반환합니다. 만약 시스템 언어가 한국어로 설정되어 있으면, **한국어(Locale.KOREAN)**가 반환됩니다.
            return Locale.getDefault();
        }
        // 저장된 언어 태그를 Locale로 변환하여 반환
        return Locale.forLanguageTag(tag);
    }
}
