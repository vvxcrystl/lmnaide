package dev.lmnaide.calendar.sync

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.lmnaide.calendar.data.CalendarRepository
import kotlinx.coroutines.CoroutineScope

object CloudDatabase {
    fun install(db: SupportSQLiteDatabase) = Unit
}

class CloudSync(context: Context, repository: CalendarRepository, scope: CoroutineScope) {
    suspend fun start() = Unit

    fun requestSync() = Unit

    fun setForeground(active: Boolean) = Unit
}

@Composable fun CloudSettings() = Unit
