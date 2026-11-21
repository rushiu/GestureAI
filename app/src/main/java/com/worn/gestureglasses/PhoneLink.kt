package com.worn.gestureglasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.util.Log
import org.json.JSONObject

/**
 * Phone <-> Gesture Glasses messages over the glasses' Bluetooth link.
 *
 * The phone sends on the SDK's third-party channel USER_DEFINE_APP (205):
 *   iOS:     MLVisionM6SDKManager.sendBLEMessage(205, message: ["cmd": "show"])
 *   Android: ConnectClientProxy.sendMsg(AppIdEnum.USER_DEFINE_APP, false, payload)
 *
 * Commands (JSON):
 *   {"cmd":"show"}                 bring Gesture Glasses to the front
 *   {"cmd":"pause"} / {"cmd":"resume"}   stop / restart recognition
 *   {"cmd":"text","text":"Hi"}     show a phrase on the display
 *   {"cmd":"ping"}                 replies {"event":"pong"}
 * Events sent back to the phone on the same channel:
 *   {"event":"gesture","label":"Thumb_Up","text":"Yes"}
 *   {"event":"state","paused":false}
 *
 * On the glasses, MicroLumin's connect service (package com.mlaixr.connect) carries the messages.
 * Its client library (com.mlaixr.aidl) isn't in the hackathon kit, so this talks to the service
 * directly, using the same binder interface as the kit's MLScreenCapture app:
 *   MsgChlAidlInterface: 1 = sendMsg(int appId, boolean ack, String json) -> long
 *                        2 = initMsgChl(int appId, MsgRcvListener listener) -> boolean
 *   MsgRcvListener:      1 = onReceiveMsg(long msgId, boolean ack, String json)
 */
object PhoneLink {
    const val APP_ID = 205 // USER_DEFINE_APP
    private const val TAG = "Gesture"
    private const val SERVICE_PACKAGE = "com.mlaixr.connect"
    private const val SERVICE_ACTION = "com.mlaixr.connect.aidl.MsgChlAidlService"
    private const val CHANNEL_DESCRIPTOR = "com.mlaixr.connect.MsgChlAidlInterface"
    private const val LISTENER_DESCRIPTOR = "com.mlaixr.connect.MsgRcvListener"
    private const val TX_SEND_MSG = IBinder.FIRST_CALL_TRANSACTION       // 1
    private const val TX_INIT_MSG_CHL = IBinder.FIRST_CALL_TRANSACTION + 1 // 2

    /** Handles commands that need the screen (pause, resume, text). Set by MainActivity while it exists. */
    @Volatile var commandHandler: ((JSONObject) -> Unit)? = null

    @Volatile private var channel: IBinder? = null
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    @Volatile var status = "phone link off"
        private set

    /** Receives messages from the connect service. */
    private val listener = object : Binder(), IInterface {
        init { attachInterface(this, LISTENER_DESCRIPTOR) }
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(LISTENER_DESCRIPTOR)
            val msgId = data.readLong()
            data.readInt() // ack flag
            val json = data.readString()
            reply?.writeNoException()
            Log.i(TAG, "phone -> glasses #$msgId: $json")
            main.post { handle(json) }
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            channel = service
            try {
                service.linkToDeath({ onLost("connect service died") }, 0)
            } catch (e: Exception) {
                Log.w(TAG, "linkToDeath failed", e)
            }
            val ok = register(service)
            status = if (ok) "phone link on" else "phone link refused"
            Log.i(TAG, "phone link: registered channel $APP_ID -> $ok")
        }

        override fun onServiceDisconnected(name: ComponentName?) = onLost("connect service disconnected")
    }

    /** Connects to the glasses' connect service. Safe to call more than once. */
    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        bind()
    }

    private fun bind() {
        val ctx = appContext ?: return
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        val bound = try {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "phone link: bind failed", e)
            false
        }
        status = if (bound) "phone link connecting" else "phone link unavailable"
        Log.i(TAG, "phone link: bindService -> $bound")
    }

    private fun onLost(why: String) {
        Log.w(TAG, "phone link lost: $why")
        channel = null
        status = "phone link lost"
        // The connect service restarts itself; bind again shortly.
        main.postDelayed({
            appContext?.let { ctx -> runCatching { ctx.unbindService(connection) } }
            bind()
        }, 3_000)
    }

    private fun register(service: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CHANNEL_DESCRIPTOR)
            data.writeInt(APP_ID)
            data.writeStrongBinder(listener)
            service.transact(TX_INIT_MSG_CHL, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            Log.e(TAG, "phone link: initMsgChl failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Sends an event to the phone. Does nothing if the link isn't up. */
    fun send(payload: JSONObject) {
        val service = channel ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CHANNEL_DESCRIPTOR)
            data.writeInt(APP_ID)
            data.writeInt(0) // no ack
            data.writeString(payload.toString())
            service.transact(TX_SEND_MSG, data, reply, 0)
            reply.readException()
            Log.i(TAG, "glasses -> phone: $payload")
        } catch (e: Exception) {
            Log.w(TAG, "phone link: sendMsg failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun handle(json: String?) {
        val msg = try {
            JSONObject(json ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "phone link: not JSON: $json")
            return
        }
        val ctx = appContext ?: return
        when (msg.optString("cmd")) {
            "show" -> AutoStart.bringToFront(ctx, "phone asked")
            "ping" -> send(JSONObject().put("event", "pong").put("onScreen", AutoStart.onScreen))
            "pause", "resume", "text" -> {
                // These need the screen: open it first, then pass the command on.
                if (!AutoStart.onScreen) AutoStart.bringToFront(ctx, "phone sent ${msg.optString("cmd")}")
                main.postDelayed({ commandHandler?.invoke(msg) }, if (AutoStart.onScreen) 0 else 800)
            }
            else -> Log.w(TAG, "phone link: unknown command $msg")
        }
    }
}
