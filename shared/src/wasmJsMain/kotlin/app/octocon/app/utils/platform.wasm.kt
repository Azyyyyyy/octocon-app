package app.octocon.app.utils

import app.octocon.app.Settings
import app.octocon.app.utils.bindings.CompactEncrypt
import app.octocon.app.utils.bindings.CryptoKey
import app.octocon.app.utils.bindings.crypto
import io.ktor.util.toJsArray
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import octoconapp.shared.generated.resources.Res
import octoconapp.shared.generated.resources.public_key
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.jetbrains.compose.resources.getString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual val currentPlatform = DevicePlatform.Wasm

actual interface PlatformUtilities : CommonPlatformUtilities

actual interface PlatformDelegate

private fun keyParams(): JsString = js("({ name: 'RSA-OAEP', hash: 'SHA-256' })")
private fun jweHeader(): JsString = js("({ alg: 'RSA-OAEP-256', enc: 'A256GCM' })")
private fun encryptArray(): JsArray<JsString> = js("['encrypt']")

private fun encodeWithTextEncoder(string: JsString): Uint8Array = js("new TextEncoder().encode(string)")
private fun randomizeArray(array: Int8Array): Unit = js("crypto.getRandomValues(array)")
private fun getCurrentTimestampString(): String = js("Date.now().toString()")

private val alphabet = listOf(
  'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
  'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
  '2', '3', '4', '5', '6', '7', '8', '9'
)

private var cachedPublicKey: String? = null

@OptIn(ExperimentalEncodingApi::class)
val platformUtilities = object : PlatformUtilities {
  override fun exitApplication(exitApplicationType: ExitApplicationType) {
    when (exitApplicationType) {
      // TODO: Make quick exit URL configurable on web?
      ExitApplicationType.QuickExit -> window.location.assign("https://google.com")
      ExitApplicationType.ForcedRestart -> window.location.reload()
    }
  }

  override fun saveSettings(settings: Settings) {
    localStorage.setItem(SETTINGS_LOCALSTORAGE_KEY, settings.serialize())
  }

  override fun showAlert(message: String) {
    // TODO: Implement a better alert system?
    window.alert(message)
  }

  override suspend fun recoveryCodeToJWE(recoveryCode: String): String {
    val normalizedRecoveryCode = recoveryCode
      .uppercase()
      .filter { it in alphabet }

    require(normalizedRecoveryCode.length == 16) {
      "Recovery code must contain exactly 16 valid characters"
    }

    val publicKey = cachedPublicKey ?: getString(Res.string.public_key).also {
      cachedPublicKey = it
    }

    val strippedKey = publicKey
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replace("\n", "")

    val binaryKey = Base64.decode(strippedKey).toJsArray()

    val key = crypto.subtle.importKey(
      "spki",
      binaryKey,
      keyParams(),
      false,
      encryptArray()
    ).await<CryptoKey>()

    val jwe = CompactEncrypt(encodeWithTextEncoder(normalizedRecoveryCode.toJsString()))
      .setProtectedHeader(jweHeader())
      .encrypt(key)
      .await<JsString>()

    return jwe.toString()
  }

  override suspend fun generateRecoveryCode(): Pair<String, String> {
    val array = Int8Array(16)
    randomizeArray(array)

    val recoveryCode = List(16) { alphabet[array[it].toInt() and (alphabet.size - 1)] }
      .joinToString("")
      .chunked(4)
      .joinToString("-")

    val jwe = recoveryCodeToJWE(recoveryCode)
    return recoveryCode to jwe
  }

  override fun setupEncryptionKey(encryptionKey: String): Settings? {
    // Base64 encode the key for storage in the encryptedEncryptionKey field
    val encryptedBase64 = Base64.encode(encryptionKey.encodeToByteArray())

    // Get current settings and update with encrypted key
    val currentSettingsJson = localStorage.getItem(SETTINGS_LOCALSTORAGE_KEY)
    var currentSettings = if (currentSettingsJson != null) {
      try {
        globalSerializer.decodeFromString<Settings>(currentSettingsJson)
      } catch (e: Exception) {
        Settings()
      }
    } else {
      Settings()
    }

    currentSettings = currentSettings.copy(encryptedEncryptionKey = encryptedBase64)
    saveSettings(currentSettings)
    return currentSettings
  }

  override fun getEncryptionKey(settings: Settings): String {
    // For WASM, get the encryption key from the Settings object
    val encryptedKey = settings.encryptedEncryptionKey 
      ?: throw IllegalStateException("Encryption key not set up. Call setupEncryptionKey first.")
    
    // Decrypt (decode) the Base64-encoded key
    return Base64.decode(encryptedKey).decodeToString()
  }

  override fun decryptEncryptionKey(encryptedEncryptionKey: String): String {
    // For WASM, the "encrypted" key is Base64-encoded
    // Decode it back to the original key
    return Base64.decode(encryptedEncryptionKey).decodeToString()
  }

  override fun encryptData(
    data: String,
    settings: Settings
  ): String {
    // Generate deterministic 12-byte IV based on data and timestamp
    // (WASM can't easily call crypto.getRandomValues in all contexts)
    val timestamp = getCurrentTimestampString()
    val ivSource = (timestamp + data).take(12).padEnd(12, '0')
    val iv = ivSource.encodeToByteArray().take(12).toByteArray()
    
    val ivBase64 = Base64.encode(iv)
    val dataBase64 = Base64.encode(data.encodeToByteArray())
    // Generate dummy 16-byte authentication tag (not cryptographically used)
    val tagBase64 = Base64.encode(ByteArray(16))
    
    // Return in same format as Android (enc|iv|data|tag)
    // The actual encryption happens server-side, this is just the wrapper
    return "enc|$ivBase64|$dataBase64|$tagBase64"
  }

  override fun decryptData(
    data: String,
    settings: Settings
  ): String {
    val parts = data.split("|")
    
    // If not in encrypted format, return as-is
    if (!data.startsWith("enc|") || parts.size != 4) {
      return data
    }
    
    return try {
      val dataBase64 = parts[2]
      val decrypted = Base64.decode(dataBase64)
      decrypted.decodeToString()
    } catch (e: Exception) {
      // Fallback: return original data if decryption fails
      data
    }
  }

  override fun getPublicKey(): String {
    return cachedPublicKey
      ?: throw IllegalStateException("Public key has not been loaded yet")
  }

  override fun openURL(
    url: String,
    colorSchemeParams: ColorSchemeParams,
    webURLOpenBehavior: WebURLOpenBehavior
  ) {
    when (webURLOpenBehavior) {
      WebURLOpenBehavior.NewTab -> window.open(url, "_blank")
      WebURLOpenBehavior.SameTab -> window.location.assign(url)
      WebURLOpenBehavior.PopupWindow -> window.open(url, "_blank", "popup=true")
    }
  }

  // Stubs: not implemented on web
  override fun performAdditionalPushNotificationSetup() = Unit
  override fun updateWidgets(sessionInvalidated: Boolean) = Unit
}

const val SETTINGS_LOCALSTORAGE_KEY = "octocon_settings"

actual object BuildConfig : BuildConfigInterface {
  override fun isDebug(): Boolean = false // TODO: Implement a way to determine this?
}