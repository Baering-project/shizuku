package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemProperties
import java.io.File

//ADB TCP 포트 상태와 같은 시스템 정보를 쉽게 조회할 수 있는 여러 기능을 제공
object EnvironmentUtils {

    //현재 기기가 스마트워치인지 여부를 판단하는 함수입니다.
    @JvmStatic
    fun isWatch(context: Context): Boolean {
        return (context.getSystemService(UiModeManager::class.java).currentModeType // 시스템 서비스를 통해 기기의 UI 모드를 확인
                == Configuration.UI_MODE_TYPE_WATCH)                                // 기기의 UI 모드가 **UI_MODE_TYPE_WATCH**인지 비교
        //UI 모드가 UI_MODE_TYPE_WATCH와 같다면 true, 그렇지 않다면 false를 반환
    }

    //기기가 루팅되었는지 여부를 확인하는 함수입니다.
    fun isRooted(): Boolean {
        //시스템 환경 변수 중 PATH 값을 조회 su 파일이 존재하는지 확인
        //su 파일은 루팅된 시스템에서 사용하는 슈퍼유저 실행 파일이다.
        //su 파일이 존재하면 기기가 루팅되었으므로 true, 그렇지 않으면 false를 반환
        return System.getenv("PATH")?.split(File.pathSeparatorChar)?.find { File("$it/su").exists() } != null
    }

    //ADB TCP 포트가 설정되어 있는지 확인하고 그 포트 번호를 반환하는 함수이다.
    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1) //ADB 서비스가 사용 중인 TCP 포트 번호를 가져온다.
        // -1 : 이 포트는 기본적으로 -1로 설정되어 있으며, 이는 포트가 활성화되지 않았다는 의미입니다.
        //영구 ADB 포트 설정(persist.adb.tcp.port)을 조회하여 다시 확인
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1) //최종적으로 확인된 포트 번호를 반환합니다. 포트가 설정되지 않았을 경우에는 -1이 반환
        return port
    }
}
