package moe.shizuku.manager.home

import android.content.pm.PackageManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku

// HomeViewModel은 ViewModel을 상속하여 홈 화면의 데이터 관리를 담당함
class HomeViewModel : ViewModel() {

    // 서비스 상태를 저장하는 MutableLiveData 객체, _serviceStatus는 내부에서만 수정 가능
    private val _serviceStatus = MutableLiveData<Resource<ServiceStatus>>()

    // 외부에서는 MutableLiveData가 아닌 LiveData로 접근할 수 있게 해서 불변성을 유지
    val serviceStatus = _serviceStatus as LiveData<Resource<ServiceStatus>>

    // Shizuku 서비스에서 현재 상태를 가져오는 메서드
    private fun load(): ServiceStatus {
        // Shizuku 서비스에 바인딩되어 있는지 확인 (바인더가 살아 있는지 확인)
        if (!Shizuku.pingBinder()) {
            // 바인더가 살아 있지 않으면 기본 빈 ServiceStatus 객체 반환
            return ServiceStatus()
        }

        // Shizuku 서버의 UID를 가져옴
        val uid = Shizuku.getUid()
        // Shizuku API 버전을 가져옴
        val apiVersion = Shizuku.getVersion()
        // 서버 패치 버전을 가져오고 음수 값이면 0으로 처리
        val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
        // Shizuku API 버전이 6 이상일 경우 SELinux 컨텍스트를 가져옴, 오류가 발생하면 null
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                // 오류 발생 시 로그 출력
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null
        // 원격으로 런타임 권한을 부여할 수 있는지 테스트, 해당 권한이 있으면 true, 없으면 false
        val permissionTest =
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED

        // Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
        // Run a random remote transaction here, report no permission as not running
        // 이전 버전의 Shizuku 서버는 제거 후에도 종료되지 않음, 권한이 없으면 서버가 실행 중이 아닌 것으로 처리
        // Shizuku API의 원격 권한을 검사, API 버전 23을 사용하는지 확인
        ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, BuildConfig.APPLICATION_ID, 0)
        // 서버 상태를 나타내는 ServiceStatus 객체 반환
        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest)
    }

    // 서버 상태를 다시 로드하는 함수
    fun reload() {
        // 코루틴을 통해 비동기로 서버 상태를 로드 (IO 스레드에서 실행)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 서버 상태를 로드하고 LiveData에 성공 상태로 업데이트
                val status = load()
                _serviceStatus.postValue(Resource.success(status))
            } catch (e: CancellationException) {
                // 취소된 경우 아무 작업도 하지 않음
            } catch (e: Throwable) {
                // 오류가 발생하면 오류 상태로 LiveData를 업데이트하고 빈 ServiceStatus 반환
                _serviceStatus.postValue(Resource.error(e, ServiceStatus()))
            }
        }
    }
}
