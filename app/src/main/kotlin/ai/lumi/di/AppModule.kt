package ai.lumi.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ai.lumi.data.db.LumiDatabase
import ai.lumi.data.db.dao.FormMemoryDao
import ai.lumi.data.db.dao.MemoryVectorDao
import ai.lumi.data.db.dao.TaskMemoryDao
import ai.lumi.data.db.dao.UIMapDao
import ai.lumi.data.db.dao.UserProfileDao
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LumiDatabase =
        Room.databaseBuilder(context, LumiDatabase::class.java, "lumi.db")
            // Add explicit Room migrations for future schema versions; never silently wipe memory.
            .build()

    @Provides fun provideUIMapDao(db: LumiDatabase): UIMapDao = db.uiMapDao()
    @Provides fun provideUserProfileDao(db: LumiDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideTaskMemoryDao(db: LumiDatabase): TaskMemoryDao = db.taskMemoryDao()
    @Provides fun provideFormMemoryDao(db: LumiDatabase): FormMemoryDao = db.formMemoryDao()
    @Provides fun provideMemoryVectorDao(db: LumiDatabase): MemoryVectorDao = db.memoryVectorDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()
}
