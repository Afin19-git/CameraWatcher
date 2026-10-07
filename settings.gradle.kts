pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // JitPack: USB-webcam (UVC) library, see https://github.com/WojciechCzeronko/AndroidUSBCamera
        maven("https://jitpack.io")
    }
}
rootProject.name = "CameraWatcher"
include(":app")
