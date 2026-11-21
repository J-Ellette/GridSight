package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class GridSightConverters {
    @TypeConverter fun fromAccountRole(v: AccountRole): String = v.name
    @TypeConverter fun toAccountRole(v: String): AccountRole = AccountRole.valueOf(v)

    @TypeConverter fun fromNodeType(v: NodeType): String = v.name
    @TypeConverter fun toNodeType(v: String): NodeType = NodeType.valueOf(v)

    @TypeConverter fun fromSecurityPosture(v: SecurityPosture): String = v.name
    @TypeConverter fun toSecurityPosture(v: String): SecurityPosture = SecurityPosture.valueOf(v)

    @TypeConverter fun fromSyncStatus(v: SyncStatus): String = v.name
    @TypeConverter fun toSyncStatus(v: String): SyncStatus = SyncStatus.valueOf(v)

    @TypeConverter fun fromReadinessStatus(v: ReadinessStatus): String = v.name
    @TypeConverter fun toReadinessStatus(v: String): ReadinessStatus = ReadinessStatus.valueOf(v)

    @TypeConverter fun fromDeliveryStatus(v: DeliveryStatus): String = v.name
    @TypeConverter fun toDeliveryStatus(v: String): DeliveryStatus = DeliveryStatus.valueOf(v)
}

@Database(
    entities = [
        OperatorAccountEntity::class,
        DetectedNodeEntity::class,
        PeerDeviceEntity::class,
        EncryptedMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(GridSightConverters::class)
abstract class GridSightDatabase : RoomDatabase() {
    abstract fun dao(): GridSightDao

    companion object {
        @Volatile
        private var INSTANCE: GridSightDatabase? = null

        fun getInstance(context: Context): GridSightDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GridSightDatabase::class.java,
                    "gridsight_offline.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
