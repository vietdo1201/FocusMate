plugins {
    id("com.android.application")      version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.jvm")     version "2.2.20" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
