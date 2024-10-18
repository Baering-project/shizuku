package moe.shizuku.manager.authorization

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.ConfirmationDialogBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.core.res.resolveColor
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// RequestPermissionActivity는 사용자에게 권한 요청 대화상자를 표시하는 Activity입니다.
// Shizuku 서비스를 통해 앱에 권한을 요청하고 결과를 처리하는 과정에서 사용자에게 확인 대화상자를 띄우고, 그에 따라 권한을 승인하거나 거부하는 로직을 처리
// "시주쿠 앱이 배링앱에 접근을 허용하시겠습니까?" 권한
class RequestPermissionActivity : AppActivity() {

    // Activity에서 사용하는 Dialog 객체 선언
    private lateinit var dialog: Dialog

    // 권한 요청 결과를 처리하는 함수. UID, PID, 요청 코드와 함께 권한 승인 여부(allowed), 일회성 여부(onetime)를 설정하여 결과를 전달.
    private fun setResult(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        // 결과 데이터를 담을 Bundle 생성
        val data = Bundle()
        // 권한 허용 여부와 일회성 여부를 Bundle에 저장
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        try {
            // Shizuku API를 통해 결과를 클라이언트로 전달
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: Throwable) {
            // 결과 전송 중 에러가 발생하면 로그로 에러 기록
            LOGGER.e("dispatchPermissionConfirmationResult")
        }
    }

    // 애플리케이션의 자체 권한을 확인하는 함수
    private fun checkSelfPermission(): Boolean {
        // GRANT_RUNTIME_PERMISSIONS 권한을 체크
        val permission = Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        if (permission) return true // 권한이 있으면 true 반환

        // 권한이 없을 경우 대화상자 설정
        val icon = getDrawable(R.drawable.ic_system_icon) // 아이콘 설정
        icon?.setTint(theme.resolveColor(android.R.attr.colorAccent)) // 아이콘 색상 설정

        // 권한이 없을 경우 표시할 경고 대화상자 생성
        val dialog = MaterialAlertDialogBuilder(this)
                .setIcon(icon)
                .setTitle("Shizuku: ${getString(R.string.app_management_dialog_adb_is_limited_title)}")
                .setMessage(getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get()).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE))
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { finish() } // 대화상자가 닫히면 Activity를 종료
                .create()

        // 대화상자에 표시된 링크가 동작할 수 있도록 설정
        dialog.setOnShowListener {
            (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        }

        // 대화상자를 표시
        try {
            dialog.show()
        } catch (ignored: Throwable) {
            // 대화상자 표시 중 발생하는 에러 무시
        }
        return false // 권한이 없으면 false 반환
    }

    // Binder 객체를 기다리는 함수
    private fun waitForBinder(): Boolean {
        // 5초 동안 Binder가 수신될 때까지 대기하는 CountDownLatch 생성
        val countDownLatch = CountDownLatch(1)

        // Binder가 수신되면 호출될 리스너 설정
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                countDownLatch.countDown()  // Binder가 수신되면 카운트 감소
                Shizuku.removeBinderReceivedListener(this) // 리스너 제거
            }
        }

        // 리스너 등록
        Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

        // Binder를 기다리며 5초 동안 대기, 성공 여부 반환
        return try {
            countDownLatch.await(5, TimeUnit.SECONDS) // 5초 동안 대기
            true // 성공적으로 수신되면 true 반환
        } catch (e: TimeoutException) {
            // 5초 내에 수신되지 않으면 타임아웃 로그 출력
            LOGGER.e(e, "Binder not received in 5s")
            false // 실패 시 false 반환
        }
    }

    // Activity의 onCreate 메서드: Activity가 생성될 때 호출됨
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Binder를 대기
        if (!waitForBinder()) {
            finish() // Binder를 수신하지 못하면 Activity를 종료
            return
        }

        // Intent로부터 UID, PID, requestCode, 그리고 applicationInfo 객체를 받아옴
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        if (uid == -1 || pid == -1 || ai == null) {
            finish() // 값이 잘못되면 Activity 종료
            return
        }

        // 애플리케이션의 권한을 확인
        if (!checkSelfPermission()) {
            setResult(uid, pid, requestCode, allowed = false, onetime = true)
            return
        }

        // 애플리케이션의 레이블을 가져오거나 오류가 발생하면 패키지 이름을 사용
        val label = try {
            ai.loadLabel(packageManager)
        } catch (e: Exception) {
            ai.packageName
        }

        // 권한 요청 대화상자의 View를 바인딩하고 이벤트 리스너 설정
        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            button1.setOnClickListener {
                setResult(uid, pid, requestCode, allowed = true, onetime = false) // 허용 버튼 클릭 시 결과 설정
                dialog.dismiss()
            }
            button3.setOnClickListener {
                setResult(uid, pid, requestCode, allowed = false, onetime = true) // 거부 버튼 클릭 시 결과 설정
                dialog.dismiss()
            }
            // 대화상자의 제목을 설정 (HTML 형식으로)
            title.text = HtmlCompat.fromHtml(getString(R.string.permission_warning_template,
                    label, getString(R.string.permission_group_description)))
        }

        // MaterialAlertDialog 대화상자를 생성하고 설정
        dialog = MaterialAlertDialogBuilder(this)
                .setView(binding.root)             // 바인딩한 View를 대화상자의 내용으로 설정
                .setCancelable(false)              // 취소 불가로 설정
                .setOnDismissListener { finish() } // 대화상자가 닫히면 Activity 종료
                .create()
        dialog.setCanceledOnTouchOutside(false)    // 외부 터치로 닫히지 않도록 설정
        dialog.show()                              // 대화상자 표시
    }
}
