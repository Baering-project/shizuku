package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.core.ktx.unsafeLazy
import java.net.ConnectException

/*
* 무선 디버깅을 위한 ADB 페어링을 처리하는 포그라운드 서비스(사용자가 ADB 페어링 작업을 하는 동안 계속 알림을 통해 상태를 확인)입니다.
*  Android 11 이상 기기에서만 동작하며, mDNS(Multicast DNS)를 통해 페어링 가능한 ADB 장치를 검색하고,
*  페어링 코드를 입력받아 ADB 연결을 시도합니다. (알림을 통해 페어링 코드를 입력받을 수 있으며, 입력된 코드를 통해 ADB 연결을 시도)
*  서비스는 포그라운드 알림(검색 중, 페어링 성공/실패)을 통해 사용자에게 상태를 알리고, 페어링 성공 여부를 알림으로 제공합니다.
* */

// Android 11(R) 이상의 기기에서 ADB 페어링 기능을 제공하는 서비스입니다.
// 이 서비스는 무선 디버깅 페어링을 처리하고, 포그라운드에서 실행되며, 알림을 통해 상태를 사용자에게 보여줍니다.
@TargetApi(Build.VERSION_CODES.R)     // Android R 이상에서만 사용 가능하다는 의미
class AdbPairingService : Service() { // Android의 백그라운드에서 실행되는 서비스 클래스 정의

