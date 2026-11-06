package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import dev.messenger.domain.model.MemberInfo

internal enum class MessageReceipt { SENT, DELIVERED, READ }

// A group tick means all current recipients, excluding the sender, reached the cursor.
// Individual receipts remain available in the message details.
internal fun messageReceipt(seq:Long,senderId:String,members:List<MemberInfo>):MessageReceipt {
    val recipients=members.filter {it.userId!=senderId && it.status=="active"}
    return when {
        recipients.isEmpty() -> MessageReceipt.SENT
        recipients.all {it.readSeq>=seq} -> MessageReceipt.READ
        recipients.all {maxOf(it.deliveredSeq,it.readSeq)>=seq} -> MessageReceipt.DELIVERED
        else -> MessageReceipt.SENT
    }
}
