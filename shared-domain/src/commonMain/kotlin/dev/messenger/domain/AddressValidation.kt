package dev.messenger.domain

/** Syntax only: DNS/TLS/server availability and admission are checked separately. */
object AddressValidation {
    fun normalizeHost(input:String):String? {
        val value=input.trim().lowercase()
        if(value.isEmpty()) return null
        if(value.startsWith('[') || value.endsWith(']')) {
            if(!value.startsWith('[') || !value.endsWith(']')) return null
            return value.substring(1,value.length-1).takeIf(::ipv6)
        }
        if(':' in value) return value.takeIf(::ipv6)
        if(value.all { it in '0'..'9' || it=='.' }) return value.takeIf(::ipv4)
        return value.takeIf { dns(it,false) }
    }
    fun port(input:String):Int? = input.trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 1..65535 }
    private fun ipv4(value:String):Boolean {
        val parts=value.split('.')
        return parts.size==4 && parts.all { part ->
            part.length in 1..3 && part.all { it in '0'..'9' } &&
                (part.length==1 || part.first()!='0') && (part.toIntOrNull() ?: -1) in 0..255
        }
    }
    private fun ipv6(value:String):Boolean {
        if(value.isEmpty() || '%' in value) return false
        val halves=value.split("::")
        if(halves.size>2) return false
        val groups=halves.flatMap { if(it.isEmpty()) emptyList() else it.split(':') }
        if(groups.any { it.isEmpty() }) return false
        var count=0
        for((index,group) in groups.withIndex()) {
            if('.' in group) {
                if(index!=groups.lastIndex || !value.endsWith(group) || !ipv4(group)) return false
                count+=2
            } else {
                if(group.length !in 1..4 || !group.all { it in '0'..'9' || it in 'a'..'f' }) return false
                count++
            }
        }
        return if(halves.size==2) count<8 else count==8
    }
    private fun dns(value:String,unicode:Boolean):Boolean {
        if(value.length !in 1..253) return false
        return value.split('.').all { label ->
            fun letterOrDigit(c:Char)=if(unicode) c.isLetterOrDigit() else c in 'a'..'z' || c in '0'..'9'
            label.length in 1..63 && letterOrDigit(label.first()) && letterOrDigit(label.last()) &&
                label.all { letterOrDigit(it) || it=='-' }
        }
    }
    fun normalizeEmail(input:String):String? {
        val value=input.trim()
        if(value.encodeToByteArray().size !in 3..254 || value.count { it=='@' }!=1) return null
        val local=value.substringBefore('@')
        val domain=value.substringAfter('@').lowercase()
        if(local.encodeToByteArray().size !in 1..64 || local.startsWith('.') || local.endsWith('.') || ".." in local) return null
        if(!local.all { it.isLetterOrDigit() || it in "!#\$%&'*+-/=?^_`{|}~." }) return null
        if(!dns(domain,true) || '.' !in domain || !domain.substringAfterLast('.').any { it.isLetter() }) return null
        return "$local@$domain"
    }
}