// The Beacon helper app that runs on the agent phone (EMU-DESIGN revision 4, section 6.6). Its own Gradle project:
// it shares nothing with beacon-android and ships as one small APK the plugin installs on the agent phone.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "BeaconAgentPhoneHelper"
