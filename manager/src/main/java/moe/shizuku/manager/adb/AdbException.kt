package moe.shizuku.manager.adb

// @Suppress("NOTHING_TO_INLINE"): inline 함수에서 아무 동작을 하지 않는 코드를 사용하지 않도록 경고를 무시
@Suppress("NOTHING_TO_INLINE")
// adbError: 특정 메시지와 함께 AdbException을 던지는 함수
// inline: 이 함수가 호출될 때마다 함수 호출이 아닌 코드 자체가 삽입되도록 하여 성능 최적화
// Nothing: 이 함수는 예외를 던지기 때문에 호출 이후의 실행 흐름이 없음을 나타냄
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

// AdbException: ADB와 관련된 오류를 처리하는 커스텀 예외 클래스
// Exception 클래스를 상속받아 ADB 관련 예외 처리에 특화된 클래스
open class AdbException : Exception {

    // 첫 번째 생성자: 예외 메시지와 예외 원인(cause)을 함께 받을 수 있음
    constructor(message: String, cause: Throwable?) : super(message, cause)
    // 두 번째 생성자: 예외 메시지만 받을 수 있음
    constructor(message: String) : super(message)
    // 세 번째 생성자: 예외 원인만 받을 수 있음
    constructor(cause: Throwable) : super(cause)
    // 네 번째 생성자: 아무런 인자도 받지 않음
    constructor()
}

// AdbInvalidPairingCodeException: 페어링 코드가 잘못되었을 때 발생하는 예외
// AdbException을 상속받아 ADB 페어링 코드와 관련된 오류를 처리
class AdbInvalidPairingCodeException : AdbException()

// AdbKeyException: ADB 키와 관련된 오류가 발생했을 때 사용되는 예외
// 예외 원인(cause)을 받아서 부모 클래스(AdbException)에 전달
class AdbKeyException(cause: Throwable) : AdbException(cause)
