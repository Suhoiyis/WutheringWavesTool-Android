// 顶层构建脚本：只声明插件版本，不在这里配置模块
// 注意：AGP 9.0 起 Kotlin 支持内置，不再需要 org.jetbrains.kotlin.android 插件
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
