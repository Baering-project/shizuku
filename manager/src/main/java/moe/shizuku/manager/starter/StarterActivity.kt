package moe.shizuku.manager.starter

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.application
import moe.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

// NotRootedException: 루트 권한을 얻지 못했을 때 발생하는 예외를 나타내는 클래스
private class NotRootedException : Exception()

// StarterActivity: Shizuku 서비스를 시작하는 액티비티
// ***********치영메모 : 이거 무선 디버깅으로 시작 - 시작 버튼 누르면 나오는 실행기 엑티비티인듯 ********************
class StarterActivity : AppBarActivity() {

    // ViewModel 인스턴스를 생성하여 이 액티비티에서 사용할 데이터를 관리
    // EXTRA_IS_ROOT 플래그를 통해 루트 모드인지 ADB 모드인지 결정
    private val viewModel by viewModels {
        ViewModel(
            this,                                            // 현재 액티비티의 컨텍스트를 전달
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),  // 인텐트를 통해 루트 권한 여부를 받음
            intent.getStringExtra(EXTRA_HOST),                      // 인텐트에서 ADB 호스트 정보를 받음
            intent.getIntExtra(EXTRA_PORT, 0)             // 인텐트에서 ADB 포트 정보를 받음
        )
    }

    // 액티비티가 생성될 때 호출되는 onCreate 메서드
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 액션바에 닫기 버튼을 설정
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        // 레이아웃을 바인딩하고 액티비티의 UI로 설정
        val binding = StarterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ViewModel에서 출력되는 데이터를 관찰하고 UI를 업데이트
        viewModel.output.observe(this) {
            val output = it.data!!.trim() // 데이터의 양쪽 공백 제거 후 가져옴
            if (output.endsWith("info: shizuku_starter exit with 0")) {
                // Shizuku 서비스가 정상적으로 종료되었을 때
                viewModel.appendOutput("")
                viewModel.appendOutput("Waiting for service...")

                // Shizuku 서비스가 시작되면 리스너를 통해 처리
                Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        Shizuku.removeBinderReceivedListener(this) // 리스너를 제거
                        viewModel.appendOutput("Service started, this window will be automatically closed in 3 seconds")

                        window?.decorView?.postDelayed({
                            if (!isFinishing) finish() // 3초 후에 액티비티를 종료
                        }, 3000)
                    }
                })
            } else if (it.status == Status.ERROR) {
                // 오류가 발생했을 때 처리
                var message = 0
                when (it.error) {
                    is AdbKeyException -> {
                        message = R.string.adb_error_key_store     // ADB 키 저장소 관련 오류
                    }
                    is NotRootedException -> {
                        message = R.string.start_with_root_failed  // 루트 권한 획득 실패
                    }
                    is ConnectException -> {
                        message = R.string.cannot_connect_port     // ADB 연결 실패
                    }
                    is SSLProtocolException -> {
                        message = R.string.adb_pair_required       // ADB 페어링 실패
                    }
                }

                if (message != 0) {
                    // 오류 메시지를 사용자에게 다이얼로그로 표시
                    MaterialAlertDialogBuilder(this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            // 바인딩된 텍스트뷰에 출력된 내용을 설정
            binding.text1.text = output
        }
    }

    // Companion object: 액티비티와 관련된 상수들을 정의
    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"  // 루트 권한 여부를 나타내는 상수
        const val EXTRA_HOST = "$EXTRA.HOST"        // ADB 호스트 주소를 나타내는 상수
        const val EXTRA_PORT = "$EXTRA.PORT"        // ADB 포트를 나타내는 상수
    }
}

