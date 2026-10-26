package com.quickfiles.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.connectionData by preferencesDataStore("connections")

data class ConnectionProfile(val id: String, val name: String, val url: String, val username: String, val encryptedPassword: String)

class ConnectionStore(private val context: Context) {
    private val key = stringPreferencesKey("profiles_v1")
    val profiles: Flow<List<ConnectionProfile>> = context.connectionData.data.map { decode(it[key] ?: "[]") }.flowOn(Dispatchers.IO)

    suspend fun save(id: String?, name: String, url: String, username: String, password: String): ConnectionProfile = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "연결 이름을 입력해 주세요." }
        require(username.isNotBlank() && ':' !in username && '\n' !in username && '\r' !in username) { "올바른 사용자 이름을 입력해 주세요."
        }
        val profile = ConnectionProfile(id ?: UUID.randomUUID().toString(), name.trim(),
            WebDavProvider.validatedUrl(url).toString(), username, encrypt(password))
        context.connectionData.edit { preferences ->
            val existing = decode(preferences[key] ?: "[]").filterNot { it.id == profile.id }
            preferences[key] = encode(existing + profile)
        }
        profile
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        context.connectionData.edit { preferences ->
            preferences[key] = encode(decode(preferences[key] ?: "[]").filterNot { it.id == id })
        }
    }

    suspend fun password(profile: ConnectionProfile): String = withContext(Dispatchers.IO) {
        try {
            val parts = profile.encryptedPassword.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw StorageException(StorageError.AUTH, "저장한 비밀번호를 읽을 수 없습니다. 연결을 수정해 다시 입력해 주세요.", e)
        }
    }

    private fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    @Synchronized private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("fileexplorer.connections.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("fileexplorer.connections.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun decode(value: String): List<ConnectionProfile> {
        val array = JSONArray(value)
        return List(array.length()) { index -> array.getJSONObject(index).let {
            ConnectionProfile(it.getString("id"), it.getString("name"), it.getString("url"), it.getString("username"), it.getString("password"))
        } }
    }

    private fun encode(profiles: List<ConnectionProfile>) = JSONArray().apply {
        profiles.forEach { profile -> put(JSONObject().apply {
            put("id", profile.id); put("name", profile.name); put("url", profile.url)
            put("username", profile.username); put("password", profile.encryptedPassword)
        }) }
    }.toString()
}
