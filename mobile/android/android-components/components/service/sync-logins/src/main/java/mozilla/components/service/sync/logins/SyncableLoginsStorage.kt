/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.sync.logins

import android.content.Context
import androidx.annotation.GuardedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mozilla.appservices.logins.DatabaseLoginsStorage
import mozilla.components.concept.storage.EncryptedLogin
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import mozilla.components.concept.storage.LoginsStorage
import mozilla.components.concept.sync.SyncableStore
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.utils.logElapsedTime
import java.io.Closeable

// Current database
const val DB_NAME = "logins2.sqlite"

// Name of our preferences file
const val PREFS_NAME = "logins"

/**
 * The telemetry ping from a successful sync
 */
typealias SyncTelemetryPing = mozilla.appservices.sync15.SyncTelemetryPing

/**
 * The base class of all errors emitted by logins storage.
 *
 * Concrete instances of this class are thrown for operations which are
 * not expected to be handled in a meaningful way by the application.
 *
 * For example, caught Rust panics, SQL errors, failure to generate secure
 * random numbers, etc. are all examples of things which will result in a
 * concrete `LoginsApiException`.
 */
typealias LoginsApiException = mozilla.appservices.logins.LoginsApiException

/**
 * This indicates that the authentication information (e.g. the [SyncUnlockInfo])
 * provided to [AsyncLoginsStorage.sync] is invalid. This often indicates that it's
 * stale and should be refreshed with FxA (however, care should be taken not to
 * get into a loop refreshing this information).
 */
typealias SyncAuthInvalidException = mozilla.appservices.logins.LoginsApiException.SyncAuthInvalid

/**
 * This is thrown if `update()` is performed with a record whose GUID
 * does not exist.
 */
typealias NoSuchRecordException = mozilla.appservices.logins.LoginsApiException.NoSuchRecord

/**
 * This is thrown on attempts to insert or update a record so that it
 * is no longer valid, where "invalid" is defined as such:
 *
 * - A record with a blank `password` is invalid.
 * - A record with a blank `hostname` is invalid.
 * - A record that doesn't have a `formSubmitURL` nor a `httpRealm` is invalid.
 * - A record that has both a `formSubmitURL` and a `httpRealm` is invalid.
 */
typealias InvalidRecordException = mozilla.appservices.logins.LoginsApiException.InvalidRecord

/**
 * Error encrypting/decrypting logins data
 */
typealias IncorrectKey = mozilla.appservices.logins.LoginsApiException.IncorrectKey

/**
 * Implements [LoginsStorage] and [SyncableStore] using the application-services logins library.
 *
 * Synchronization is handled via the SyncManager by calling [registerWithSyncManager]
 */
