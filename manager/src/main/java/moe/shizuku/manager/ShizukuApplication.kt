package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell                // 루트 권한을 다루는 Shell 라이브러리 사용
import moe.shizuku.manager.ktx.logd                 // 로그 출력 관련 확장 함수
import org.lsposed.hiddenapibypass.HiddenApiBypass  // API 차단을 우회하는 라이브러리
import rikka.core.util.BuildUtils.atLeast30         // API 30 (Android 11) 이상인지 확인하는 함수
import rikka.material.app.LocaleDelegate            // 앱의 지역(언어) 설정을 관리하는 클래스

// ShizukuApplication 인스턴스를 전역 변수로 선언하여 어디서나 접근할 수 있도록 함
lateinit var application: ShizukuApplication

// Android의 Application 클래스를 상속하고 있어 앱의 전역 상태와 초기화를 관리.
class ShizukuApplication : Application() {

    // 동반 객체 (companion object)는 클래스의 정적 필드나 메서드 역할을 함
    companion object {

        init {
            // ShizukuApplication이 초기화될 때 호출됨
            logd("ShizukuApplication", "init")   // 로그로 초기화 메시지를 출력

            // Shell 기본 설정을 구성, 오류 메시지를 리다이렉트하도록 플래그 설정
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))

            // API 레벨이 28 (Android 9) 이상이면, 히든 API 우회 설정을 적용
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            // Android 11 (API 30) 이상이면 "adb" 라이브러리를 로드
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    // 앱의 초기화를 처리하는 init 함수, 지역 설정과 야간 모드를 설정
    private fun init(context: Context?) {
        ShizukuSettings.initialize(context)                                   // ShizukuSettings 초기화
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()            // 기본 지역 설정을 ShizukuSettings에서 가져옴
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode()) // 기본 야간 모드 설정을 가져옴
    }

    // 앱이 처음 시작될 때 실행됨
    override fun onCreate() {
        super.onCreate()    // 상위 클래스의 onCreate() 호출
        application = this  // 전역 변수 application에 현재 인스턴스를 할당
        init(this)   // 앱의 초기화 작업을 수행
    }

}
