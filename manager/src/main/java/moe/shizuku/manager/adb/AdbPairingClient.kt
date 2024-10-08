package moe.shizuku.manager.adb

// 필요한 Android API, 네트워크 및 암호화 관련 라이브러리 임포트
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

// 디버그 로그용 상수 TAG 정의
private const val TAG = "AdbPairClient"

// 페어링에 필요한 상수 값들 정의 (버전, 크기, 레이블 등)
private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192 // 최대 Peer 정보 크기
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2 // 최대 페이로드 크기

// ADB에서 사용할 레이블과 키 크기 상수 정의
private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

// 페어링 패킷의 헤더 크기 상수 정의
private const val kPairingPacketHeaderSize = 6

// PeerInfo 클래스: 페어링할 때 주고받을 정보 (타입과 데이터)
private class PeerInfo(
        val type: Byte,     // Peer 정보의 타입 (RSA 키, 장치 GUID 등)
        data: ByteArray) {  // Peer 정보의 실제 데이터 (최대 크기 제한 있음)

    // Peer 정보 데이터 저장 (최대 크기 제한 적용)
    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        // 데이터 크기를 맞추고 저장
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    // PeerInfo 타입 정의 (RSA 공개키, 장치 GUID 등)
    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),  // RSA 공개키 타입
        ADB_DEVICE_GUID(0.toByte()),  // 장치 GUID 타입
    }

    // ByteBuffer에 PeerInfo를 기록하는 함수
    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)      // 타입을 기록
            put(data) // 데이터를 기록
        }

        Log.d(TAG, "write PeerInfo ${toStringShort()}")  // 기록된 PeerInfo를 로그로 출력
    }

    // PeerInfo의 전체 정보를 문자열로 반환
    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    // 간략한 정보만 문자열로 반환
    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

    companion object {

        // ByteBuffer로부터 PeerInfo를 읽어들이는 함수
        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()      // PeerInfo의 타입 읽기
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)             // PeerInfo의 데이터 읽기
            return PeerInfo(type, data)  // 읽은 데이터를 새로운 PeerInfo로 반환
        }
    }
}

// PairingPacketHeader 클래스: 페어링할 때 사용하는 패킷 헤더
private class PairingPacketHeader(
        val version: Byte,  // 패킷 버전
        val type: Byte,     // 패킷 타입 (메시지 또는 Peer 정보)
        val payload: Int) { // 페이로드 크기 (전달할 데이터의 크기)

    // 패킷 타입 정의 (SPAKE2 메시지, Peer 정보)
    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),  // SPAKE2 메시지
        PEER_INFO(1.toByte())    // Peer 정보
    }

    // ByteBuffer에 패킷 헤더를 기록하는 함수
    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)         // 버전을 기록
            put(type)            // 타입을 기록
            putInt(payload) // 페이로드 크기 기록
        }

        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}") // 기록된 헤더 정보를 로그로 출력
    }

    // 패킷 헤더의 전체 정보를 문자열로 반환
    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    // 간략한 정보를 문자열로 반환
    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {
        // ByteBuffer로부터 패킷 헤더를 읽어들이는 함수
        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get() // 버전 읽기
            val type = buffer.get()    // 타입 읽기
            val payload = buffer.int   // 페이로드 크기 읽기

            // 지원하는 버전 범위 확인
            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=${version})")
                return null
            }
            // 패킷 타입 확인 (지원하는 타입인지)
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=${type}")
                return null
            }

            // 페이로드 크기 확인 (안전한 크기인지)
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=${payload})")
                return null
            }

            // 패킷 헤더 생성 후 반환
            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

// PairingContext 클래스: 페어링 과정에서 암호화를 관리
private class PairingContext private constructor(private val nativePtr: Long) {

    // 생성자에서 메시지를 가져옴 (native 코드에서 호출)
    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)  // native 메시지 생성
    }

    // 상대방 메시지를 사용해 암호화를 초기화하는 함수
    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    // 데이터를 암호화하는 함수
    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    // 데이터를 복호화하는 함수
    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    // PairingContext를 파괴하는 함수 (자원 해제)
    fun destroy() = nativeDestroy(nativePtr)

    // native 함수 선언 (JNI 사용)
    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        // PairingContext 생성 (암호화를 위한 native 코드 호출)
        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }
        // native 생성자 선언
        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