// ViewModel: StarterActivity에서 데이터를 관리하고, 루트 또는 ADB를 통해 서비스를 시작하는 역할
private class ViewModel(context: Context, root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {

    // 출력 메시지를 저장할 StringBuilder
    private val sb = StringBuilder()

    // LiveData로 UI에 데이터를 전달하기 위한 변수
    private val _output = MutableLiveData<Resource<StringBuilder>>()
    val output = _output as LiveData<Resource<StringBuilder>>

    // ViewModel이 생성될 때 루트 모드인지 ADB 모드인지 확인하고 적절한 메서드 호출
    init {
        try {
            if (root) {
                //Starter.writeFiles(context)
                startRoot() // 루트 권한으로 시작
            } else {
                startAdb(host!!, port) // ADB로 시작
            }
        } catch (e: Throwable) {
            postResult(e) // 오류 발생 시 처리
        }
    }

    // 출력 메시지를 추가하고 결과를 전달하는 메서드
    fun appendOutput(line: String) {
        sb.appendLine(line)
        postResult()
    }

    // 결과를 LiveData로 전달하는 메서드
    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }

    // 루트 권한으로 Shizuku 서비스를 시작하는 메서드
    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n') // 루트 권한으로 시작 중이라는 메시지 추가
        postResult()

        // 루트 권한을 요청하고, 루트 셸을 실행
        GlobalScope.launch(Dispatchers.IO) {
            if (!Shell.rootAccess()) {
                // 루트 권한이 없을 경우 처리
                Shell.getCachedShell()?.close()
                sb.append('\n').append("Can't open root shell, try again...").append('\n')

                postResult()
                if (!Shell.rootAccess()) {
                    sb.append('\n').append("Still not :(").append('\n')
                    postResult(NotRootedException()) // 루트 권한 실패 시 예외 발생
                    return@launch
                }
            }

            // 필요한 데이터 파일을 작성하고, 루트 셸에서 명령어 실행
            Starter.writeDataFiles(application)
            Shell.su(Starter.dataCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')  // 명령어 결과를 출력 메시지에 추가
                    postResult()
                }
            }).submit {
                if (it.code != 0) {
                    sb.append('\n').append("Send this to developer may help solve the problem.")
                    postResult()
                }
            }
        }
    }

    // ADB로 Shizuku 서비스를 시작하는 메서드
    private fun startAdb(host: String, port: Int) {
        sb.append("Starting with wireless adb...").append('\n').append('\n')  // ADB로 시작 중이라는 메시지 추가
        postResult()

        GlobalScope.launch(Dispatchers.IO) {
            val key = try {
                // ADB 키를 설정
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(e)) // 오류 발생 시 스택 트레이스를 출력

                postResult(AdbKeyException(e))
                return@launch
            }

            // ADB 클라이언트를 통해 Shizuku 명령어 실행
            AdbClient(host, port, key).runCatching {
                connect() // ADB 연결 시도
                shellCommand(Starter.sdcardCommand) {
                    sb.append(String(it))  // 결과를 출력 메시지에 추가
                    postResult()
                }
                close() // 연결 닫기
            }.onFailure {
                it.printStackTrace()

                sb.append('\n').append(Log.getStackTraceString(it)) // 오류가 발생하면 스택 트레이스를 추가
                postResult(it)
            }

            /* Adb on MIUI Android 11 has no permission to access Android/data.
               Before MIUI Android 12, we can temporarily use /data/user_de.
               After that, is better to implement "adb push" and push files directly to /data/local/tmp.
             */

            // MIUI Android 11에서 권한 문제 해결을 위한 처리
            if (sb.contains("/Android/data/${BuildConfig.APPLICATION_ID}/start.sh: Permission denied")) {
                sb.append('\n')
                    .appendLine("adb have no permission to access Android/data, how could this possible ?!")
                    .appendLine("try /data/user_de instead...")
                    .appendLine()
                postResult()

                // ADB 권한 문제 해결을 위한 데이터 파일 작성
                Starter.writeDataFiles(application, true)

                // 다시 ADB 명령어 실행
                AdbClient(host, port, key).runCatching {
                    connect()
                    shellCommand(Starter.dataCommand) {
                        sb.append(String(it))
                        postResult()
                    }
                    close()
                }.onFailure {
                    it.printStackTrace()

                    sb.append('\n').append(Log.getStackTraceString(it))
                    postResult(it)
                }
            }
        }
    }
}
