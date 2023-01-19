package io.github.nfdz.cryptool.shared.core.realm

import io.github.aakira.napier.Napier
import io.github.nfdz.cryptool.shared.encryption.repository.realm.EncryptionRealm
import io.github.nfdz.cryptool.shared.message.repository.realm.MessageRealm
import io.github.nfdz.cryptool.shared.password.repository.realm.PasswordRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import io.realm.kotlin.ext.query

interface RealmGateway {
    fun open(key: ByteArray)
    fun tearDown()
    val instance: Realm
    fun executeOnOpen(callback: suspend (Realm) -> Unit)

    companion object {
        const val keyHashLength = 64
    }
}

class RealmGatewayImpl : RealmGateway {

    companion object {
        private val schema = setOf(
            PasswordRealm::class,
            EncryptionRealm::class,
            MessageRealm::class,
        )

        private const val name = "cryptool.realm"
    }

    private var onOpenCallback: suspend (Realm) -> Unit = ::purgeOrphanMessages
    private var _instance: Realm? = null
    override val instance: Realm
        get() = _instance ?: throw IllegalStateException("Realm instance is not ready")

    override fun executeOnOpen(callback: suspend (Realm) -> Unit) {
        onOpenCallback = callback
    }

    override fun open(key: ByteArray) {
        if (_instance != null) {
            Napier.d("Realm instance is already open")
            return
        }
        _instance = createInstance(key).also {
            MainScope().launch(Dispatchers.Default) {
                onOpenCallback(it)
                onOpenCallback = {}
            }
        }
    }

    private suspend fun purgeOrphanMessages(realm: Realm) {
        runCatching {
            realm.write {
                val encryptionIds = query<EncryptionRealm>().find().map { it.id }
                val orphanMessages = if (encryptionIds.isEmpty()) {
                    query<MessageRealm>().find()
                } else {
                    val encryptionIdsString = encryptionIds.joinToString(",") { "'$it'" }
                    query<MessageRealm>("NOT encryptionId IN {$encryptionIdsString}").find()
                }
                if (orphanMessages.isEmpty()) return@write
                val count = orphanMessages.size
                delete(orphanMessages)
                Napier.i(tag = "RealmGateway", message = "Purged orphan messages: $count")
            }
        }.onFailure {
            Napier.e(tag = "RealmGateway", message = "Purge orphan messages error: ${it.message}", throwable = it)
        }
    }

    override fun tearDown() {
        runCatching {
            _instance?.let {
                it.close()
                Realm.deleteRealm(it.configuration)
            }
            Realm.deleteRealm(RealmConfiguration.Builder(schema).name(name).build())
        }.onFailure {
            Napier.e(tag = "RealmGateway", message = "Delete error: ${it.message}", throwable = it)
        }
        _instance = null
    }

    private fun createInstance(key: ByteArray): Realm {
        val config = RealmConfiguration.Builder(schema).name(name).encryptionKey(key).build()
        return Realm.open(config)
    }

}