class SyncableLoginsStorage(
    private val context: Context,
    private val securePrefs: Lazy<SecureAbove22Preferences>,
) : LoginsStorage, SyncableStore, AutoCloseable {
    private val logger = Logger("SyncableLoginsStorage")
    private val coroutineContext by lazy { Dispatchers.IO }
    val crypto by lazy { LoginsCrypto(context, securePrefs.value, this) }

    internal val conn by lazy {
        LoginStorageConnection.init(dbPath = context.getDatabasePath(DB_NAME).absolutePath)
        LoginStorageConnection
    }

    /**
     * "Warms up" this storage layer by establishing the database connection.
     */
    suspend fun warmUp() = withContext(coroutineContext) {
        logElapsedTime(logger, "Warming up storage") { conn }
        Unit
    }

    /**
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(LoginsApiException::class)
    override suspend fun wipeLocal() = withContext(coroutineContext) {
        conn.getStorage().wipeLocal()
    }

    /**
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(LoginsApiException::class)
    override suspend fun delete(guid: String): Boolean = withContext(coroutineContext) {
        conn.getStorage().delete(guid)
    }

    /**
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(LoginsApiException::class)
    override suspend fun get(guid: String): Login? = withContext(coroutineContext) {
        conn.getStorage().get(guid)?.toEncryptedLogin()?.let { crypto.decryptLogin(it) }
    }

    /**
     * @throws [NoSuchRecordException] if the login does not exist.
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(NoSuchRecordException::class, LoginsApiException::class)
    override suspend fun touch(guid: String) = withContext(coroutineContext) {
        conn.getStorage().touch(guid)
    }

    /**
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(LoginsApiException::class)
    override suspend fun list(): List<Login> = withContext(coroutineContext) {
        val key = crypto.getOrGenerateKey()
        conn.getStorage().list().map { crypto.decryptLogin(it.toEncryptedLogin(), key) }
    }

    /**
     * @throws [InvalidRecordException] if the record is invalid.
     * @throws [IncorrectKey] if the encryption key can't decrypt the login
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(IncorrectKey::class, InvalidRecordException::class, LoginsApiException::class)
    override suspend fun add(entry: LoginEntry) = withContext(coroutineContext) {
        conn.getStorage().add(entry.toLoginEntry(), crypto.getOrGenerateKey().key).toEncryptedLogin()
    }

    /**
     * @throws [NoSuchRecordException] if the login does not exist.
     * @throws [IncorrectKey] if the encryption key can't decrypt the login
     * @throws [InvalidRecordException] if the update would create an invalid record.
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(
        IncorrectKey::class,
        NoSuchRecordException::class,
        InvalidRecordException::class,
        LoginsApiException::class,
    )
    override suspend fun update(guid: String, entry: LoginEntry) = withContext(coroutineContext) {
        conn.getStorage().update(guid, entry.toLoginEntry(), crypto.getOrGenerateKey().key).toEncryptedLogin()
    }

    /**
     * @throws [InvalidRecordException] if the update would create an invalid record.
     * @throws [IncorrectKey] if the encryption key can't decrypt the login
     * @throws [LoginsApiException] if the storage is locked, and on unexpected
     *              errors (IO failure, rust panics, etc)
     */
    @Throws(IncorrectKey::class, InvalidRecordException::class, LoginsApiException::class)
    override suspend fun addOrUpdate(entry: LoginEntry) = withContext(coroutineContext) {
        conn.getStorage().addOrUpdate(entry.toLoginEntry(), crypto.getOrGenerateKey().key).toEncryptedLogin()
    }

    override fun registerWithSyncManager() {
        conn.getStorage().registerWithSyncManager()
    }

    /**
     * @throws [LoginsApiException] On unexpected errors (IO failure, rust panics, etc)
     */
    @Throws(LoginsApiException::class)
    override suspend fun getByBaseDomain(origin: String): List<Login> = withContext(coroutineContext) {
        val key = crypto.getOrGenerateKey()
        conn.getStorage().getByBaseDomain(origin).map { crypto.decryptLogin(it.toEncryptedLogin(), key) }
    }

    /**
     * @throws [IncorrectKey] if the encryption key can't decrypt the login
     * @throws [LoginsApiException] On unexpected errors (IO failure, rust panics, etc)
     */
    @Throws(LoginsApiException::class)
    override suspend fun findLoginToUpdate(entry: LoginEntry): Login? = withContext(coroutineContext) {
        conn.getStorage().findLoginToUpdate(entry.toLoginEntry(), crypto.getOrGenerateKey().key)?.toLogin()
    }

    /**
     * @throws [IncorrectKey] if the encryption key can't decrypt the login
     */
    override suspend fun decryptLogin(login: EncryptedLogin) = crypto.decryptLogin(login)

    override fun close() {
        coroutineContext.cancel()
        conn.close()
    }
}

/**
 * A singleton wrapping a [LoginsStorage] connection.
 */
internal object LoginStorageConnection : Closeable {
    @GuardedBy("this")
    private var storage: DatabaseLoginsStorage? = null

    internal fun init(dbPath: String = DB_NAME) = synchronized(this) {
        if (storage == null) {
            storage = DatabaseLoginsStorage(dbPath)
        }
        storage
    }

    internal fun getStorage(): DatabaseLoginsStorage = synchronized(this) {
        check(storage != null) { "must call init first" }
        return storage!!
    }

    override fun close() = synchronized(this) {
        check(storage != null) { "must call init first" }
        storage!!.close()
        storage = null
    }
}
