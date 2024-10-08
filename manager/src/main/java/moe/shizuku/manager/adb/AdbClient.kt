package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import moe.shizuku.manager.ktx.logd
import rikka.core.util.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

// 이 소스는 AdbClient 클래스를 정의하고 있으며, ADB(안드로이드 디버그 브리지) 프로토콜을 통해 원격으로 명령을 실행하는 클라이언트를 구현하고 있습니다.
// 이 클라이언트는 TLS(Transport Layer Security)를 사용해 보안 통신을 지원하며, ADB 명령을 전송하고 응답을 처리합니다.

// AdbClient 클래스: ADB 서버와 통신할 수 있는 클라이언트를 정의
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    // TCP 소켓을 통해 ADB 서버와 연결
    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    // TLS(보안) 연결 여부를 나타내는 플래그
    private var useTls = false

    // TLS 소켓과 입력/출력 스트림을 위한 변수
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    // TLS 사용 여부에 따라 적절한 입력 스트림을 반환
    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    // TLS 사용 여부에 따라 적절한 출력 스트림을 반환
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    // ADB 서버에 연결을 시도하는 함수
    fun connect() {
        // TCP 소켓을 열고 ADB 서버에 연결
        socket = Socket(host, port)
        socket.tcpNoDelay = true    // 네트워크 지연을 최소화
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        // ADB 연결 요청 패킷을 작성하여 전송
        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        // ADB 서버로부터 응답을 읽음
        var message = read()
        // TLS 연결 요청이 있는 경우
        if (message.command == A_STLS) {
            // Android 9 이하에서는 TLS 연결을 지원하지 않음
            if (!BuildUtils.atLeast29) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            // TLS 요청에 응답하고 TLS 소켓을 설정하여 보안 연결
            write(A_STLS, A_STLS_VERSION, 0)

            // TLS 소켓을 통해 SSL 핸드셰이크 수행
            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            // TLS를 사용하는 입력/출력 스트림 설정
            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            // TLS 핸드셰이크 후 새로운 메시지를 읽음
            message = read()
        // ADB 인증 요청이 있는 경우
        } else if (message.command == A_AUTH) {
            // ADB 인증 토큰을 받아와서 서명한 후 응답
            if (message.command != A_AUTH && message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            // ADB 서버의 응답을 읽음
            message = read()
            // 인증이 성공하지 않은 경우 RSA 공개키를 전송하여 인증 시도
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        // 연결이 성공하지 않으면 에러 발생
        if (message.command != A_CNXN) error("not A_CNXN")
    }

    // 셸 명령을 실행하는 함수. ADB를 통해 셸 명령을 전송하고 결과를 리스너로 반환
    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        // ADB OPEN 명령을 통해 셸 명령을 서버에 전송
        write(A_OPEN, localId, 0, "shell:$command")

        // 서버로부터 응답을 처리
        var message = read()
        when (message.command) {
            A_OKAY -> { // 셸 명령이 성공적으로 실행된 경우
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    // WRTE 메시지가 오면 데이터를 처리
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    // CLSE 메시지가 오면 종료
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    // 다른 메시지가 오면 에러 처리
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> { // CLSE 메시지가 먼저 온 경우
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> { // OKAY 또는 CLSE 이외의 메시지가 온 경우
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    // ADB 명령을 작성하여 전송하는 함수 (바이너리 데이터)
    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    // ADB 명령을 작성하여 전송하는 함수 (문자열 데이터)
    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    // ADB 메시지를 전송하는 함수
    private fun write(message: AdbMessage) {
        // 메시지를 바이트 배열로 변환하여 전송
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    // ADB 서버로부터 메시지를 읽는 함수
    private fun read(): AdbMessage {
        // ADB 메시지 헤더를 읽고 처리 (리틀 엔디안으로 읽음)
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        // 메시지의 필드를 각각 읽음
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray?
        // 데이터가 있는 경우 데이터 부분도 읽음
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        // 읽은 데이터를 AdbMessage 객체로 변환
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        // 메시지의 유효성을 검사
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    // 리소스를 해제하는 함수 (소켓 및 스트림 종료)
    override fun close() {
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        // TLS 소켓을 사용하는 경우 TLS 스트림과 소켓도 종료
        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}