package moe.shizuku.manager

import android.os.Bundle
import androidx.core.os.bundleOf
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException


// ShizukuManagerProvider는 Shizuku 서비스와 클라이언트 앱 사이의 상호작용을 관리하는 중개자로서, 바인더를 통해 사용자 서비스를 연결하고,
// 클라이언트가 필요한 시스템 권한이나 기능을 사용할 수 있도록 지원하는 역할을 합니다.
// Shizuku 서비스와 관련된 Android의 Content Provider로서, Shizuku 기능을 사용하는 앱과 시스템 사이에서 바인더(Binder) 객체를 전달하고 관리하는 역할


// ShizukuManagerProvider 클래스 선언. ShizukuProvider 클래스를 상속받음
class ShizukuManagerProvider : ShizukuProvider() {

    // 동반 객체 선언 (companion object) - 상수 정의를 위해 사용
    companion object {
        // 바인더 데이터에 사용하는 추가 정보 키를 정의
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        // 메서드 이름 상수 - 사용자 서비스를 전송할 때 사용
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    }

    // onCreate 메서드는 Provider가 생성될 때 호출됨
    override fun onCreate(): Boolean {
        // Sui(SuperUser Interface) 초기화를 비활성화 - 자동 초기화 방지
        disableAutomaticSuiInitialization()
        return super.onCreate() // 부모 클래스의 onCreate 호출
    }

    // call 메서드는 외부에서 Provider를 호출할 때 수행되는 작업을 정의
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // extras가 null이면 아무 작업도 하지 않고 null 반환
        if (extras == null) return null

        // 호출된 메서드가 사용자 서비스 전송 메서드인 경우
        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                // extras의 classLoader를 BinderContainer의 로더로 설정 (객체 직렬화 및 역직렬화 처리)
                extras.classLoader = BinderContainer::class.java.classLoader

                // extras에서 사용자 서비스 토큰을 가져옴. 없으면 null 반환
                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                // extras에서 Binder 객체를 가져옴. 없으면 null 반환
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                // 비동기 작업을 위한 CountDownLatch 생성 (1회만 카운트다운)
                val countDownLatch = CountDownLatch(1)
                // 반환할 데이터를 담을 빈 Bundle 객체 생성
                var reply: Bundle? = Bundle()

                // 바인더 수신 리스너 구현
                val listener = object : Shizuku.OnBinderReceivedListener {

                    // 바인더가 수신되었을 때 실행되는 메서드
                    override fun onBinderReceived() {
                        try {
                            // Shizuku에 사용자 서비스를 바인더로 첨부 (토큰 포함)
                            Shizuku.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token
                            ))
                            // reply에 바인더를 담아 반환 준비
                            reply!!.putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder()))
                        } catch (e: Throwable) {
                            // 오류가 발생하면 로그를 남기고 reply를 null로 설정
                            LOGGER.e(e, "attachUserService $token")
                            reply = null
                        }

                        // 리스너 제거 (다시 리스너를 받을 필요가 없으므로)
                        Shizuku.removeBinderReceivedListener(this)

                        // 카운트다운을 줄여 대기 상태 해제
                        countDownLatch.countDown()
                    }
                }

                // Shizuku에 리스너를 추가해 바인더 수신을 기다림
                Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

                // 카운트다운이 5초 내에 끝나는지 기다림
                return try {
                    countDownLatch.await(5, TimeUnit.SECONDS) // 5초 대기
                    reply                                            // 성공 시 reply 반환
                } catch (e: TimeoutException) {
                    // 5초 내에 바인더가 수신되지 않으면 오류 로그 출력 후 null 반환
                    LOGGER.e(e, "Binder not received in 5s")
                    null
                }
            } catch (e: Throwable) {
                // 전반적인 오류 처리 및 로그 남기기
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            // 메서드가 "sendUserService"가 아닌 경우 부모 클래스의 call 메서드 호출
            super.call(method, arg, extras)
        }
    }
}
