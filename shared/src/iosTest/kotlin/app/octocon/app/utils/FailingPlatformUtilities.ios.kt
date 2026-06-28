package app.octocon.app.utils

actual class FailingPlatformUtilities actual constructor() :
  FailingPlatformUtilitiesBase(), PlatformUtilities {
  override var injectedPlatformDelegate: PlatformDelegate? = null
}
