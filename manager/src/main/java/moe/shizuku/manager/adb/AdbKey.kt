package moe.shizuku.manager.adb

// 여러 필요한 암호화 라이브러리와 클래스들 임포트
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import rikka.core.ktx.unsafeLazy
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

// TAG는 로그에서 사용될 태그로, 디버깅 시 "AdbKey"로 표시됨
private const val TAG = "AdbKey"

// ADB 키를 관리하는 클래스 AdbKey 정의, adbKeyStore는 ADB 키를 저장하거나 가져오는 데 사용됨
class AdbKey(private val adbKeyStore: AdbKeyStore, name: String) {

    companion object {
        // Android KeyStore를 사용할 때 필요한 상수들 정의
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding" // AES 암호화 모드

        // AES 암호화 시 필요한 IV와 태그 사이즈
        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        // 패딩을 적용하여 데이터 크기를 맞춤
        private val PADDING = byteArrayOf(
                0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
                0x04, 0x14)
    }

    // 암호화 키와 RSA 개인키 및 공개키를 저장할 변수 선언
    private val encryptionKey: Key

    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey
    private val certificate: X509Certificate

    // 클래스가 초기화될 때 호출되는 init 블록
    init {
        // Android Keystore에서 암호화 키를 가져오거나 생성함
        this.encryptionKey = getOrCreateEncryptionKey() ?: error("Failed to generate encryption key with AndroidKeyManager.")

        // RSA 개인키와 공개키를 생성 또는 가져옴
        this.privateKey = getOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

        // 개인키를 사용해 인증서를 생성
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509Certificate = X509v3CertificateBuilder(X500Name("CN=00"), // 발행자
                BigInteger.ONE,                          // 일련번호
                Date(0),                            // 유효기간 시작일
                Date(2461449600 * 1000),            // 유효기간 종료일
                Locale.ROOT,                             // 로케일
                X500Name("CN=00"),               // 주체
                SubjectPublicKeyInfo.getInstance(publicKey.encoded) // 공개키 정보
        ).build(signer)

        // 인증서를 X.509 형식으로 변환
        this.certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(x509Certificate.encoded)) as X509Certificate

        // 생성된 RSA 개인키 로그로 출력
        Log.d(TAG, privateKey.toString())
    }

    // lazy 초기화를 사용하여 ADB용 공개키를 인코딩
    val adbPublicKey: ByteArray by unsafeLazy {
        publicKey.adbEncoded(name)
    }

    // 암호화 키를 생성하거나 기존 키를 가져오는 함수
    private fun getOrCreateEncryptionKey(): Key? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // 키가 이미 존재하면 가져오고, 없으면 새로 생성
        return keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val parameterSpec = KeyGenParameterSpec.Builder(ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)  // AES 256비트 암호화 사용
                    .build()
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }
    }

    // 데이터를 암호화하는 함수
    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) {
            return null // 암호화할 데이터가 너무 크면 null 반환
        }
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    // 데이터를 복호화하는 함수
    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) {
            return null  // 복호화할 데이터가 너무 작으면 null 반환
        }
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    // RSA 개인키를 생성하거나 기존 키를 가져오는 함수
    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        var privateKey: RSAPrivateKey? = null

        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        // 기존에 저장된 키가 있으면 복호화해서 가져옴
        var ciphertext = adbKeyStore.get()
        if (ciphertext != null) {
            try {
                val plaintext = decrypt(ciphertext, aad)

                val keyFactory = KeyFactory.getInstance("RSA")
                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            } catch (e: Exception) {
                // 복호화 실패 시 무시
            }
        }
        // 새로운 키를 생성해야 하는 경우 처리
        if (privateKey == null) {
            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
            keyPairGenerator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val keyPair = keyPairGenerator.generateKeyPair()
            privateKey = keyPair.private as RSAPrivateKey

            // 생성된 키를 암호화하여 저장
            ciphertext = encrypt(privateKey.encoded, aad)
            if (ciphertext != null) {
                adbKeyStore.put(ciphertext)
            }
        }
        return privateKey
    }

    // 데이터를 서명하는 함수 (RSA 암호화 방식 사용)
    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? {
                Log.d(TAG, "chooseClientAlias: keyType=${keyTypes.contentToString()}, issuers=${issuers?.contentToString()}")
                for (keyType in keyTypes) {
                    if (keyType == "RSA") return alias
                }
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                Log.d(TAG, "getCertificateChain: alias=$alias")
                return if (alias == this.alias) arrayOf(certificate) else null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                Log.d(TAG, "getPrivateKey: alias=$alias")
                return if (alias == this.alias) privateKey else null
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? {
                return null
            }
        }


    private val trustManager
        get() =
            @RequiresApi(Build.VERSION_CODES.R)
            object : X509ExtendedTrustManager() {

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return emptyArray()
                }
            }

    // SSLContext와 관련된 키 및 트러스트 관리자 정의
    @delegate:RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by unsafeLazy {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        sslContext
    }
}

// AdbKeyStore 인터페이스: ADB 키를 저장하고 가져오기 위한 메서드 정의
interface AdbKeyStore {

    fun put(bytes: ByteArray)

    fun get(): ByteArray?
}

// PreferenceAdbKeyStore: SharedPreferences를 사용하여 ADB 키를 저장하는 클래스
class PreferenceAdbKeyStore(private val preference: SharedPreferences) : AdbKeyStore {

    private val preferenceKey = "adbkey"

    // ADB 키를 저장하는 메서드
    override fun put(bytes: ByteArray) {
        preference.edit { putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP))) }
    }

    // ADB 키를 가져오는 메서드
    override fun get(): ByteArray? {
        if (!preference.contains(preferenceKey)) return null
        return Base64.decode(preference.getString(preferenceKey, null), Base64.NO_WRAP)
    }
}

// ADB 공개키와 관련된 크기 상수 정의
const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
const val RSAPublicKey_Size = 524

// BigInteger를 ADB용 인코딩 방식으로 변환하는 확장 함수
private fun BigInteger.toAdbEncoded(): IntArray {
    // little-endian integer with padding zeros in the end

    val endcoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        endcoded[i] = out[1].toInt()
    }
    return endcoded
}

// RSA 공개키를 ADB용 형식으로 인코딩하는 확장 함수
private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r30:system/core/libcrypto_utils/android_pubkey.c

    /*
    typedef struct RSAPublicKey {
        uint32_t modulus_size_words; // ANDROID_PUBKEY_MODULUS_SIZE
        uint32_t n0inv; // n0inv = -1 / N[0] mod 2^32
        uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE];
        uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE]; // rr = (2^(rsa_size)) ^ 2 mod N
        uint32_t exponent;
    } RSAPublicKey;
    */

    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}
