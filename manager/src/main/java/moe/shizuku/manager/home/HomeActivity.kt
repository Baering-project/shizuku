package moe.shizuku.manager.home

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity                // 앱바(Activity 상단의 제목 바)를 제공하는 기본 액티비티 클래스
import moe.shizuku.manager.databinding.AboutDialogBinding    // AboutDialog 레이아웃 바인딩 클래스
import moe.shizuku.manager.databinding.HomeActivityBinding   // HomeActivity 레이아웃 바인딩 클래스
import moe.shizuku.manager.ktx.toHtml                        // HTML 문자열을 처리하는 확장 함수
import moe.shizuku.manager.management.appsViewModel          // 앱 관리와 관련된 ViewModel을 제공하는 함수
import moe.shizuku.manager.settings.SettingsActivity         // 설정 액티비티 클래스
import moe.shizuku.manager.starter.Starter                   // Shizuku의 초기화 관련 작업을 처리하는 클래스
import moe.shizuku.manager.utils.AppIconCache                // 앱 아이콘 캐시 관리 클래스
import rikka.core.ktx.unsafeLazy                             // 안전하지 않은 Lazy 초기화 방식 (나중에 필요할 때 초기화)
import rikka.lifecycle.Status                                // 라이프사이클 상태를 관리하는 유틸리티
import rikka.lifecycle.viewModels                            // ViewModel을 사용하는 유틸리티 함수
import rikka.recyclerview.addEdgeSpacing                     // RecyclerView에 테두리 간격 추가하는 함수
import rikka.recyclerview.addItemSpacing                     // RecyclerView 항목 간 간격 추가하는 함수
import rikka.recyclerview.fixEdgeEffect                      // RecyclerView의 에지 효과 수정 함수
import rikka.shizuku.Shizuku                                 // Shizuku의 주요 API를 제공하는 클래스

// HomeActivity: 앱의 홈 화면을 담당하는 기본 클래스
abstract class HomeActivity : AppBarActivity() {

    // Shizuku 서비스가 시작되었을 때 호출되는 리스너
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus() // 서버 상태를 확인
        appsModel.load()    // 앱 목록을 불러옴
    }

    // Shizuku 서비스가 중지되었을 때 호출되는 리스너
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkServerStatus()   // 서버 상태를 다시 확인
    }

    // HomeViewModel 인스턴스를 lazy 방식으로 초기화
    private val homeModel by viewModels { HomeViewModel() }
    // 앱 관련 ViewModel을 불러옴
    private val appsModel by appsViewModel()
    // HomeAdapter를 lazy 방식으로 초기화
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel) }

    // 액티비티가 처음 생성될 때 호출
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Starter 관련 파일을 작성하는 메서드 호출
        writeStarterFiles()

        // HomeActivity 레이아웃을 inflate하여 설정
        val binding = HomeActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 서비스 상태 관찰 (LiveData가 업데이트될 때마다 실행)
        homeModel.serviceStatus.observe(this) {
            if (it.status == Status.SUCCESS) {
                // 서비스 상태가 성공적으로 확인되었을 때
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                adapter.updateData() // 데이터 업데이트
                // Shizuku의 마지막 실행 모드를 저장 (루트 또는 ADB)
                ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
            }
        }

        // 앱의 권한 상태를 관찰하여 데이터 업데이트
        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                adapter.updateData()  // grantedCount에 변화가 있을 때마다 데이터를 업데이트
            }
        }

        // RecyclerView 설정 (앱 목록을 보여줌)
        val recyclerView = binding.list
        recyclerView.adapter = adapter // 어댑터 설정
        recyclerView.fixEdgeEffect()   // 에지 효과 수정
        recyclerView.addItemSpacing(top = 4f, bottom = 4f, unit = TypedValue.COMPLEX_UNIT_DIP) // 항목 간 간격 설정
        recyclerView.addEdgeSpacing(top = 4f, bottom = 4f, left = 16f, right = 16f, unit = TypedValue.COMPLEX_UNIT_DIP) // 테두리 간격 설정

        // Shizuku 서비스 상태 관련 리스너 추가
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    // 액티비티가 화면에 표시될 때 호출
    override fun onResume() {
        super.onResume()
        checkServerStatus()  // 서버 상태를 다시 확인
    }

    // 서버 상태를 확인하는 메서드 (Shizuku 서비스 상태 확인)
    private fun checkServerStatus() {
        homeModel.reload()
    }

    // 액티비티가 종료될 때 호출
    override fun onDestroy() {
        super.onDestroy()
        // 리스너 제거 (메모리 누수 방지)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    // 메뉴를 생성하는 메서드 (액티비티 상단에 메뉴 추가)
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    // 메뉴 아이템이 선택되었을 때 처리하는 메서드
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> { // 'About' 메뉴 클릭 시
                val binding = AboutDialogBinding.inflate(LayoutInflater.from(this), null, false) // AboutDialog 레이아웃 inflate
                binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()                                       // GitHub 링크 클릭 가능하게 설정
                binding.sourceCode.text = getString(
                    R.string.about_view_source_code,
                    "<b><a href=\"https://github.com/RikkaApps/Shizuku\">GitHub</a></b>"
                ).toHtml() // GitHub 링크를 HTML로 표시
                binding.icon.setImageBitmap(
                    AppIconCache.getOrLoadBitmap(
                        this,
                        applicationInfo,
                        Process.myUid() / 100000, // UID를 사용하여 아이콘을 가져옴
                        resources.getDimensionPixelOffset(R.dimen.default_app_icon_size)
                    )
                )
                binding.versionName.text = packageManager.getPackageInfo(packageName, 0).versionName  // 버전 정보 표시
                MaterialAlertDialogBuilder(this) // 'About' 다이얼로그 생성 및 표시
                    .setView(binding.root)
                    .show()
                true
            }
            R.id.action_stop -> { // 'Stop' 메뉴 클릭 시
                if (!Shizuku.pingBinder()) { // Shizuku 서비스가 실행 중인지 확인
                    return true
                }
                // 서비스 종료 확인 다이얼로그 표시
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.dialog_stop_message)
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        try {
                            Shizuku.exit() // Shizuku 서비스 종료
                        } catch (e: Throwable) {
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            R.id.action_settings -> { // 'Settings' 메뉴 클릭 시 설정 액티비티로 이동
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item) // 기타 메뉴 항목 처리
        }
    }

    // Starter 관련 파일을 작성하는 메서드
    private fun writeStarterFiles() {
        // 백그라운드 작업으로 파일 작성
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Starter.writeSdcardFiles(applicationContext) // SD 카드에 Starter 파일을 작성
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    // 파일 작성 실패 시 오류 다이얼로그 표시
                    MaterialAlertDialogBuilder(this@HomeActivity)
                        .setTitle("Cannot write files")
                        .setMessage(Log.getStackTraceString(e)) // 오류 메시지 표시
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                        .apply {
                            // 다이얼로그가 표시될 때 모노스페이스 글꼴로 설정
                            setOnShowListener {
                                this.findViewById<TextView>(android.R.id.message)!!.apply {
                                    typeface = Typeface.MONOSPACE
                                    setTextIsSelectable(true)
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }
}