// ADB 페어링 클라이언트 클래스: ADB와 TLS 기반 페어링을 처리
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(private val host: String, private val port: Int, private val pairCode: String, private val key: AdbKey) : Closeable {

    // 클라이언트 상태를 나타내는 열거형
    private enum class State {
        Ready,                 // 준비 상태
        ExchangingMsgs,        // 메시지 교환 중
        ExchangingPeerInfo,    // Peer 정보 교환 중
        Stopped                // 중지 상태
    }

    private lateinit var socket: Socket                 // 네트워크 소켓
    private lateinit var inputStream: DataInputStream   // 입력 스트림
    private lateinit var outputStream: DataOutputStream // 출력 스트림

    // Peer 정보를 저장 (RSA 공개키 사용)
    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private lateinit var pairingContext: PairingContext // 페어링 컨텍스트
    private var state: State = State.Ready              // 초기 상태는 Ready

    // 페어링 시작 함수
    fun start(): Boolean {
        setupTlsConnection()            // TLS 연결 설정

        state = State.ExchangingMsgs    // 메시지 교환 상태로 변경

        // 메시지 교환 성공 여부 확인
        if (!doExchangeMsgs()) {
            state = State.Stopped
            return false
        }

        state = State.ExchangingPeerInfo   // Peer 정보 교환 상태로 변경

        // Peer 정보 교환 성공 여부 확인
        if (!doExchangePeerInfo()) {
            state = State.Stopped
            return false
        }

        state = State.Stopped  // 상태를 Stopped로 설정하고 성공 반환
        return true
    }

    // TLS 연결 설정
    private fun setupTlsConnection() {
        socket = Socket(host, port)  // 소켓 생성
        socket.tcpNoDelay = true     // TCP NoDelay 설정

        val sslContext = key.sslContext  // SSL 컨텍스트 가져오기
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()       // SSL 핸드셰이크 시작
        Log.d(TAG, "Handshake succeeded.") // 핸드셰이크 성공 로그

        // 입력 및 출력 스트림 설정
        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        // 페어링 코드와 TLS 키 데이터를 결합하여 PairingContext 생성
        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, kExportedKeyLabel, null, kExportedKeySize)
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        val pairingContext = PairingContext.create(passwordBytes)  // PairingContext 생성
        checkNotNull(pairingContext) { "Unable to create PairingContext." } // 실패 시 오류 발생
        this.pairingContext = pairingContext
    }

    // 패킷 헤더 생성 함수
    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    // 패킷 헤더를 읽어오는 함수
    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize) // 패킷 헤더 크기만큼 바이트 배열 생성
        inputStream.readFully(bytes)                    // 입력 스트림에서 읽어옴
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN) // ByteBuffer로 감싸서 읽기
        return PairingPacketHeader.readFrom(buffer)     // 헤더 읽기
    }

    // 패킷 헤더와 페이로드를 쓰는 함수
    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)  // 헤더 크기만큼 ByteBuffer 생성
        header.writeTo(buffer) // 헤더 기록

        outputStream.write(buffer.array())  // 기록된 헤더를 출력 스트림으로 전송
        outputStream.write(payload)         // 페이로드 전송
        Log.d(TAG, "write payload, size=${payload.size}") // 페이로드 크기를 로그로 출력
    }

    // 메시지 교환 과정 처리 함수
    private fun doExchangeMsgs(): Boolean {
        val msg = pairingContext.msg // PairingContext의 메시지 가져오기
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size) // 메시지에 대한 헤더 생성
        writeHeader(ourHeader, msg)                                             // 헤더와 메시지 전송

        val theirHeader = readHeader() ?: return false // 상대방 헤더 읽기, 실패 시 false 반환
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false // 메시지 타입 확인

        val theirMessage = ByteArray(theirHeader.payload) // 상대방의 메시지 크기만큼 바이트 배열 생성
        inputStream.readFully(theirMessage) // 메시지 읽어옴

        // 상대방 메시지로 암호화 초기화, 실패 시 false 반환
        if (!pairingContext.initCipher(theirMessage)) return false
        return true
    }

    // Peer 정보 교환 과정 처리 함수
    private fun doExchangePeerInfo(): Boolean {
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN) // Peer 정보 크기만큼 ByteBuffer 생성
        peerInfo.writeTo(buf) // Peer 정보 기록

        val outbuf = pairingContext.encrypt(buf.array()) ?: return false // 암호화, 실패 시 false 반환

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size) // Peer 정보에 대한 헤더 생성
        writeHeader(ourHeader, outbuf) // 헤더와 암호화된 데이터를 전송

        val theirHeader = readHeader() ?: return false // 상대방 헤더 읽기, 실패 시 false 반환
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false // Peer 정보 타입 확인

        val theirMessage = ByteArray(theirHeader.payload) // 상대방 메시지 크기만큼 배열 생성
        inputStream.readFully(theirMessage)               // 메시지 읽기

        // 메시지 복호화, 실패 시 예외 발생
        val decrypted = pairingContext.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {
            Log.e(TAG, "Got size=${decrypted.size} PeerInfo.size=$kMaxPeerInfoSize") // Peer 정보 크기 오류
            return false
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))  // Peer 정보 읽어옴
        Log.d(TAG, theirPeerInfo.toString()) // Peer 정보 출력
        return true
    }

    // 자원 해제 함수 (소켓과 스트림을 닫음)
    override fun close() {
        try {
            inputStream.close()   // 입력 스트림 닫기
        } catch (e: Throwable) {
        }
        try {
            outputStream.close()  // 출력 스트림 닫기
        } catch (e: Throwable) {
        }
        try {
            socket.close()        // 소켓 닫기
        } catch (e: Exception) {
        }

        if (state != State.Ready) {
            pairingContext.destroy() // Ready 상태가 아니면 PairingContext 파괴
        }
    }

    companion object {

        init {
            System.loadLibrary("adb") // native 라이브러리 로드
        }

        @JvmStatic
        external fun available(): Boolean // JNI 함수로 페어링 클라이언트 사용 가능 여부 확인
    }
}
