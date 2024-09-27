package moe.shizuku.manager.home

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemProperties
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartWirelessAdbBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import java.net.Inet4Address

// StartWirelessAdbViewHolder: 홈 화면에서 "무선 디버깅으로 시작" 항목을 나타내는 ViewHolder
class StartWirelessAdbViewHolder(binding: HomeStartWirelessAdbBinding, root: View) :
    BaseViewHolder<Any?>(root) { // BaseViewHolder를 상속받아 RecyclerView의 아이템을 관리

    companion object {
        // ViewHolder를 생성하는 Companion 객체 (CREATOR를 통해 인플레이터로부터 ViewHolder를 생성)
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            // 외부 레이아웃(HomeItemContainerBinding)과 내부 레이아웃(HomeStartWirelessAdbBinding)을 inflate하여 ViewHolder 생성
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartWirelessAdbBinding.inflate(inflater, outer.root, true)
            StartWirelessAdbViewHolder(inner, outer.root)
        }
    }

    init {
        //  '시작' 클릭 시 ADB를 실행하는 함수 호출
        binding.button1.setOnClickListener { v: View ->
            onAdbClicked(v.context)
        }

        // Android 11 이상일 경우, '페어링'버튼과 '단계별 가이드'버튼에 각각 무선 ADB와 페어링 기능 연결
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.button3.setOnClickListener { v: View ->
                CustomTabsHelper.launchUrlOrCopy(v.context, Helps.ADB_ANDROID11.get()) // 버튼3: ADB 가이드 문서 열기
            }
            binding.button2.setOnClickListener { v: View ->
                onPairClicked(v.context)                                               // 버튼2: ADB 페어링 기능 실행
            }
            // 텍스트1을 링크 처리하여 ADB 설명 추가 (LinkMovementMethod를 사용하여 링크 클릭 가능)
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text = context.getString(R.string.home_wireless_adb_description)         //Android 11 또는 그 이상 버전에서는 컴퓨터에 연결하지 않아도 ... 문구
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        // Android 11 미만일 경우 버튼2, 버튼3을 숨기고 다른 설명 추가
        } else {
            binding.text1.text = context.getString(R.string.home_wireless_adb_description_pre_11)  //Android 11 이전에서는 무선 디버깅을 활성화하기 위해 컴퓨터에 연결해야 합니다. 문구
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            binding.button2.isVisible = false // 버튼2 숨김 (페어링)
            binding.button3.isVisible = false // 버튼3 숨김 (단계별 가이드)
        }
    }

    // RecyclerView가 데이터를 갱신할 때 호출됨 (여기서는 별도 작업 없음)
    override fun onBind(payloads: MutableList<Any>) {
        super.onBind(payloads)
    }

    // 버튼1('시작' 버튼)이 클릭될 때 호출되는 ADB 실행 함수
    private fun onAdbClicked(context: Context) {
        // Android 11 이상이면 페어링 다이얼로그 표시
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            return
        }

        // ADB TCP 포트를 확인하고, 열려 있으면 StarterActivity를 시작하여 ADB 연결
        val port = EnvironmentUtils.getAdbTcpPort()
        if (port > 0) {
            val host = "127.0.0.1" // ADB 서버 호스트 설정
            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, host)
                putExtra(StarterActivity.EXTRA_PORT, port)
            }
            context.startActivity(intent) // StarterActivity 실행
        } else {
            WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager) // ADB가 활성화되지 않았을 경우 다이얼로그 표시
        }
    }

    // '페어링 버튼' (버튼2)이 클릭될 때 호출되는 ADB 페어링 함수 (Android 11 이상)
    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        if ((context.display?.displayId ?: -1) > 0) {
            // Running in a multi-display environment (e.g., Windows Subsystem for Android),
            // pairing dialog can be displayed simultaneously with Shizuku.
            // Input from notification is harder to use under this situation.
            // 멀티 디스플레이 환경에서 ADB 페어링 다이얼로그를 표시
            AdbPairDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        } else {
            // 그렇지 않으면 AdbPairingTutorialActivity를 실행하여 페어링 절차 시작
            context.startActivity(Intent(context, AdbPairingTutorialActivity::class.java))
        }
    }
}
