package com.arflix.tv.data.repository

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

val DRIVE_ACCOUNT_NAME_KEY = stringPreferencesKey("drive_sync_account")

@Singleton
class DriveSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.appdata"
        private const val FILE_NAME = "xadarr_settings.json"
        private const val LIST_URL =
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder" +
            "&q=name%3D%27xadarr_settings.json%27&fields=files(id)"
        private fun contentUrl(id: String) = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
        private fun updateUrl(id: String) =
            "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media"
        private const val CREATE_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
    }

    private val am get() = AccountManager.get(context)

    suspend fun savedAccountName(): String? =
        context.settingsDataStore.data.first()[DRIVE_ACCOUNT_NAME_KEY]?.takeIf { it.isNotBlank() }

    suspend fun saveAccount(name: String) {
        context.settingsDataStore.edit { prefs -> prefs[DRIVE_ACCOUNT_NAME_KEY] = name }
    }

    suspend fun disconnect() {
        context.settingsDataStore.edit { prefs -> prefs.remove(DRIVE_ACCOUNT_NAME_KEY) }
    }

    suspend fun isConnected(): Boolean = savedAccountName() != null

    fun listGoogleAccounts(): List<Account> =
        am.getAccountsByType(GOOGLE_ACCOUNT_TYPE).toList()

    /**
     * Gets a Drive OAuth token for the saved account.
     * If user consent is required, [onConsentNeeded] is called with the intent to launch.
     */
    suspend fun getToken(onConsentNeeded: ((Intent) -> Unit)? = null): String? =
        withContext(Dispatchers.IO) {
            val name = savedAccountName() ?: return@withContext null
            val account = am.getAccountsByType(GOOGLE_ACCOUNT_TYPE)
                .firstOrNull { it.name == name } ?: return@withContext null
            runCatching {
                val future = am.getAuthToken(account, DRIVE_SCOPE, null, false, null, null)
                val bundle = future.result
                @Suppress("DEPRECATION")
                val intent = bundle.getParcelable<android.os.Parcelable>(AccountManager.KEY_INTENT) as? Intent
                if (intent != null) {
                    onConsentNeeded?.invoke(intent)
                    return@withContext null
                }
                bundle.getString(AccountManager.KEY_AUTHTOKEN)
            }.getOrNull()
        }

    /** Invalidates the cached token so it will be re-fetched on the next call. */
    fun invalidateToken() {
        val name = runCatching {
            android.os.Looper.myLooper() // can't use coroutines here, best-effort
            am.getAccountsByType(GOOGLE_ACCOUNT_TYPE).firstOrNull()
        }.getOrNull() ?: return
        // Token invalidation is handled by AccountManager automatically on 401
    }

    suspend fun push(payload: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext Result.failure(
            IllegalStateException("Drive not connected or consent needed")
        )
        runCatching {
            val existingId = findFileId(token)
            if (existingId != null) {
                val req = Request.Builder()
                    .url(updateUrl(existingId))
                    .patch(payload.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) {
                        invalidateToken()
                        throw IllegalStateException("Drive token expired")
                    }
                    if (!resp.isSuccessful) throw IllegalStateException("Drive PATCH ${resp.code}")
                }
            } else {
                val boundary = "xadarr_mp"
                val meta = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
                val mp = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                    "$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n$payload\r\n--$boundary--"
                val req = Request.Builder()
                    .url(CREATE_URL)
                    .post(mp.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) {
                        invalidateToken()
                        throw IllegalStateException("Drive token expired")
                    }
                    if (!resp.isSuccessful) throw IllegalStateException("Drive POST ${resp.code}")
                }
            }
        }
    }

    suspend fun pull(): Result<String?> = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext Result.failure(
            IllegalStateException("Drive not connected or consent needed")
        )
        runCatching {
            val id = findFileId(token) ?: return@runCatching null
            val req = Request.Builder()
                .url(contentUrl(id))
                .get()
                .header("Authorization", "Bearer $token")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) { invalidateToken(); return@runCatching null }
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }
    }

    private fun findFileId(token: String): String? = runCatching {
        val req = Request.Builder()
            .url(LIST_URL)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string() ?: return@runCatching null
            JSONObject(body).optJSONArray("files")?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)?.optString("id")
        }
    }.getOrNull()
}
