package one.mixin.bot

import com.google.gson.JsonObject
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import one.mixin.bot.Constants.API.CN_URL
import one.mixin.bot.Constants.API.URL
import one.mixin.bot.api.AddressService
import one.mixin.bot.api.AssetService
import one.mixin.bot.api.AttachmentService
import one.mixin.bot.api.ConversationService
import one.mixin.bot.api.ExternalService
import one.mixin.bot.api.MessageService
import one.mixin.bot.api.SnapshotService
import one.mixin.bot.api.UserService
import one.mixin.bot.api.exception.ClientErrorException
import one.mixin.bot.api.exception.ServerErrorException
import one.mixin.bot.extension.HostSelectionInterceptor
import one.mixin.bot.extension.base64Decode
import one.mixin.bot.extension.base64Encode
import one.mixin.bot.extension.isNeedSwitch
import one.mixin.bot.util.getRSAPrivateKeyFromString
import one.mixin.bot.vo.RpcRequest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.create
import java.security.Security
import java.util.Locale
import java.util.concurrent.TimeUnit

class HttpClient private constructor(
    private val clientToken: SessionToken,
    cnServer: Boolean = false,
    debug: Boolean = false,
    autoSwitch: Boolean = false
) {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    private var userSessionToken: SessionToken? = null

    private val ed25519 by lazy { EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519) }
    fun setUserToken(userSessionToken: SessionToken?) {
        this.userSessionToken = userSessionToken
    }

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
        if (debug) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY
            builder.addNetworkInterceptor(logging)
        }
        builder.connectTimeout(10, TimeUnit.SECONDS)
        builder.writeTimeout(10, TimeUnit.SECONDS)
        builder.readTimeout(10, TimeUnit.SECONDS)
        builder.pingInterval(15, TimeUnit.SECONDS)
        builder.retryOnConnectionFailure(false)
        builder.addInterceptor(HostSelectionInterceptor.get(if (cnServer) { CN_URL } else { URL }))
        builder.addInterceptor(
            Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", Constants.UA)
                    .addHeader("Accept-Language", Locale.getDefault().language)
                    .addHeader(
                        "Authorization",
                        "Bearer " +
                            (userSessionToken ?: clientToken).let { token ->
                                signToken(
                                    token.userId,
                                    token.sessionId,
                                    chain.request(),
                                    if (token is SessionToken.RSA) {
                                        token.privateKey
                                    } else {
                                        val seed = (token as SessionToken.EdDSA).seed
                                        val privateSpec = EdDSAPrivateKeySpec(
                                            seed.base64Decode(),
                                            ed25519
                                        )
                                        EdDSAPrivateKey(privateSpec)
                                    }
                                )
                            }
                    ).build()

                val response = try {
                    chain.proceed(request)
                } catch (e: Exception) {
                    throw e.apply {
                        if (autoSwitch && e.isNeedSwitch()) {
                            HostSelectionInterceptor.get().switch(request)
                        } else {
                            if (e.message?.contains("502") == true) {
                                throw ServerErrorException(502)
                            } else throw e
                        }
                    }
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    if (code in 500..599) {
                        throw ServerErrorException(code)
                    } else if (code in 400..499) {
                        throw ClientErrorException(code)
                    }
                }
                response
            }
        )
        builder.build()
    }

    private val retrofit: Retrofit by lazy {
        val builder = Retrofit.Builder()
            .baseUrl(if (cnServer) { CN_URL } else { URL })
            .addCallAdapterFactory(CoroutineCallAdapterFactory())
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
        builder.build()
    }

    val userService: UserService by lazy {
        retrofit.create(UserService::class.java)
    }

    val assetService: AssetService by lazy {
        retrofit.create(AssetService::class.java)
    }

    val addressService: AddressService by lazy {
        retrofit.create(AddressService::class.java)
    }

    val snapshotService: SnapshotService by lazy {
        retrofit.create(SnapshotService::class.java)
    }

    val messageService: MessageService by lazy {
        retrofit.create(MessageService::class.java)
    }

    val conversationService: ConversationService by lazy {
        retrofit.create(ConversationService::class.java)
    }

    val attachmentService: AttachmentService by lazy {
        retrofit.create(AttachmentService::class.java)
    }

    val externalService: ExternalService by lazy {
        object : ExternalService {
            override fun getUtxoCall(hash: String, index: Int): Call<JsonObject> {
                val list = mutableListOf<Any>()
                list.add(hash)
                list.add(index)
                return userService.mixinMainnetRPCCall(RpcRequest("getutxo", list))
            }

            override suspend fun getUtxo(hash: String, index: Int): JsonObject {
                val list = mutableListOf<Any>()
                list.add(hash)
                list.add(index)
                return userService.mixinMainnetRPC(RpcRequest("getutxo", list))
            }
        }
    }

    class Builder {
        private lateinit var clientToken: SessionToken
        private var cnServer: Boolean = false
        private var debug: Boolean = false
        private var autoSwitch: Boolean = false

        fun configEdDSA(
            userId: String,
            sessionId: String,
            privateKey: EdDSAPrivateKey
        ): Builder {
            clientToken = SessionToken.EdDSA(userId, sessionId, privateKey.seed.base64Encode())
            return this
        }

        fun configRSA(
            userId: String,
            sessionId: String,
            privateKey: String
        ): Builder {
            val key = getRSAPrivateKeyFromString(privateKey)
            clientToken =
                SessionToken.RSA(userId, sessionId, key)
            return this
        }

        fun useCNServer(): Builder {
            cnServer = true
            return this
        }

        fun enableDebug(): Builder {
            debug = true
            return this
        }

        fun enableAutoSwitch(): Builder {
            debug = true
            return this
        }

        fun build(): HttpClient {
            return HttpClient(clientToken, cnServer, debug, autoSwitch)
        }
    }
}
