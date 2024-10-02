package moe.shizuku.manager.adb

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AdbPairingTutorialActivityBinding
import rikka.compatibility.DeviceCompatibility

@RequiresApi(Build.VERSION_CODES.R) // 이 클래스는 Android 11(R) 이상에서만 작동
class AdbPairingTutorialActivity : AppBarActivity() { // AppBarActivity를 상속받아 UI를 제공하는 액티비티 클래스

    private lateinit var binding: AdbPairingTutorialActivityBinding // ViewBinding을 사용하여 레이아웃 요소에 접근

    private var notificationEnabled: Boolean = false // 알림이 활성화되어 있는지 여부를 저장하는 변수

    override fun onCreate(savedInstanceState: Bundle?) { // 액티비티 생성 시 호출되는 메서드
        super.onCreate(savedInstanceState)
        val context = this                               // 현재 컨텍스트를 변수에 저장

        binding = AdbPairingTutorialActivityBinding.inflate(layoutInflater) // 레이아웃을 inflate하고 ViewBinding 객체 생성
        setContentView(binding.root)                                        // 레이아웃을 액티비티에 설정
        supportActionBar?.setDisplayHomeAsUpEnabled(true)                   // 상단 바에 뒤로가기 버튼 표시

        notificationEnabled = isNotificationEnabled()                       // 알림 활성화 여부 확인

        if (notificationEnabled) { // 알림이 활성화되어 있으면
            startPairingService()  // 페어링 서비스 시작
        }

        binding.apply {// binding 객체를 통해 UI 요소에 접근
            syncNotificationEnabled()              // UI에서 알림 상태 동기화

            if (DeviceCompatibility.isMiui()) {    // MIUI 기기인지 확인
                miui.isVisible = true              // MIUI 관련 UI 요소 표시
            }

            developerOptions.setOnClickListener {// 개발자 옵션 버튼 클릭 시 실행
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)           // 개발자 옵션 설정 화면으로 이동하는 Intent 생성
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK // 플래그 설정
                try {
                    context.startActivity(intent)        // 설정 화면 시작
                } catch (e: ActivityNotFoundException) { // 예외 처리
                }
            }

            notificationOptions.setOnClickListener { // 알림 설정 버튼 클릭 시 실행
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)   // 알림 설정 화면으로 이동하는 Intent 생성
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) // 현재 앱의 패키지 이름을 인텐트에 추가
                try {
                    context.startActivity(intent)        // 설정 화면 시작
                } catch (e: ActivityNotFoundException) { // 예외 처리
                }
            }
        }
    }

    private fun syncNotificationEnabled() {                  // 알림 상태를 UI에 반영하는 함수
        binding.apply {
            step1.isVisible = notificationEnabled             // 알림이 활성화되면 step1 표시
            step2.isVisible = notificationEnabled             // 알림이 활성화되면 step2 표시
            step3.isVisible = notificationEnabled             // 알림이 활성화되면 step3 표시
            network.isVisible = notificationEnabled           // 알림이 활성화되면 network 표시
            notification.isVisible = notificationEnabled      // 알림이 활성화되면 notification 표시
            notificationDisabled.isGone = notificationEnabled // 알림이 활성화되면 '알림 비활성화됨' 메시지 숨김
        }
    }

    // 알림이 활성화되어 있는지 확인하는 함수
    private fun isNotificationEnabled(): Boolean {
        val context = this                         // 현재 컨텍스트를 변수에 저장

        val nm = context.getSystemService(NotificationManager::class.java) // NotificationManager 객체 가져오기
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel) // 알림 채널 가져오기
        return nm.areNotificationsEnabled() && // 알림이 활성화되어 있고
                (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE) // 채널이 없거나 중요도가 None이 아니면 true 반환
    }

    // 액티비티가 화면에 다시 나타날 때 호출되는 메서드
    override fun onResume() {
        super.onResume()

        val newNotificationEnabled = isNotificationEnabled()  // 새로운 알림 활성화 상태 확인
        if (newNotificationEnabled != notificationEnabled) {  // 이전 상태와 다르면
            notificationEnabled = newNotificationEnabled      // 상태 업데이트
            syncNotificationEnabled()                         // UI와 동기화

            if (newNotificationEnabled) {                     // 알림이 활성화되었으면
                startPairingService()                         // 페어링 서비스 시작
            }
        }
    }

    // 페어링 서비스를 시작하는 함수
    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(this) // 페어링 서비스 시작 인텐트 생성
        try {
            startForegroundService(intent)  // 포그라운드 서비스 시작
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S    // 안드로이드 12 이상에서
                && e is ForegroundServiceStartNotAllowedException // 포그라운드 서비스가 허용되지 않는 예외가 발생했을 때
            ) {
                val mode = getSystemService(AppOpsManager::class.java) // AppOpsManager로 권한 상태 확인
                    .noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                if (mode == AppOpsManager.MODE_ERRORED) { // 권한이 거부된 경우
                    Toast.makeText(this, "OP_START_FOREGROUND is denied. What are you doing?", Toast.LENGTH_LONG).show() // 에러 메시지 표시
                }
                startService(intent) // 포그라운드 서비스가 실패했으므로 일반 서비스로 시작
            }
        }
    }
}
