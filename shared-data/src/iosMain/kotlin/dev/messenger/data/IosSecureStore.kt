@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package dev.messenger.data

import dev.messenger.data.session.SecureStore
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/** One atomically replaced Keychain item. Credentials never enter NSUserDefaults or application files. */
class IosSecureStore : SecureStore {
    private val lock = Mutex()
    init {
        // Keychain can survive uninstall; a fresh installation must get a new device identity.
        val defaults = NSUserDefaults.standardUserDefaults
        if (!defaults.boolForKey("messenger.install.v1")) {
            query(emptyMap()) { attributes ->
                val status = SecItemDelete(attributes)
                check(status == errSecSuccess || status == errSecItemNotFound)
            }
            defaults.setBool(true, forKey = "messenger.install.v1")
        }
    }
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            query(mapOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) { attributes ->
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(attributes, result.ptr)
                if (status == errSecItemNotFound) return@query null
                check(status == errSecSuccess) { "Keychain read failed" }
                val data = CFBridgingRelease(result.value) as? NSData ?: error("Invalid Keychain value")
                NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String ?: error("Invalid Keychain encoding")
            }
        }
    }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            @Suppress("CAST_NEVER_SUCCEEDS")
            val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            val retained = CFBridgingRetain(requireNotNull(data))
            try {
                query(emptyMap()) { attributes ->
                    val changes = dictionary(mapOf(kSecValueData to retained,
                        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
                    try {
                        val status = SecItemUpdate(attributes, changes)
                        if (status == errSecItemNotFound) {
                            query(mapOf(kSecValueData to retained,
                                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)) {
                                check(SecItemAdd(it, null) == errSecSuccess) { "Keychain add failed" }
                            }
                        } else check(status == errSecSuccess) { "Keychain update failed" }
                    } finally { changes?.let { CFRelease(it) } }
                }
            } finally { retained?.let { CFRelease(it) } }
        }
    }

    private fun <T> query(extra: Map<CFStringRef?, CFTypeRef?>, operation: MemScope.(CFDictionaryRef?) -> T): T = memScoped {
        val service = CFBridgingRetain("dev.messenger.session")
        val account = CFBridgingRetain("active.v1")
        val values: Map<CFStringRef?, CFTypeRef?> = mapOf(kSecClass to kSecClassGenericPassword,
            kSecAttrService to service, kSecAttrAccount to account) + extra
        val attributes = dictionary(values)
        try { operation(attributes) }
        finally { attributes?.let { CFRelease(it) }; service?.let { CFRelease(it) }; account?.let { CFRelease(it) } }
    }
    private fun MemScope.dictionary(values: Map<CFStringRef?, CFTypeRef?>): CFDictionaryRef? =
        CFDictionaryCreate(kCFAllocatorDefault, allocArrayOf(*values.keys.toTypedArray()).reinterpret(),
            allocArrayOf(*values.values.toTypedArray()).reinterpret(), values.size.convert(), null, null)
}