    companion object {

        const val notificationChannel = "adb_pairing" // 알림 채널 ID, 알림을 만들 때 사용되는 채널 식별자

        private const val tag = "AdbPairingService"   // 로그 태그, 디버깅용 로그 메시지에 사용됨

        private const val notificationId = 1          // 알림 ID, 특정 알림을 식별하는 데 사용됨
        private const val replyRequestId = 1          // 응답 요청 ID, 페어링 코드 입력과 관련된 요청을 식별하는 데 사용
        private const val stopRequestId = 2           // 서비스 정지 요청 ID
        private const val retryRequestId = 3          // 서비스 재시도 요청 ID
        private const val startAction = "start"       // 서비스 시작을 나타내는 액션
        private const val stopAction = "stop"         // 서비스 중지를 나타내는 액션
        private const val replyAction = "reply"       // 페어링 코드 입력 응답을 나타내는 액션
        private const val remoteInputResultKey = "paring_code" // 페어링 코드의 결과 키
        private const val portKey = "paring_code"     // 페어링 시 사용하는 포트 번호를 저장하는 키

        // 서비스를 시작하기 위한 인텐트 생성
        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        // 서비스를 중지하기 위한 인텐트 생성
        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        // 페어링 코드 입력을 위한 인텐트 생성
        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
        }
    }

    // 메인 스레드에서 실행되는 핸들러 생성 - 이 핸들러는 메인(UI) 스레드에서 동작하게 설정되어 있어, UI와 관련된 작업을 안전하게 처리할 수 있습니다.
    private val handler = Handler(Looper.getMainLooper())
    // Android의 LiveData 중 하나로, 데이터가 변경되면 이를 관찰하는 Observer들에게 자동으로 알림을 주는 역할을 함-
    private val port = MutableLiveData<Int>() // ADB 페어링에 사용될 포트를 저장

    //adbMdns**는 ADB mDNS(Multicast DNS) 검색 객체로, ADB 장치들이 연결을 위해 사용할 수 있는 네트워크 서비스를 검색하는 데 사용
    private var adbMdns: AdbMdns? = null      // ADB mDNS 검색 객체

    // 포트가 변경될 때 호출되는 옵저버
    /*
    * Observer**는 LiveData를 구독하여 포트 번호가 변경될 때 호출됩니다.
    * 즉, port라는 MutableLiveData가 변할 때마다 이 Observer가 알림을 받고 포트 변경을 처리합니다.
    */
    private val observer = Observer<Int> { port ->
        Log.i(tag, "Pairing service port: $port") //포트가 변경되면 로그로 포트 번호를 기록

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent

        // 사용자가 페어링 코드 입력을 완료하기 전에 서비스가 종료될 수 있으므로 페어링 코드 입력을 위한 알림을 생성
        val notification = createInputNotification(port) // 페어링 코드 입력 알림 생성

        //NotificationManager를 통해 해당 알림을 표시
        getSystemService(NotificationManager::class.java).notify(notificationId, notification) // 알림 표시
    }

    private var started = false  // 서비스가 시작되었는지 여부

    override fun onCreate() {
        super.onCreate()

        // 알림 채널 생성
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH // 중요도 설정 (상단 알림으로 표시)
            ).apply {
                setSound(null, null) // 소리 없음
                setShowBadge(false)                     // 배지 사용 안 함
                setAllowBubbles(false)                  // 버블 알림 사용 안 함
            })
    }

    // 서비스 시작 시 호출됨
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {  // 시작 액션일 경우
                onStart()     // 페어링 검색 시작
            }
            replyAction -> {  // 응답 액션일 경우
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1) // 포트 번호 가져오기
                if (port != -1) {
                    onInput(code.toString(), port) // 입력된 페어링 코드 처리
                } else {
                    onStart() // 다시 검색 시작
                }
            }
            stopAction -> { // 정지 액션일 경우
                stopForeground(STOP_FOREGROUND_REMOVE) // 포그라운드 서비스 정지
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(notificationId, notification) // 포그라운드 서비스 시작
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException) {
                    getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    // 페어링 서비스 검색 시작
    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, port).apply { start() } // mDNS로 ADB 검색 시작

        if (Looper.myLooper() == Looper.getMainLooper()) {
            port.observeForever(observer) // 메인 스레드에서 포트 옵저버 등록
        } else {
            handler.post { port.observeForever(observer) } // 핸들러로 포트 옵저버 등록
        }
    }

    // 페어링 서비스 검색 중지
    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop() // mDNS 검색 중지

        if (Looper.myLooper() == Looper.getMainLooper()) {
            port.removeObserver(observer) // 메인 스레드에서 포트 옵저버 제거
        } else {
            handler.post { port.removeObserver(observer) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch() // 서비스 종료 시 페어링 검색 중지
    }

    // ADB 페어링 검색 시작 알림 생성
    private fun onStart(): Notification {
        startSearch() // 페어링 검색 시작
        return searchingNotification // 검색 중 알림 반환
    }

    // 페어링 코드 입력 시 호출되는 함수
    private fun onInput(code: String, port: Int): Notification {
        GlobalScope.launch(Dispatchers.IO) { // 백그라운드 스레드에서 실행
            val host = "127.0.0.1" // ADB 서버 호스트 설정

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku") // ADB 키 생성
            } catch (e: Throwable) {
                e.printStackTrace()
                return@launch
            }

            // ADB 페어링 클라이언트 시작
            AdbPairingClient(host, port, code, key).runCatching {
                start()
            }.onFailure {
                handleResult(false, it) // 실패 시 처리
            }.onSuccess {
                handleResult(it, null) // 성공 시 처리
            }
        }

        return workingNotification // 작업 중 알림 반환
    }

    // 페어링 결과 처리
    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            Log.i(tag, "Pair succeed") // 성공 로그 출력

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = getString(R.string.notification_adb_pairing_succeed_text)

            stopSearch() // 검색 중지
        } else {
            title = getString(R.string.notification_adb_pairing_failed_title)

            text = when (exception) {
                is ConnectException -> {
                    getString(R.string.cannot_connect_port) // 포트 연결 실패
                }
                is AdbInvalidPairingCodeException -> {
                    getString(R.string.paring_code_is_wrong) // 페어링 코드 오류
                }
                is AdbKeyException -> {
                    getString(R.string.adb_error_key_store)  // ADB 키 저장소 오류
                }
                else -> {
                    exception?.let { Log.getStackTraceString(it) } // 기타 오류
                }
            }

            if (exception != null) {
                Log.w(tag, "Pair failed", exception) // 오류 로그 출력
            } else {
                Log.w(tag, "Pair failed")
            }
        }

        // 페어링 결과 알림 표시
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(title)
                .setContentText(text)
                /*.apply {
                    if (!success) {
                        addAction(retryNotificationAction)
                    }
                }*/
                .build()
        )
    }

    // 서비스 중지 알림 액션 생성
    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    // 재시도 알림 액션 생성
    private val retryNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_retry),
            pendingIntent
        )
            .build()
    }

    // 페어링 코드 입력 알림 액션 생성
    private val replyNotificationAction by unsafeLazy {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.dialog_adb_pairing_paring_code)) // 입력 필드 레이블 설정
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(remoteInput) // 알림에 페어링 코드 입력 필드 추가
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        // Ensure pending intent is created
        val action = replyNotificationAction

        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return action
    }

    // 검색 중 알림 생성
    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction) // 중지 액션 추가
            .build()
    }

    // 페어링 코드 입력 알림 생성
    private fun createInputNotification(port: Int): Notification {
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .addAction(replyNotificationAction(port)) // 페어링 코드 입력 액션 추가
            .build()
    }

    // 작업 중 알림 생성
    private val workingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    // 바인드된 서비스가 아니므로 onBind는 null을 반환
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
