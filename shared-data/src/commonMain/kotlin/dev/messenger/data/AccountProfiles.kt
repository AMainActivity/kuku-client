package dev.messenger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="account_profiles")
data class AccountProfileEntity(@PrimaryKey val accountId:String,val json:String)
@Dao interface AccountProfilesDao {
    @Query("SELECT * FROM account_profiles WHERE accountId=:accountId")
    fun observe(accountId:String):Flow<AccountProfileEntity?>
    @Upsert suspend fun put(profile:AccountProfileEntity)
    @Query("DELETE FROM account_profiles WHERE accountId=:accountId")
    suspend fun clear(accountId:String)
}