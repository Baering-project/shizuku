package moe.shizuku.manager.home

import android.os.Build
import moe.shizuku.manager.management.AppsViewModel  // 앱 권한 및 관리와 관련된 ViewModel
import moe.shizuku.manager.utils.EnvironmentUtils    // 기기의 환경 정보를 다루는 유틸리티 클래스
import moe.shizuku.manager.utils.UserHandleCompat    // 현재 사용자 정보를 확인하는 유틸리티
import rikka.recyclerview.IdBasedRecyclerViewAdapter // RecyclerView 어댑터를 위한 기본 클래스
import rikka.recyclerview.IndexCreatorPool           // RecyclerView에서 ViewHolder를 관리하는 유틸리티
import rikka.shizuku.Shizuku                         // Shizuku의 주요 API를 제공하는 클래스

// HomeAdapter: 홈 화면의 RecyclerView에서 항목을 표시하는 어댑터
class HomeAdapter(private val homeModel: HomeViewModel, private val appsModel: AppsViewModel) :
    IdBasedRecyclerViewAdapter(ArrayList()) {

    init {
        // 어댑터 생성 시 초기화 작업: 데이터를 업데이트하고 고유 ID가 있는 항목을 가진다는 것을 표시
        updateData()
        setHasStableIds(true)
    }

    companion object {

        // RecyclerView 항목들에 대한 고유 ID 상수 정의 (각 항목마다 고유한 ID 부여)
        private const val ID_STATUS = 0L     // 서버 상태 항목의 ID
        private const val ID_APPS = 1L       // 앱 관리 항목의 ID
        private const val ID_TERMINAL = 2L   // 터미널 항목의 ID
        private const val ID_START_ROOT = 3L // 루트로 시작하는 항목의 ID
        private const val ID_START_WADB = 4L // 무선 ADB로 시작하는 항목의 ID
        private const val ID_START_ADB = 5L  // ADB로 시작하는 항목의 ID
        private const val ID_LEARN_MORE = 6L // 추가 정보 항목의 ID
        private const val ID_ADB_PERMISSION_LIMITED = 7L // ADB 권한 제한 경고 항목의 ID
    }

    // RecyclerView에서 ViewHolder를 생성하고 관리하는 IndexCreatorPool을 반환
    override fun onCreateCreatorPool(): IndexCreatorPool {
        return IndexCreatorPool()
    }

    // 데이터 업데이트 함수: 홈 화면에 표시할 항목들을 관리
    fun updateData() {
        // homeModel에서 서버 상태를 가져오고, 앱 권한을 얻은 수(grantedCount)를 가져옴
        val status = homeModel.serviceStatus.value?.data ?: return
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        // ADB 권한 상태와 서버 실행 상태, 현재 사용자가 Primary(주) 사용자인지 확인
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0

        // RecyclerView를 초기화하고 새로운 항목들을 추가
        clear()
        // 서버 상태 ViewHolder를 추가 (고유 ID: ID_STATUS)
        addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)                       //시주쿠가 실행 중이 아닙니다 , 시주쿠가 실행 중입니다.

        // ADB 권한이 있을 때 앱 관리와 터미널 항목 추가
        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)  // 앱 관리 - 0개 앱 인증됨 화면 추가
            addItem(TerminalViewHolder.CREATOR, status, ID_TERMINAL)                      // 터미널 실행 - 터미널에서 Shizuku 사용 화면 추가
        }

        // 서버가 실행 중이고 ADB 권한이 없을 때 경고 항목 추가
        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED) //경고 화면 추가
        }

        // Primary 사용자일 경우에만 추가적인 항목들을 추가 (루트 시작, 무선 ADB, ADB 시작)
        if (isPrimaryUser) {
            val root = EnvironmentUtils.isRooted()       // 기기가 루팅되었는지 여부 확인
            val rootRestart = running && status.uid == 0 // 루트 재시작 필요 여부 확인

            // 루팅된 기기일 경우 루트 시작 항목 추가
            if (root) {
                addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)       //시작(루팅된 기기용) 화면 추가
            }

            // Android 11(R) 이상이거나 ADB TCP 포트가 열려 있을 경우 무선 ADB 항목 추가
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0) {
                addItem(StartWirelessAdbViewHolder.CREATOR, null, ID_START_WADB) //무선 디버깅으로 시작 화면 추가
            }


            // ADB 시작 항목 추가
            addItem(StartAdbViewHolder.CREATOR, null, ID_START_ADB)              //컴퓨터에 연결하여 시작 화면추가

            // 루팅되지 않은 경우에도 루트 시작 항목 추가
            if (!root) {
                addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)       //시작(루팅된 기기용) 화면 추가
            }
        }
        // 추가 정보 항목 추가
        addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE)                //Shizuku 알아보기 화면 추가
        // 데이터가 변경되었음을 RecyclerView에 알림
        notifyDataSetChanged()
    }
}
