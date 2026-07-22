package io.zer0.ai

import io.zer0.ai.image.ImageService
import io.zer0.ai.video.GenericOpenAiVideoProvider
import io.zer0.ai.video.KlingVideoProvider
import io.zer0.ai.video.VideoGenerationService
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * ai 模块的 Koin 装配。
 *
 * [ProviderConfigStore] 由 app 模块提供实现(基于 DataStore),
 * [OkHttpClient] 也由 app 模块注册(用 named("chat") qualifier,与 web search client 区分),
 * 因此这里只声明 [ChatService] / [ImageService] / [VideoGenerationService]。
 */
val aiModule: Module = module {
    single { ChatService(get()) }
    single { ImageService(get(), get(named("chat"))) } // Phase 5-G / Phase 8.5 修复 qualifier

    // P2-8: 视频生成服务 — 注入 named("chat") OkHttpClient(已配 30s connect / 300s read 超时,
    // 满足提交 30s + 轮询 5min 的超时要求)
    // v1.136: 新增通用 OpenAI 兼容视频 Provider,与 Kling 一起注册到 Service map
    single { KlingVideoProvider(get(named("chat"))) }
    single { GenericOpenAiVideoProvider(get(named("chat"))) }
    single {
        VideoGenerationService(
            providers = mapOf(
                "kling" to get<KlingVideoProvider>(),
                GenericOpenAiVideoProvider.PROVIDER_ID to get<GenericOpenAiVideoProvider>(),
            ),
            genericProvider = get<GenericOpenAiVideoProvider>(),
        )
    }
}
