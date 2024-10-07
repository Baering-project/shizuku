package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager         // 네트워크 서비스 검색(Nsd)를 위한 Android API 사용
import android.net.nsd.NsdServiceInfo     // 서비스 정보 저장을 위한 클래스
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi    // 특정 API 레벨 이상에서만 사용하도록 제한하는 어노테이션
import androidx.lifecycle.MutableLiveData // LiveData를 사용하여 데이터를 관찰 가능하게 함
import java.io.IOException
import java.net.InetSocketAddress         // IP 주소와 포트 번호를 하나의 객체로 제공
import java.net.NetworkInterface          // 네트워크 인터페이스 정보 제공
import java.net.ServerSocket              // 서버 소켓을 사용하여 포트의 가용성을 확인


// AdbMdns 소스는 ADB (Android Debug Bridge) 서비스를 mDNS (Multicast DNS)를 통해 자동으로 검색하고,
// 검색된 서비스의 포트 정보를 처리하는 기능을 구현한 코드입니다. mDNS는 네트워크 상의 서비스나 기기(여기서는 ADB 서비스)를 탐색하는 데 사용됩니다.
@RequiresApi(Build.VERSION_CODES.R) // 이 클래스는 Android 11 (API 레벨 30) 이상에서만 동작
class AdbMdns(
    context: Context, // 애플리케이션의 컨텍스트 전달
    private val serviceType: String,       // ADB 서비스를 탐색하기 위한 서비스 타입 (예: "_adb-tls-connect._tcp")
    private val port: MutableLiveData<Int> // 탐색된 포트를 LiveData로 저장하여 다른 컴포넌트에서 관찰 가능
) {

    private var registered = false            // 서비스 검색이 등록되었는지 여부를 나타내는 플래그
    private var running = false               // 서비스 검색이 실행 중인지 여부를 나타내는 플래그
    private var serviceName: String? = null   // 발견된 서비스의 이름을 저장하는 변수
    private val listener: DiscoveryListener   // 서비스 검색 결과를 처리하는 리스너
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java) // NsdManager 인스턴스를 가져옴

    // ADB mDNS 서비스 검색을 시작하는 함수
    fun start() {
        if (running) return  // 이미 실행 중이라면 아무 것도 하지 않음
        running = true       // 실행 상태로 설정
        if (!registered) {
            // 서비스 검색 시작
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    // ADB mDNS 서비스 검색을 중단하는 함수
    fun stop() {
        if (!running) return  // 실행 중이 아니면 아무 것도 하지 않음
        running = false       // 실행 상태 해제
        if (registered) {
            // 서비스 검색 중단
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    // 서비스 검색이 시작될 때 호출되는 함수
    private fun onDiscoveryStart() {
        registered = true  // 검색이 등록되었음을 나타냄
    }

    // 서비스 검색이 중단될 때 호출되는 함수
    private fun onDiscoveryStop() {
        registered = false  // 검색 등록 해제
    }

    // 새로운 서비스가 발견되었을 때 호출되는 함수
    private fun onServiceFound(info: NsdServiceInfo) {
        // 발견된 서비스 정보를 기반으로 서비스 해결(Resolve)을 시도
        nsdManager.resolveService(info, ResolveListener(this))
    }

    // 서비스가 손실되었을 때 호출되는 함수
    private fun onServiceLost(info: NsdServiceInfo) {
        // 손실된 서비스가 이전에 발견된 서비스와 동일한지 확인 후, 포트 값을 -1로 설정
        if (info.serviceName == serviceName) port.postValue(-1)
    }

    // 서비스가 성공적으로 해결되었을 때 호출되는 함수
    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        // 서비스가 여전히 실행 중이고, 네트워크 인터페이스에서 호스트가 발견되며, 해당 포트가 사용 가능한지 확인
        if (running && NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { resolvedService.host.hostAddress == it.hostAddress }
                }
            && isPortAvailable(resolvedService.port) // 포트 가용성 확인
        ) {
            // 서비스 이름 저장 및 포트 값 업데이트
            serviceName = resolvedService.serviceName
            port.postValue(resolvedService.port)
        }
    }

    // 포트가 사용 가능한지 확인하는 함수
    private fun isPortAvailable(port: Int) = try {
        // 주어진 포트가 사용 가능한지 확인하기 위해 소켓을 열고 닫음
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false // 포트가 사용 중이면 false 반환
        }
    } catch (e: IOException) {
        true // 포트를 열 수 없으면 포트가 사용 중이므로 true 반환
    }

    // 서비스 검색 리스너: 서비스 검색 상태와 결과를 처리하는 내부 클래스
    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        // 서비스 검색이 시작되면 호출
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType") // 로그 출력

            adbMdns.onDiscoveryStart() // 서비스 검색 시작 처리
        }

        // 서비스 검색이 실패하면 호출
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode") // 로그 출력
        }

        // 서비스 검색이 중단되면 호출
        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType") // 로그 출력

            adbMdns.onDiscoveryStop() // 서비스 검색 중단 처리
        }

        // 서비스 검색 중단이 실패하면 호출
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode") // 로그 출력
        }

        // 새로운 서비스가 발견되면 호출
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}") // 로그 출력

            adbMdns.onServiceFound(serviceInfo) // 새로운 서비스 발견 처리
        }

        // 서비스가 손실되면 호출
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}") // 로그 출력

            adbMdns.onServiceLost(serviceInfo) // 서비스 손실 처리
        }
    }

    // 서비스 해결 리스너: 서비스 해결 결과를 처리하는 내부 클래스
    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        // 서비스 해결 실패 시 호출
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        // 서비스 해결 성공 시 호출
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo) // 서비스 해결 처리
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp" // TLS 연결 서비스 타입 정의
        const val TLS_PAIRING = "_adb-tls-pairing._tcp" // TLS 페어링 서비스 타입 정의
        const val TAG = "AdbMdns" // 로그 태그 정의
    }

    init {
        // DiscoveryListener 초기화
        listener = DiscoveryListener(this)
    }
}
