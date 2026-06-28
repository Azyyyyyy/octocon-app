package app.octocon.app.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider

actual class FailingPlatformUtilities actual constructor() :
  FailingPlatformUtilitiesBase(), PlatformUtilities {
  override val context: Context = ApplicationProvider.getApplicationContext()
}
