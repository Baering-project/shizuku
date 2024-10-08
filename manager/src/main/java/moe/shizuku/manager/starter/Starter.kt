package moe.shizuku.manager.starter

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.system.ErrnoException
import android.system.Os
import moe.shizuku.manager.R
import moe.shizuku.manager.ktx.createDeviceProtectedStorageContextCompat
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.loge
import rikka.core.os.FileUtils
import java.io.*
import java.util.zip.ZipFile

// 이 코드는 Shizuku 서비스를 기기에서 실행하기 위한 Starter 객체를 구현한 것으로,
// ADB 명령을 실행할 수 있는 환경을 준비하고 스크립트를 생성하여 이를 통해 ADB 명령을 실행합니다.

// ADB 명령을 실행할 수 있는 환경을 준비하고,
// starter 파일을 복사하고 실행할 수 있도록 스크립트를 생성하는 역할을 합니다.

object Starter {

    // 내부에서 사용할 명령어들을 저장하는 배열. 인덱스 0: 내부 데이터 파일용, 인덱스 1: 외부 파일(SD카드)용.
    private var commandInternal = arrayOfNulls<String>(2)

    // 내부 데이터 디렉터리에서 사용될 명령어를 반환
    val dataCommand get() = commandInternal[0]!!

    // SD카드 또는 외부 저장소에서 사용될 명령어를 반환
    val sdcardCommand get() = commandInternal[1]!!

    // ADB 명령어를 반환. SD카드 명령어를 이용해 ADB 셸에서 실행할 명령어를 생성
    val adbCommand: String
        get() = "adb shell $sdcardCommand"

    // SD카드에 파일을 쓰는 함수. 이미 작성된 경우는 다시 작성하지 않음
    fun writeSdcardFiles(context: Context) {
        // 이미 SD카드 명령어가 있으면 로그를 찍고 함수를 종료
        if (commandInternal[1] != null) {
            logd("already written")
            return
        }

        // 사용자 잠금 상태를 확인. 잠겨 있으면 작업을 진행할 수 없음
        val um = context.getSystemService(UserManager::class.java)!!
        val unlocked = Build.VERSION.SDK_INT < 24 || um.isUserUnlocked
        if (!unlocked) {
            throw IllegalStateException("User is locked")
        }

        // 외부 파일 디렉토리 가져오기. 가져오지 못하면 예외 발생
        val filesDir = context.getExternalFilesDir(null) ?: throw IOException("getExternalFilesDir() returns null")
        // 상위 디렉터리를 가져옴. 가져오지 못하면 예외 발생
        val dir = filesDir.parentFile ?: throw IOException("$filesDir parentFile returns null")
        // starter 파일을 복사하고 스크립트를 작성하여 명령어를 생성
        val starter = copyStarter(context, File(dir, "starter"))
        val sh = writeScript(context, File(dir, "start.sh"), starter)
        // 생성한 명령어를 commandInternal에 저장
        commandInternal[1] = "sh $sh"
        logd(commandInternal[1]!!)
    }

    // 내부 데이터 디렉터리에 파일을 쓰는 함수. 이미 작성된 경우 권한 설정에 따라 재작성 가능
    fun writeDataFiles(context: Context, permission: Boolean = false) {
        // 이미 명령어가 있고 권한 설정이 필요 없으면 종료
        if (commandInternal[0] != null && !permission) {
            logd("already written")
            return
        }

        // 기기 보호 저장소의 상위 디렉터리 가져오기
        val dir = context.createDeviceProtectedStorageContextCompat().filesDir?.parentFile ?: return

        // 권한 설정이 필요한 경우 디렉터리의 권한을 변경 (0711로 설정)
        if (permission) {
            try {
                Os.chmod(dir.absolutePath, 457 /* 0711 */)
            } catch (e: ErrnoException) {
                e.printStackTrace()
            }
        }

        // starter 파일을 복사하고 스크립트를 작성하여 명령어를 생성
        try {
            val starter = copyStarter(context, File(dir, "starter"))
            val sh = writeScript(context, File(dir, "start.sh"), starter)
            // 명령어를 생성하여 commandInternal에 저장
            commandInternal[0] = "sh $sh --apk=${context.applicationInfo.sourceDir}"
            logd(commandInternal[0]!!)

            // 권한 설정이 필요한 경우 starter 및 스크립트의 권한을 0644로 변경
            if (permission) {
                try {
                    Os.chmod(starter, 420 /* 0644 */)
                } catch (e: ErrnoException) {
                    e.printStackTrace()
                }
                try {
                    Os.chmod(sh, 420 /* 0644 */)
                } catch (e: ErrnoException) {
                    e.printStackTrace()
                }
            }
        } catch (e: IOException) {
            loge("write files", e)
        }
    }

    // starter 파일을 APK 파일에서 복사하는 함수
    private fun copyStarter(context: Context, out: File): String {
        // 현재 ABI에 맞는 so 파일 경로 설정
        val so = "lib/${Build.SUPPORTED_ABIS[0]}/libshizuku.so"
        // 앱 정보 가져오기
        val ai = context.applicationInfo

        // out 파일에 데이터를 쓸 FileOutputStream 생성
        val fos = FileOutputStream(out)
        // 앱의 APK 파일을 Zip 형식으로 열음
        val apk = ZipFile(ai.sourceDir)
        // APK 파일의 엔트리들을 순회하면서 so 파일을 찾음
        val entries = apk.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement() ?: break
            // 엔트리 이름이 목표 파일이 아닌 경우 다음으로 넘어감
            if (entry.name != so) continue

            // 파일 내용을 버퍼에 읽어와서 FileOutputStream으로 복사
            val buf = ByteArray(entry.size.toInt())
            val dis = DataInputStream(apk.getInputStream(entry))
            dis.readFully(buf)
            FileUtils.copy(ByteArrayInputStream(buf), fos)
            break
        }
        return out.absolutePath
    }

    // 스크립트를 생성하는 함수
    private fun writeScript(context: Context, out: File, starter: String): String {
        // 파일이 존재하지 않으면 새로 생성
        if (!out.exists()) {
            out.createNewFile()
        }
        // 스크립트 리소스를 읽어옴
        val `is` = BufferedReader(InputStreamReader(context.resources.openRawResource(R.raw.start)))
        // 스크립트 파일에 쓸 PrintWriter 생성
        val os = PrintWriter(FileWriter(out))
        var line: String?
        // 스크립트 파일을 한 줄씩 읽어서 %%STARTER_PATH%%%를 starter 경로로 대체
        while (`is`.readLine().also { line = it } != null) {
            os.println(line!!.replace("%%%STARTER_PATH%%%", starter))
        }
        os.flush()
        os.close()
        return out.absolutePath
    }
}
