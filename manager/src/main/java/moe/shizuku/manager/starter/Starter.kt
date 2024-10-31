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


    //20241031 치영 - AAB빌드 시 librish.so인식 안되는 현상때문에 ABI지원되는 목록에서 librish.so 복제하여 신규 디렉토리에 붙여넣는 로직 추가. 이후 복제한 librish.so로 연동
    //20241031 치영 - initializeLibraries메소드와 extractSoFile는 기존 shizuku원본 소스에 없었으며 , 우리 프로젝트에 맞게 로직을 수정하고 추가한거임 (Rishconfig.java 에 절대경로를 넣었음 참고)
    // `initializeLibraries` 메서드는 `Starter` 객체가 초기화될 때 `librish.so`를 직접 로드하기 위한 static 메서드입니다.

    @JvmStatic// static 메서드 선언
    fun initializeLibraries(context: Context) { //initializeLibraries static메소드는 MainActivity에서 호출하였음. oncreate에서 앱 최초 실행 시 바로 초기화해서 복제함.
        try {
            // `context.filesDir` 경로에 `librish.so` 파일을 생성하고 저장하기 위한 파일 객체를 만듭니다.
            //  context.filesDir = /data/user/0/com.baering.auto_click_java/files
            val soFile = File(context.filesDir, "librish.so")

            // `soFile`이 존재하지 않는 경우에만 `extractSoFile`을 호출하여 복사 작업을 수행합니다.
            if (!soFile.exists()) {
                extractSoFile(context, soFile) // 네이티브 라이브러리 복사를 수행하는 함수 호출
            }

            // `System.load`를 사용하여 `soFile`의 절대 경로에서 `librish.so` 파일을 로드합니다.
            System.load(soFile.absolutePath)
            // 성공적으로 로드되면 로그를 출력합니다.
            loge("Library librish.so loaded successfully.")
        } catch (e: Exception) {
            // 파일 로드 중 오류가 발생하면 오류 메시지를 로그에 출력하고 예외를 던집니다.
            loge("Failed to load librish.so: ${e.message}")
            throw e
        }
    }

    // 이 메서드는 APK 파일에서 네이티브 라이브러리 `librish.so`를 추출하여 `destination` 위치에 저장.
    private fun extractSoFile(context: Context, destination: File) {
        // 지원되는 ABI 목록에 따라 "lib/ABI명/librish.so" 경로를 설정하여 모든 아키텍처에 맞는 네이티브 라이브러리 경로를 만듭니다.
        val supportedAbis = Build.SUPPORTED_ABIS.map { "lib/$it/librish.so" }

        // APK 파일 경로 목록을 가져옵니다. `splitSourceDirs`는 AAB에서 여러 분할된 APK 파일을 지원하기 위해 포함됩니다.
        val apkPaths = listOf(context.applicationInfo.sourceDir) +
                (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList()) // Split APK 경로 포함

        // 라이브러리(librish.so)를 찾았는지 여부를 저장하는 변수입니다.
        var fileFound = false

        // `apkPaths` 목록을 순회하며 각 APK 파일 경로에서 `librish.so` 파일을 찾습니다.
        apkPaths.forEach { apkPath ->
            val apkFile = File(apkPath) // `apkPath`를 파일 객체로 만듭니다.

            // 파일이 존재하지 않으면 다음 경로로 넘어갑니다.
            if (!apkFile.exists()) return@forEach

            val apk = ZipFile(apkFile)  // Zip 형식으로 APK 파일을 엽니다.
            val entries = apk.entries() // APK 파일 내 모든 항목을 가져옵니다.

            // `entries` 목록을 순회하면서 네이티브 라이브러리 경로가 `supportedAbis`에 있는지 확인합니다.
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement() ?: break  // 다음 항목이 null인 경우 순회를 중단합니다.

                // `entry.name`이 지원되는 ABI 경로 중 하나와 일치할 때 복사 작업을 시작합니다.
                if (entry.name in supportedAbis) {
                    // 네이티브 라이브러리 파일을 읽어서 바이트 배열로 가져옵니다.
                    val buf = ByteArray(entry.size.toInt())

                    // `inputStream`으로 APK 내 라이브러리 파일을 읽어 `destination` 파일로 복사합니다.
                    apk.getInputStream(entry).use { input ->
                        FileOutputStream(destination).use { output ->
                            input.copyTo(output) // 입력 스트림의 데이터를 출력 스트림으로 복사합니다.
                        }
                    }

                    // 성공적으로 복사되었음을 로그로 출력하고, `fileFound`를 true로 설정합니다.
                    loge("Copy successful to ${destination.absolutePath}")
                    fileFound = true
                    return // 복사 성공 시 함수 종료
                }
            }
        }

        // 라이브러리를 찾지 못한 경우 오류 메시지를 로그에 출력하고 예외를 던집니다.
        if (!fileFound) {
            loge("Copy failed: .so 파일을 찾지 못함")
            throw FileNotFoundException("librish.so 파일을 찾을 수 없음")
        }
    }








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

    // starter 파일을 APK 파일에서 복사하는 함수 (APK버전임 아래 AAB버전 주석처리해놓았음)
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


    // AAB 버전
    // starter 파일을 APK 파일에서 복사하는 함수 **중요** libshozuku.so 파일 복사
    /*
    private fun copyStarter(context: Context, out: File): String { // :String은 함수의 반환타입
        // 매개변수 out = /storage/emulated/0/Android/data/com.baering.auto_click_java/starter
        // 아래 로직을 보면 libshizuku.so파일을 찾아 복제하여 out경로에 복사한다. 파일명은 starter로 복제한다.

        // 지원되는 ABI 리스트를 가져와서 각 아키텍처에 맞는 'libshizuku.so' 파일 경로로 변환
        //Build.SUPPORTED_ABIS 는 현재 디바이스에서 지원하는 ABI 목록을 배열 형태로 제공합니다.
        val supportedAbis = Build.SUPPORTED_ABIS.map { "lib/$it/libshizuku.so" } //$it: map 함수의 람다 내부에서 사용되는 변수 $it위치에 순회 중인 ABI 값이 들어감.

        // supportedAbis = [lib/arm64-v8a/libshizuku.so, lib/armeabi-v7a/libshizuku.so, lib/armeabi/libshizuku.so]
        loge("복사 가능한 경로 목록: $supportedAbis")

        // 앱의 APK 경로와 분할 APK 경로들을 리스트로 구성 (AAB의 경우 다수의 split APK 존재 가능)
        // apkPaths = "/data/app/~~Yayurd61k2iITO9YsBXP7A==/com.baering.auto_click_java-vwoTfkIez9_53TtDJhYUag==/split_config.arm64_v8a.apk"
        val apkPaths = listOf(context.applicationInfo.sourceDir) +
                (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList()) // Split APK 경로 가져오기

        // .so 파일을 찾았는지 추적하는 변수
        var fileFound = false

        // APK 경로 리스트를 순회하여 각 APK 내에서 라이브러리 파일을 검색
        apkPaths.forEach { apkPath ->
            val apkFile = File(apkPath)           // 현재 APK 경로를 File 객체로 생성
            if (!apkFile.exists()) return@forEach // 파일이 존재하지 않으면 다음 APK 경로로 이동

            // APK 파일을 Zip 파일로 열어 내부의 파일 구조를 탐색
            val apk = ZipFile(apkFile)
            val entries = apk.entries() // Zip 파일의 모든 엔트리 (파일들)을 가져옴

            // Zip 파일 내의 엔트리들을 순회하며 대상 파일을 찾음
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement() ?: break                            // 다음 엔트리를 가져오고 없으면 중단
                loge("Checking entry in APK path [$apkPath]: ${entry.name}")  // 각 entry와 APK 경로 로그 추가

                // 엔트리 이름이 지원되는 ABI 경로 목록에 존재하는지 확인
                if (entry.name in supportedAbis) {
                    // 파일 내용을 버퍼에 읽어들임
                    val buf = ByteArray(entry.size.toInt())
                    val dis = DataInputStream(apk.getInputStream(entry)) // 엔트리의 InputStream 생성
                    dis.readFully(buf)                                   // 버퍼에 파일 전체를 읽어들임

                    // 버퍼 내용을 출력 파일에 복사
                    FileUtils.copy(ByteArrayInputStream(buf), FileOutputStream(out))

                    // 복사 성공 로그를 출력하고 복사 완료 플래그를 true로 설정
                    loge("Copy successful from APK path [$apkPath] to ${out.absolutePath}, entry: ${entry.name}, size: ${buf.size} bytes")
                    fileFound = true         // 파일 찾음 상태를 true로 설정
                    return out.absolutePath  // 복사된 파일의 경로를 반환
                }
            }
        }

        // 만약 파일을 찾지 못했을 경우, 예외를 발생시키며 실패 로그 출력
        if (!fileFound) {
            loge("Copy failed: .so 파일을 찾지 못함")
            throw FileNotFoundException(".so 파일을 찾을 수 없음")
        }
        val libPath = out.parent  // 복사된 파일의 상위 디렉터리 경로를 얻음
        System.setProperty("shizuku.library.path", libPath)  // 상위 경로를 'shizuku.library.path' 시스템 속성에 설정
        loge("SHIZUKU_LIBRARY_PATH 설정: $libPath")

        return out.absolutePath   // 최종적으로 복사된 파일 경로를 반환
    }
     */

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
