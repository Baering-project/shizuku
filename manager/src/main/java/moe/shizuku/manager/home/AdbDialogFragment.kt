package moe.shizuku.manager.home

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemProperties
import android.provider.Settings
import android.view.LayoutInflater
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.databinding.AdbDialogBinding
import moe.shizuku.manager.starter.StarterActivity
import java.net.InetAddress

//AdbDialogFragment.kt : ADB 연결 설정을 다이얼로그 형태로 제공하고, 개발자 설정을 쉽게 접근할 수 있도록 하는 소스
@RequiresApi(Build.VERSION_CODES.R) // 이 클래스는 Android 11(API 30) 이상에서만 동작
class AdbDialogFragment : DialogFragment() {  // 다이얼로그를 띄우는 프래그먼트 클래스 정의

    // View 바인딩을 사용하여 레이아웃 요소에 접근하기 위한 변수
    private lateinit var binding: AdbDialogBinding
    // ADB mdns 서비스(ADB 장치를 자동으로 검색하기 위한 클래스)를 사용하기 위한 변수
    private lateinit var adbMdns: AdbMdns
    // LiveData 객체로, ADB TCP 포트 정보를 저장하고 이를 관찰할 수 있게 함
    private val port = MutableLiveData<Int>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Context를 가져옴
        val context = requireContext()
        // 레이아웃 인플레이터를 사용하여 adb_dialog.xml을 View로 변환하고 바인딩 객체에 저장
        binding = AdbDialogBinding.inflate(LayoutInflater.from(context))
        // ADB mdns 초기화. TLS_CONNECT 방식과 포트를 인자로 전달
        adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT, port)

        // SystemProperties에서 ADB TCP 포트를 가져옴 (기본값은 -1)
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        // 포트가 기본값이면 persist 속성에서 다시 시도
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)

        // MaterialAlertDialogBuilder를 사용하여 다이얼로그를 생성
        val builder = MaterialAlertDialogBuilder(context).apply {
            // 다이얼로그 제목 설정
            setTitle(R.string.dialog_adb_discovery)
            // 다이얼로그에 사용할 레이아웃 설정
            setView(binding.root)
            // 취소 버튼 추가
            setNegativeButton(android.R.string.cancel, null)
            // 개발자 설정으로 이동하는 버튼 추가
            setPositiveButton(R.string.development_settings, null)

            // 포트가 유효한 경우 중립 버튼에 포트 번호 표시
            if (port != -1) {
                setNeutralButton("$port", null)
            }
        }

        // 다이얼로그를 생성하고 반환
        val dialog = builder.create()
        // 다이얼로그가 터치로 취소되지 않도록 설정
        dialog.setCanceledOnTouchOutside(false)
        // 다이얼로그가 표시될 때 호출될 리스너 설정
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    // 다이얼로그가 해제될 때 호출되는 메서드. ADB mdns 검색을 중단
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        adbMdns.stop() // mdns 검색 중지
    }

    // 다이얼로그가 표시될 때 호출되는 메서드
    private fun onDialogShow(dialog: AlertDialog) {
        // ADB mdns 검색 시작
        adbMdns.start()

        // 개발자 설정으로 이동하는 버튼 클릭 리스너 설정
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // 개발자 설정으로 이동하는 인텐트 생성
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            try {
                // 인텐트를 통해 개발자 설정으로 이동
                it.context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // 개발자 설정을 찾지 못했을 때 예외 처리
            }
        }

        // 중립 버튼 클릭 시 ADB 포트 확인 후 연결 시도
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            // ADB TCP 포트를 다시 가져옴
            var port = SystemProperties.getInt("service.adb.tcp.port", -1)
            if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
            // 포트가 유효한 경우 연결 시도
            startAndDismiss(port)
        }

        // 포트 LiveData 객체를 관찰. 포트 값이 설정되면 연결 시도
        port.observe(this) {
            if (it > 65535 || it < 1) return@observe // 포트 범위가 유효하지 않으면 반환
            startAndDismiss(it) // 유효한 포트일 경우 연결 시도
        }
    }

    // ADB 연결을 시도하고 다이얼로그를 닫는 메서드
    private fun startAndDismiss(port: Int) {
        // ADB 연결 호스트 설정
        val host = "127.0.0.1"
        // StarterActivity로 이동하는 인텐트 생성 및 연결 관련 정보 전달
        val intent = Intent(context, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_HOST, host)
            putExtra(StarterActivity.EXTRA_PORT, port)
        }
        // 인텐트를 통해 StarterActivity 시작
        requireContext().startActivity(intent)

        // 다이얼로그를 닫음
        dismissAllowingStateLoss()
    }

    // 이 프래그먼트를 표시하는 메서드
    fun show(fragmentManager: FragmentManager) {
        // 상태가 저장된 경우 다이얼로그를 띄우지 않음
        if (fragmentManager.isStateSaved) return
        // 다이얼로그를 띄움
        show(fragmentManager, javaClass.simpleName)
    }
}
