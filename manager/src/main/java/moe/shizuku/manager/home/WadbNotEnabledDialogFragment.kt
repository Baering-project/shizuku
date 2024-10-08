package moe.shizuku.manager.home

import android.app.Dialog  // Dialog 객체를 사용하기 위한 import
import android.os.Bundle   // 상태 저장을 위한 번들을 사용하기 위한 import
import androidx.fragment.app.DialogFragment  // Fragment 기반의 Dialog를 만들기 위한 import
import androidx.fragment.app.FragmentManager // FragmentManager를 사용하여 Fragment를 관리하기 위한 import
import com.google.android.material.dialog.MaterialAlertDialogBuilder // Material Design 스타일의 Dialog를 만들기 위한 import
import moe.shizuku.manager.R // 리소스 파일을 접근하기 위한 import (예: 문자열, 레이아웃 등)

// WadbNotEnabledDialogFragment 클래스는 DialogFragment를 상속받아 사용자의 ADB 설정이 비활성화된 경우 알림을 띄우는 다이얼로그를 정의
class WadbNotEnabledDialogFragment :DialogFragment() {

    // Fragment가 생성될 때 다이얼로그를 만들기 위한 함수 (DialogFragment의 필수 함수 중 하나)
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // 현재 Context를 가져옴
        val context = requireContext()
        // MaterialAlertDialogBuilder를 사용하여 Material Design 스타일의 다이얼로그를 빌드
        return MaterialAlertDialogBuilder(context)
                // 다이얼로그에 표시할 메시지 설정 (ADB 비활성화 관련 메시지)
                .setMessage(R.string.dialog_wireless_adb_not_enabled)
                // OK 버튼을 추가, 클릭 시 아무 동작도 하지 않음 (null 처리)
                .setPositiveButton(android.R.string.ok, null)
                // 다이얼로그 객체를 생성
                .create()
    }

    // 다이얼로그를 화면에 보여주는 함수
    fun show(fragmentManager: FragmentManager) {
        // FragmentManager의 상태가 저장되어 있으면 다이얼로그를 표시하지 않음
        if (fragmentManager.isStateSaved) return
        // 다이얼로그를 화면에 표시
        show(fragmentManager, javaClass.simpleName)
    }
}
