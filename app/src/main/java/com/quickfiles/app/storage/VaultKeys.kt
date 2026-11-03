package com.quickfiles.app.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the vault's data key with an Android Keystore key that only works for [AUTH_SECONDS] after the user
 * proves who they are with a strong fingerprint/face or the screen lock. The Keystore key never leaves secure
 * hardware, so a copy of the app's files cannot be opened elsewhere. Adding a fingerprint does not invalidate it;
 * removing the screen lock does, and then the vault cannot be opened again.
 */
class KeystoreGuard(private val alias: String = "gotgan-vault") : Vault.KeyGuard {
    private fun keystoreKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw StorageException(StorageError.ACCESS, "보관함 열쇠가 없습니다. 휴대폰 화면 잠금을 해제했다가 다시 설정하면 이전 보관함은 열 수 없습니다.")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(false)
            .build())
        return generator.generateKey()
    }

    override fun wrap(key: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey(create = true)) }
        val iv = cipher.iv
        check(iv.size == IV) { "Unexpected IV size ${iv.size}" }
        iv + cipher.doFinal(key)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = guarded {
        if (wrapped.size <= IV) throw StorageException(StorageError.INVALID_RESPONSE, "보관함 열쇠 파일이 손상되었습니다.")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keystoreKey(create = false), GCMParameterSpec(128, wrapped, 0, IV)) }
        cipher.doFinal(wrapped, IV, wrapped.size - IV)
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (e: UserNotAuthenticatedException) { throw StorageException(StorageError.AUTH, "본인 확인 시간이 지났습니다. 보관함을 다시 열어 주세요.", e) }
        catch (e: KeyPermanentlyInvalidatedException) { throw StorageException(StorageError.ACCESS, "휴대폰 화면 잠금이 해제되어 보관함 열쇠가 무효가 되었습니다. 보관함 파일은 더 이상 열 수 없습니다.", e) }
        catch (e: javax.crypto.AEADBadTagException) { throw StorageException(StorageError.INVALID_RESPONSE, "보관함 열쇠 파일이 손상되었습니다.", e) }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = 12
        /** How long one fingerprint or screen-lock check lets the vault key be used. */
        const val AUTH_SECONDS = 30
    }
}
