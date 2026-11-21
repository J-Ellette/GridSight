package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GridSightDao {
    // --- Operator Accounts & RBAC ---
    @Query("SELECT * FROM operator_accounts ORDER BY createdAt ASC")
    fun observeAllAccounts(): Flow<List<OperatorAccountEntity>>

    @Query("SELECT COUNT(*) FROM operator_accounts")
    suspend fun getAccountCount(): Int

    @Query("SELECT * FROM operator_accounts WHERE username = :username LIMIT 1")
    suspend fun getAccountByUsername(username: String): OperatorAccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: OperatorAccountEntity)

    @Query("UPDATE operator_accounts SET lastLoginAt = :timestamp WHERE username = :username")
    suspend fun updateLastLogin(username: String, timestamp: Long)

    @Query("UPDATE operator_accounts SET role = :role WHERE username = :username")
    suspend fun updateAccountRole(username: String, role: AccountRole)

    @Query("DELETE FROM operator_accounts WHERE username = :username")
    suspend fun deleteAccount(username: String)

    // --- Detected Nodes ---
    @Query("SELECT * FROM detected_nodes ORDER BY isPinned DESC, lastSeenAt DESC")
    fun observeAllNodes(): Flow<List<DetectedNodeEntity>>

    @Query("SELECT * FROM detected_nodes WHERE syncStatus = :status")
    suspend fun getNodesBySyncStatus(status: SyncStatus): List<DetectedNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: DetectedNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<DetectedNodeEntity>)

    @Query("UPDATE detected_nodes SET isPinned = :pinned WHERE id = :id")
    suspend fun setNodePinned(id: String, pinned: Boolean)

    @Query("UPDATE detected_nodes SET isSharedToMesh = 1, syncStatus = :status WHERE id = :id")
    suspend fun queueNodeForSync(id: String, status: SyncStatus = SyncStatus.QUEUED_SYNC)

    @Query("UPDATE detected_nodes SET syncStatus = :status WHERE id IN (:ids)")
    suspend fun updateNodesSyncStatus(ids: List<String>, status: SyncStatus)

    @Query("DELETE FROM detected_nodes WHERE id = :id")
    suspend fun deleteNode(id: String)

    @Query("DELETE FROM detected_nodes")
    suspend fun purgeAllNodes()

    // --- Peers ---
    @Query("SELECT * FROM peer_devices ORDER BY isOnline DESC, lastSeenAt DESC")
    fun observePeers(): Flow<List<PeerDeviceEntity>>

    @Query("SELECT * FROM peer_devices WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeerById(peerId: String): PeerDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerDeviceEntity)

    @Query("UPDATE peer_devices SET isOnline = 0")
    suspend fun markAllPeersOffline()

    // --- Encrypted Messages ---
    @Query("SELECT * FROM encrypted_messages ORDER BY timestamp ASC")
    fun observeAllMessages(): Flow<List<EncryptedMessageEntity>>

    @Query("SELECT * FROM encrypted_messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun observeMessagesForPeer(peerId: String): Flow<List<EncryptedMessageEntity>>

    @Query("SELECT * FROM encrypted_messages WHERE deliveryStatus = :status")
    suspend fun getQueuedMessages(status: DeliveryStatus = DeliveryStatus.QUEUED_OFFLINE): List<EncryptedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: EncryptedMessageEntity)

    @Query("UPDATE encrypted_messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: DeliveryStatus)

    @Query("DELETE FROM encrypted_messages")
    suspend fun purgeAllMessages()
}
