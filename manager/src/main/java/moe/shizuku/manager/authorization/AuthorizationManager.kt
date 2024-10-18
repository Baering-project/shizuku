package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.shizuku.server.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import java.util.*

/**
 * AuthorizationManager는 Shizuku의 권한 부여 및 철회를 관리하는 객체입니다.
 * 이 객체는 권한 관련 기능들을 제공하고, Shizuku 서버와의 상호작용을 통해 권한을 제어합니다.
 */
object AuthorizationManager {

    // 권한 부여와 거부에 대한 플래그 상수 정의
    private const val FLAG_ALLOWED = 1 shl 1  // 권한 부여 플래그 (비트 쉬프트 연산으로 2진수로 처리됨)
    private const val FLAG_DENIED = 1 shl 2   // 권한 거부 플래그
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED  // 권한 관련 플래그를 하나로 묶음

    /**
     * 주어진 사용자 ID에 대한 설치된 애플리케이션 목록을 가져오는 함수
     * 이 함수는 Shizuku 서비스의 트랜잭션을 통해 서버에서 애플리케이션 목록을 가져옵니다.
     */
    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()  // Parcel 객체 생성 (바인더 통신을 위한 데이터 저장소)
        val reply = Parcel.obtain() // 응답을 받을 Parcel 객체 생성
        return try {
            // Shizuku 서비스의 인터페이스 토큰을 작성
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)  // 사용자 ID를 Parcel에 씀
            try {
                // Shizuku의 바인더 트랜잭션을 호출하여 서버에서 애플리케이션 목록을 가져옴
                Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                // 트랜잭션 호출 중 에러 발생 시 예외를 던짐
                throw RuntimeException(e)
            }
            reply.readException()  // 응답에서 예외가 발생했는지 확인
            // ParcelableListSlice 형태로 응답에서 읽어와서 List<PackageInfo>로 변환
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
        } finally {
            // Parcel 객체를 재활용
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * 설치된 패키지 정보를 가져오는 함수.
     * Shizuku 버전과 서버 패치 버전에 따라 애플리케이션 목록을 가져오는 방식이 달라집니다.
     */
    fun getPackages(): List<PackageInfo> {
        val packages: MutableList<PackageInfo> = ArrayList() // 결과를 저장할 리스트 생성

        // Shizuku 버전이 11보다 낮거나 특정 조건일 때 구버전 방식으로 패키지 목록을 가져옴
        if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
            val allPackages: MutableList<PackageInfo> = ArrayList()
            // ShizukuSystemApis를 사용해 각 사용자에 대해 설치된 패키지를 가져옴
            for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                try {
                    allPackages.addAll(ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id))
                } catch (e: Throwable) {
                    LOGGER.w(e, "getInstalledPackages") // 에러 로그 출력
                }
            }

            // 필터링: 해당 패키지가 현재 애플리케이션이 아니고, Shizuku V3 클라이언트 지원하는지 확인
            for (pi in allPackages) {
                if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                if (pi.applicationInfo?.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") != true) continue
                if (pi.requestedPermissions?.contains(Manifest.permission.API_V23) != true) continue

                packages.add(pi)
            }
        } else {
            // 최신 Shizuku 버전에서는 새로운 방식으로 패키지 목록을 가져옴
            packages.addAll(getApplications(-1)) // 모든 사용자의 패키지를 가져옴
        }
        return packages // 패키지 목록 반환
    }

    /**
     * 주어진 패키지에 대한 권한이 부여되었는지 확인하는 함수
     * Shizuku 버전에 따라 권한 확인 방법이 달라집니다.
     */
    fun granted(packageName: String, uid: Int): Boolean {
        return if (Shizuku.isPreV11()) {
            // 구버전에서는 시스템 API를 통해 권한 부여 여부 확인
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
        } else {
            // 최신 버전에서는 UID 플래그를 확인하여 권한이 부여되었는지 확인
            (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
        }
    }

    /**
     * 주어진 패키지에 권한을 부여하는 함수
     * Shizuku 버전에 따라 권한 부여 방법이 달라집니다.
     */
    fun grant(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            // 구버전에서는 시스템 API를 사용해 권한을 부여
            ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
        } else {
            // 최신 버전에서는 UID 플래그를 업데이트하여 권한 부여
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
        }
    }

    /**
     * 주어진 패키지의 권한을 철회하는 함수
     * Shizuku 버전에 따라 권한 철회 방법이 달라집니다.
     */
    fun revoke(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            // 구버전에서는 시스템 API를 사용해 권한을 철회
            ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
        } else {
            // 최신 버전에서는 UID 플래그를 업데이트하여 권한 철회
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
        }
    }
}
