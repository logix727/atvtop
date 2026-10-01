package com.atvpm.tvcontrol.core.backend

import android.content.Context
import android.util.Base64
import com.atvpm.tvcontrol.core.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Pure-Kotlin built-in ADB TCP protocol client (`127.0.0.1:5555`).
 *
 * This is how Play Store Android TV tools ("ADB TV", "TDUK App Killer",
 * "tvQuickActions") work without a PC, without root, and without Shizuku:
 *
 * When the user enables Developer Options -> USB/Network Debugging on their TV,
 * `adbd` listens on loopback `127.0.0.1:5555`. This client connects directly
 * from the app to `127.0.0.1:5555`, performs the ADB RSA-2048 `AUTH` handshake
 * (which triggers the native "Allow USB debugging?" prompt right on the TV
 * screen), and executes commands via the `shell,v2` protocol in a single run.
 */
class LocalAdbClient(
    context: Context,
    val host: String = "127.0.0.1",
    val port: Int = 5555,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val keyPair: KeyPair by lazy { loadOrGenerateKeyPair() }

    /** Quick TCP probe to check whether `adbd` is listening on `127.0.0.1:5555`. */
    fun isPortListening(timeoutMs: Int = 350): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /**
     * Executes [command] strictly once over the ADB TCP protocol and returns
     * stdout, stderr, and exitCode via `shell,v2,raw:` (with fallback to `shell:`).
     */
    suspend fun exec(command: String, timeoutMs: Long = 20_000L): ShellResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    Socket().use { socket ->
                        val soTimeout = timeoutMs.coerceIn(2_000L, 60_000L).toInt()
                        socket.soTimeout = soTimeout
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(host, port), 2_000)

                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()

                        if (!performHandshake(input, output)) {
                            return@withLock ShellResult.unavailable(command).copy(
                                stderr = "ADB authorization pending or declined on TV screen",
                            )
                        }

                        executeShellV2(input, output, command)
                    }
                }.getOrElse { err ->
                    ShellResult(command = command, failure = err)
                }
            }
        }

    private fun performHandshake(input: DataInputStream, output: OutputStream): Boolean {
        val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(output, CMD_CNXN, ADB_VERSION, MAX_DATA, banner)

        var sentSignature = false
        var sentPublicKey = false

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                CMD_CNXN -> return true
                CMD_AUTH -> {
                    if (packet.arg0 != ADB_AUTH_TOKEN) return false
                    if (!sentSignature) {
                        sentSignature = true
                        val sig = signAdbToken(packet.payload)
                        sendPacket(output, CMD_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                    } else if (!sentPublicKey) {
                        sentPublicKey = true
                        val pubKeyPayload = encodeAndroidRsaPublicKey(
                            keyPair.public as RSAPublicKey,
                        )
                        sendPacket(output, CMD_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubKeyPayload)
                    } else {
                        return false
                    }
                }
                else -> return false
            }
        }
    }

    /**
     * Uses `shell,v2,raw:<command>` so `adbd` natively demultiplexes stdout (id 1),
     * stderr (id 2), and exit code (id 3) from a single command execution.
     */
    private fun executeShellV2(
        input: DataInputStream,
        output: OutputStream,
        command: String,
    ): ShellResult {
        val localId = 1
        val openDest = "shell,v2,raw:$command\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(output, CMD_OPEN, localId, 0, openDest)

        var remoteId = 0
        val v2Stream = ByteArrayOutputStream()

        while (true) {
            val pkt = readPacket(input)
            when (pkt.command) {
                CMD_OKAY -> {
                    if (remoteId == 0) remoteId = pkt.arg0
                }
                CMD_WRTE -> {
                    if (remoteId == 0) remoteId = pkt.arg0
                    sendPacket(output, CMD_OKAY, localId, remoteId, ByteArray(0))
                    v2Stream.write(pkt.payload)
                }
                CMD_CLSE -> {
                    sendPacket(output, CMD_CLSE, localId, remoteId, ByteArray(0))
                    break
                }
            }
        }

        return parseShellV2Payload(command, v2Stream.toByteArray())
    }

    private fun parseShellV2Payload(command: String, raw: ByteArray): ShellResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode = 0
        var offset = 0

        // Verify if stream looks like valid shell,v2 packets; otherwise treat as raw stdout
        while (offset + 5 <= raw.size) {
            val id = raw[offset].toInt() and 0xFF
            val len = ByteBuffer.wrap(raw, offset + 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            if (len < 0 || offset + 5 + len > raw.size || id !in 0..3) {
                // Fallback if legacy shell stream returned
                return ShellResult(
                    command = command,
                    exitCode = 0,
                    stdout = String(raw, Charsets.UTF_8),
                )
            }
            offset += 5
            when (id) {
                1 -> stdout.write(raw, offset, len)
                2 -> stderr.write(raw, offset, len)
                3 -> if (len >= 1) exitCode = raw[offset].toInt() and 0xFF
            }
            offset += len
        }

        return ShellResult(
            command = command,
            exitCode = exitCode,
            stdout = stdout.toString(Charsets.UTF_8.name()),
            stderr = stderr.toString(Charsets.UTF_8.name()),
        )
    }

    private fun sendPacket(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
    ) {
        var checksum = 0
        for (b in payload) {
            checksum += (b.toInt() and 0xFF)
        }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(checksum)
            putInt(command xor -1)
        }.array()
        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readPacket(input: DataInputStream): AdbPacket {
        val headerBytes = ByteArray(24)
        input.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        header.int // checksum
        header.int // magic
        val payload = if (length > 0) {
            ByteArray(length).also { input.readFully(it) }
        } else {
            ByteArray(0)
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    /**
     * Signs the 20-byte ADB token with PKCS#1 v1.5 SHA-1 DigestInfo padding
     * expected by `adbd`.
     */
    private fun signAdbToken(token: ByteArray): ByteArray {
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(digestInfo)
    }

    /**
     * Encodes an RSA-2048 public key into Android's 524-byte `RSAPublicKey` C struct,
     * Base64-encoded and suffixed with `" tvcontrol@androidtv\u0000"`.
     */
    private fun encodeAndroidRsaPublicKey(pub: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(pub.modulus)
        val n0inv = pub.modulus.mod(r32).modInverse(r32).negate().mod(r32)

        val nWords = IntArray(64)
        val rrWords = IntArray(64)
        var remN = pub.modulus
        var remRR = rr
        for (i in 0 until 64) {
            val divRemN = remN.divideAndRemainder(r32)
            remN = divRemN[0]
            nWords[i] = divRemN[1].toInt()

            val divRemRR = remRR.divideAndRemainder(r32)
            remRR = divRemRR[0]
            rrWords[i] = divRemRR[1].toInt()
        }

        val struct = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(64) // len in 32-bit words (2048 bits)
            putInt(n0inv.toInt())
            for (w in nWords) putInt(w)
            for (w in rrWords) putInt(w)
            putInt(pub.publicExponent.toInt())
        }.array()

        val b64 = Base64.encodeToString(struct, Base64.NO_WRAP)
        return "$b64 tvcontrol@androidtv\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        val privFile = File(appContext.filesDir, "adb_rsa.pk8")
        val pubFile = File(appContext.filesDir, "adb_rsa.pub")
        if (privFile.exists() && pubFile.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        runCatching {
            privFile.writeBytes(pair.private.encoded)
            pubFile.writeBytes(pair.public.encoded)
        }
        return pair
    }

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    companion object {
        private const val ADB_VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024

        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_WRTE = 0x45545257
        private const val CMD_CLSE = 0x45534c43

        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
