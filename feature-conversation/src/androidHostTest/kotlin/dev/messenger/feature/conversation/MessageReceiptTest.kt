package dev.messenger.feature.conversation

import dev.messenger.domain.model.MemberInfo
import org.junit.Test
import kotlin.test.assertEquals

class MessageReceiptTest {
    private fun member(id:String,delivered:Long=0,read:Long=0,status:String="active")=
        MemberInfo(id,id,"human",null,"member",status,1,delivered,read)
    @Test fun requiresActualRecipientEvidence() {
        assertEquals(MessageReceipt.SENT,messageReceipt(10,"me",emptyList()))
        assertEquals(MessageReceipt.SENT,messageReceipt(10,"me",listOf(member("me",20,20))))
        assertEquals(MessageReceipt.SENT,messageReceipt(10,"me",listOf(member("peer",9))))
        assertEquals(MessageReceipt.DELIVERED,messageReceipt(10,"me",listOf(member("peer",10))))
        assertEquals(MessageReceipt.READ,messageReceipt(10,"me",listOf(member("peer",10,10))))
    }
    @Test fun groupRequiresAllActiveRecipientsAndExcludesSender() {
        val members=listOf(member("me"),member("a",10,10),member("b",10,9),member("left",status="left"))
        assertEquals(MessageReceipt.DELIVERED,messageReceipt(10,"me",members))
        assertEquals(MessageReceipt.READ,messageReceipt(9,"me",members))
    }
}

