package com.myAllVideoBrowser.di.module

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.myAllVideoBrowser.data.local.room.dao.AdBlockDao
import com.myAllVideoBrowser.data.remote.service.ConfigService
import com.myAllVideoBrowser.data.remote.service.VideoService
import com.myAllVideoBrowser.data.remote.service.VideoServiceSuperX
import com.myAllVideoBrowser.data.remote.service.VideoServiceLocal
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockEngine
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.ProxyRetryInterceptor
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import dagger.Module
import dagger.Provides
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
class NetworkModule {

    companion object {
        private const val DATA_URL = "https://some-url.com/youtube-dl/"

        // OkHttp's own defaults (maxRequests=64, maxRequestsPerHost=5) quietly
        // throttle concurrent segment downloads to the same CDN host, no
        // matter how high the user sets the thread-count slider in Settings
        // (up to 16 - see SettingsFragment.ABSOLUTE_MAX_THREADS). Raise the
        // per-host cap so the configured thread count can actually be used.
        private const val MAX_REQUESTS_PER_HOST = 32
        private const val MAX_REQUESTS_TOTAL = 64
        private const val MAX_IDLE_CONNECTIONS = 32
    }

    @Singleton
    @Provides
    fun provideOkHttpClient(
        cookieJar: PersistentCookieJar,
        @ApplicationContext context: Context
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = MAX_REQUESTS_TOTAL
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(ProxyRetryInterceptor(context))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
            .build()
    }

    @Provides
    @Singleton
    fun provideConfigService(okHttpClient: OkHttpClient): ConfigService = Retrofit.Builder()
        .baseUrl(DATA_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build()
        .create(ConfigService::class.java)

    @Provides
    @Singleton
    fun provideVideoService(
        proxyController: CustomProxyController,
    ): VideoService = VideoServiceLocal(
        proxyController
    )

    @Provides
    @Singleton
    fun provideFfmpegVideoService(httpClient: OkHttpProxyClient): VideoServiceSuperX =
        VideoServiceSuperX(
            httpClient
        )

    @Singleton
    @Provides
    fun provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar {
        return PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))
    }

    @Singleton
    @Provides
    fun provideAdblockerEngine(
        @ApplicationContext context: Context,
        adBlockDao: AdBlockDao
    ): AdBlockEngine {
        return AdBlockEngine(context, adBlockDao)
    }
}
