package com.alethra.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user" | "assistant"
    val content: String,
    val createdAt: Long
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id")
    fun messages(id: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id")
    suspend fun messagesOnce(id: Long): List<MessageEntity>

    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Insert suspend fun insertMessage(m: MessageEntity): Long

    @Query("UPDATE conversations SET updatedAt = :u WHERE id = :id")
    suspend fun touch(id: Long, u: Long)

    @Query("UPDATE conversations SET title = :t WHERE id = :id")
    suspend fun rename(id: Long, t: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ChatDao
}
