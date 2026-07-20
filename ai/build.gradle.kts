plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.zer0.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))

    // v1.100: Compose runtime 注解(@Immutable),用于标注 UIMessage/ToolCallInfo 为不可变,
    // 让 Compose 编译器跳过无效重组。通过 BOM 管理版本,只引入注解依赖。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    // OkHttp + SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Koin(DSL module/single/get)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
