// Top-level build file
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// 清理任务
tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
