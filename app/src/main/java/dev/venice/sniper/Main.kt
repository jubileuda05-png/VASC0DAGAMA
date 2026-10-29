package dev.venice.sniper

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/* ---------------- log ---------------- */

object Bus {
    var sink: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun log(m: String) {
        val line = fmt.format(Date()) + "  " + m
        main.post { sink?.invoke(line) }
    }
}

/* ---------------- prefs ---------------- */

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("sniper", Context.MODE_PRIVATE)
    fun delay(c: Context) = sp(c).getString("delay", "0")?.toLongOrNull() ?: 0L
    fun setDelay(c: Context, v: Long) = sp(c).edit().putString("delay", v.toString()).apply()
    fun useAlts(c: Context) = sp(c).getBoolean("useAlts", true)
    fun setUseAlts(c: Context, v: Boolean) = sp(c).edit().putBoolean("useAlts", v).apply()
    fun ignoreOwn(c: Context) = sp(c).getBoolean("ignoreOwn", true)
    fun setIgnoreOwn(c: Context, v: Boolean) = sp(c).edit().putBoolean("ignoreOwn", v).apply()
    fun ignoreBots(c: Context) = sp(c).getBoolean("ignoreBots", false)
    fun setIgnoreBots(c: Context, v: Boolean) = sp(c).edit().putBoolean("ignoreBots", v).apply()
    fun sound(c: Context) = sp(c).getBoolean("sound", true)
    fun setSound(c: Context, v: Boolean) = sp(c).edit().putBoolean("sound", v).apply()
    fun autoStart(c: Context) = sp(c).getBoolean("autoStart", true)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoStart", v).apply()
    fun webhook(c: Context) = sp(c).getString("webhook", "") ?: ""
    fun setWebhook(c: Context, v: String) = sp(c).edit().putString("webhook", v).apply()
}

/* ---------------- contas ---------------- */

data class Account(val id: String, val token: String, val username: String, val main: Boolean)

object Accounts {
    private fun sp(c: Context) = c.getSharedPreferences("sniper", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Account> {
        val out = mutableListOf<Account>()
        runCatching {
            val arr = JSONArray(sp(c).getString("accounts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Account(o.getString("id"), o.getString("token"), o.optString("username"), o.optBoolean("main")))
            }
        }
        return out
    }

    fun save(c: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("token", it.token)
                .put("username", it.username).put("main", it.main))
        }
        sp(c).edit().putString("accounts", arr.toString()).apply()
    }

    fun add(c: Context, a: Account) {
        val l = load(c).filterNot { it.id == a.id || it.main == a.main }.toMutableList()
        l.add(a)
        save(c, l)
    }

    fun remove(c: Context, id: String) = save(c, load(c).filterNot { it.id == id })
    fun main(c: Context) = load(c).firstOrNull { it.main }
    fun alts(c: Context) = load(c).filterNot { it.main }
}

/* ---------------- http ---------------- */

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    val api: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val ws: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
}

/* ---------------- resgate ---------------- */

object Redeemer {
    data class Result(val ok: Boolean, val message: String, val captcha: Boolean = false)

    fun redeem(token: String, code: String, channel: String?): Result {
        val body = JSONObject()
        if (!channel.isNullOrEmpty()) body.put("channel_id", channel)
        val req = Request.Builder()
            .url("https://discord.com/api/v9/entitlements/gift-codes/" + code + "/redeem")
            .header("Authorization", token).header("User-Agent", Http.UA)
            .header("X-Discord-Locale", "pt-BR")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            Http.api.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (r.isSuccessful) Result(true, "resgatado")
                else {
                    val j = runCatching { JSONObject(t) }.getOrNull()
                    val m = j?.optString("message").takeUnless { it.isNullOrEmpty() } ?: ("HTTP " + r.code)
                    val cap = j?.has("captcha_key") == true || !j?.optString("captcha_sitekey").isNullOrEmpty()
                    Result(false, m, cap)
                }
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "erro de rede")
        }
    }

    fun whoami(token: String): String? {
        val req = Request.Builder().url("https://discord.com/api/v9/users/@me")
            .header("Authorization", token).header("User-Agent", Http.UA).build()
        return try {
            Http.api.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val j = JSONObject(r.body?.string().orEmpty())
                j.optString("username") + "|" + j.optString("id")
            }
        } catch (e: Exception) { null }
    }
}

/* ---------------- gateway ---------------- */

class Gateway(private val token: String, private val l: Listener) {

    interface Listener {
        fun onReady(id: String)
        fun onGuild()
        fun onMessage(m: JSONObject)
        fun onDead(r: String)
    }

    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var sock: WebSocket? = null
    private var beat: ScheduledFuture<*>? = null
    private var seq: Int? = null
    private var session: String? = null
    private var resumeNext = false
    private var retrying = false
    private var subs = 0
    @Volatile private var dead = false

    fun start() = connect()

    fun stop() {
        dead = true
        beat?.cancel()
        runCatching { sock?.close(1000, "stop") }
        timer.shutdownNow()
    }

    private fun connect() {
        if (dead) return
        val req = Request.Builder().url("wss://gateway.discord.gg/?v=9&encoding=json")
            .header("User-Agent", Http.UA).build()
        sock = Http.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(w: WebSocket, text: String) = handle(text)
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { if (!dead) retry() }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                if (dead) return
                if (code == 4004) { dead = true; l.onDead("token inválido") } else retry()
            }
        })
    }

    private fun retry() {
        if (dead || retrying) return
        retrying = true
        beat?.cancel()
        resumeNext = session != null
        timer.schedule({ retrying = false; if (!dead) connect() }, 2500, TimeUnit.MILLISECONDS)
    }

    private fun send(o: JSONObject) { sock?.send(o.toString()) }

    private fun handle(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (!j.isNull("s")) seq = j.optInt("s")
        when (j.optInt("op")) {
            10 -> {
                val iv = j.getJSONObject("d").optLong("heartbeat_interval", 41250)
                beat?.cancel()
                beat = timer.scheduleAtFixedRate({ beat() }, iv, iv, TimeUnit.MILLISECONDS)
                if (resumeNext && session != null) resume() else identify()
            }
            11 -> Unit
            1 -> beat()
            7 -> { runCatching { sock?.close(4000, "rc") }; retry() }
            9 -> { session = null; seq = null; retry() }
            0 -> dispatch(j)
        }
    }

    private fun beat() = send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL))

    private fun identify() {
        val p = JSONObject().put("os", "Android").put("browser", "Chrome").put("device", "")
            .put("system_locale", "pt-BR").put("browser_user_agent", Http.UA)
            .put("browser_version", "126.0.0.0").put("os_version", "14").put("referrer", "")
            .put("referring_domain", "").put("release_channel", "stable").put("client_build_number", 999999)
        val d = JSONObject().put("token", token).put("capabilities", 125).put("compress", false)
            .put("properties", p)
            .put("presence", JSONObject().put("status", "online").put("since", 0)
                .put("activities", JSONArray()).put("afk", false))
        send(JSONObject().put("op", 2).put("d", d))
    }

    private fun resume() {
        send(JSONObject().put("op", 6).put("d", JSONObject()
            .put("token", token).put("session_id", session).put("seq", seq ?: 0)))
    }

    private fun dispatch(j: JSONObject) {
        val t = j.optString("t")
        val d = j.optJSONObject("d") ?: return
        when (t) {
            "READY" -> {
                session = d.optString("session_id")
                l.onReady(d.optJSONObject("user")?.optString("id").orEmpty())
            }
            "GUILD_CREATE" -> { subscribe(d); l.onGuild() }
            "MESSAGE_CREATE" -> l.onMessage(d)
        }
    }

    private fun subscribe(g: JSONObject) {
        val gid = g.optString("id")
        if (gid.isEmpty()) return
        val ch = JSONObject()
        val arr = g.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val type = c.optInt("type", -1)
            if (type != 0 && type != 5) continue
            ch.put(c.getString("id"), JSONArray().put(JSONArray().put(0).put(99)))
        }
        if (ch.length() == 0) return
        val d = JSONObject().put("guild_id", gid).put("typing", true)
            .put("threads", true).put("activities", true).put("channels", ch)
        val wait = 120L * (subs++ % 50)
        timer.schedule({ send(JSONObject().put("op", 14).put("d", d)) }, wait, TimeUnit.MILLISECONDS)
    }
}

/* ---------------- notificações ---------------- */

object Notifier {
    const val CH_SVC = "svc"
    const val CH_ALERT = "alert"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Sniper ativo", NotificationManager.IMPORTANCE_LOW))
        val a = NotificationChannel(CH_ALERT, "Nitro resgatado", NotificationManager.IMPORTANCE_HIGH)
        a.enableVibration(true)
        a.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        nm.createNotificationChannel(a)
    }

    fun svc(c: Context, text: String): Notification {
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(c, CH_SVC)
            .setContentTitle("Nitro Sniper ativo").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi).setOngoing(true).build()
    }

    fun updateSvc(c: Context, text: String) {
        runCatching { c.getSystemService(NotificationManager::class.java).notify(1001, svc(c, text)) }
    }

    fun success(c: Context, code: String, acc: String) {
        val n = Notification.Builder(c, CH_ALERT)
            .setContentTitle("Nitro resgatado").setContentText(acc + " • " + code)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true).build()
        if (Prefs.sound(c)) {
            n.sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            n.vibrate = longArrayOf(0, 200, 120, 200)
        }
        c.getSystemService(NotificationManager::class.java).notify(code.hashCode(), n)
    }

    fun hook(c: Context, title: String, color: Int, fields: List<Pair<String, String>>) {
        val url = Prefs.webhook(c).trim()
        if (url.isEmpty()) return
        val arr = JSONArray()
        fields.forEach { arr.put(JSONObject().put("name", it.first).put("value", it.second).put("inline", false)) }
        val body = JSONObject().put("username", "Nitro Sniper")
            .put("embeds", JSONArray().put(JSONObject().put("title", title).put("color", color)
                .put("fields", arr).put("timestamp", Instant.now().toString())
                .put("footer", JSONObject().put("text", "Nitro Sniper Mobile"))))
            .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        val sep = if (url.contains("?")) "&" else "?"
        val req = Request.Builder().url(url + sep + "wait=true").header("User-Agent", Http.UA)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Thread { runCatching { Http.api.newCall(req).execute().close() } }.start()
    }
}

/* ---------------- serviço ---------------- */

class SniperService : Service() {

    companion object {
        const val START = "start"
        const val STOP = "stop"
        @Volatile var running = false
        fun start(c: Context) = c.startForegroundService(Intent(c, SniperService::class.java).setAction(START))
        fun stop(c: Context) = c.startService(Intent(c, SniperService::class.java).setAction(STOP))
    }

    private var gw: Gateway? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 600
    }
    private var me = ""
    private var subs = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        startForeground(1001, Notifier.svc(this, "conectando..."))
        running = true
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sniper:net").also { it.acquire() }
        wifi = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sniper:wifi").also { it.acquire() }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (gw == null) boot()
        return START_STICKY
    }

    private fun boot() {
        val main = Accounts.main(this)
        if (main == null) {
            Bus.log("sem conta principal")
            Notifier.updateSvc(this, "sem conta principal")
            return
        }
        Bus.log("conectando " + main.username)
        gw = Gateway(main.token, object : Gateway.Listener {
            override fun onReady(id: String) {
                me = id
                Bus.log("conectado como " + main.username)
                Notifier.updateSvc(this, "ativo • " + main.username)
            }
            override fun onGuild() {
                subs++
                if (subs % 10 == 0) Notifier.updateSvc(this, "ativo • " + main.username + " • " + subs + " servidores")
            }
            override fun onMessage(m: JSONObject) = consider(m)
            override fun onDead(r: String) {
                Bus.log("encerrado: " + r)
                Notifier.updateSvc(this, "desconectado: " + r)
            }
        }).also { it.start() }
    }

    private fun consider(m: JSONObject) {
        val content = m.optString("content")
        if (content.isEmpty()) return
        val author = m.optJSONObject("author") ?: return
        if (Prefs.ignoreOwn(this) && author.optString("id") == me) return
        if (Prefs.ignoreBots(this) && author.optBoolean("bot")) return

        val code = Regex("(?:discord\\.gift/|discord\\.com/gifts?/)([A-Za-z0-9]{16,24})")
            .find(content)?.groupValues?.get(1) ?: return

        synchronized(seen) {
            if (seen.containsKey(code)) return
            seen[code] = true
        }

        val ch = m.optString("channel_id")
        val sender = author.optString("global_name").ifEmpty { author.optString("username") }
        val link = "https://discord.com/channels/" + m.optString("guild_id", "@me") + "/" + ch + "/" + m.optString("id")
        Bus.log("detectado " + code + " de " + sender)

        worker.execute {
            val d = Prefs.delay(this)
            if (d > 0) Thread.sleep(d)
            claim(code, ch, sender, link)
        }
    }

    private fun claim(code: String, ch: String, sender: String, link: String) {
        val targets = mutableListOf<Account>()
        Accounts.main(this)?.let { targets.add(it) }
        if (Prefs.useAlts(this)) targets.addAll(Accounts.alts(this))

        for (acc in targets) {
            val r = Redeemer.redeem(acc.token, code, ch)
            if (r.ok) {
                Bus.log("RESGATADO com " + acc.username)
                Notifier.success(this, code, acc.username)
                Notifier.hook(this, "Nitro resgatado", 0x43b581, listOf(
                    "Conta" to acc.username, "Enviado por" to sender,
                    "Código" to code, "Mensagem" to link))
                Notifier.updateSvc(this, "último resgate: " + code)
                return
            }
            Bus.log("falhou com " + acc.username + ": " + r.message + if (r.captcha) " (captcha)" else "")
            if (r.message.contains("Unknown Gift Code", true)) return
        }
        Notifier.hook(this, "Falha ao resgatar", 0xf04747, listOf(
            "Enviado por" to sender, "Código" to code, "Mensagem" to link))
    }

    override fun onDestroy() {
        runCatching { gw?.stop() }
        runCatching { if (wake?.isHeld == true) wake?.release() }
        runCatching { if (wifi?.isHeld == true) wifi?.release() }
        worker.shutdownNow()
        running = false
        Bus.log("serviço parado")
        super.onDestroy()
    }
}

/* ---------------- boot ---------------- */

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (Prefs.autoStart(c) && Accounts.main(c) != null) SniperService.start(c)
    }
}

/* ---------------- tela ---------------- */

class MainActivity : Activity() {

    private lateinit var tokenInput: EditText
    private lateinit var altInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var webhookInput: EditText
    private lateinit var mainLabel: TextView
    private lateinit var altsBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var toggle: Button

    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        root.setPadding(p, p, p, p)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(title("Nitro Sniper", 24f))
        root.addView(label("Token da conta principal"))
        tokenInput = input("Token", false)
        root.addView(tokenInput)
        root.addView(button("Validar e definir como principal") { addAccount(true) })
        mainLabel = label("Nenhuma conta principal")
        root.addView(mainLabel)

        root.addView(label("Contas alt (reserva)"))
        altsBox = LinearLayout(this)
        altsBox.orientation = LinearLayout.VERTICAL
        root.addView(altsBox)
        altInput = input("Token da alt", false)
        root.addView(altInput)
        root.addView(button("Adicionar alt") { addAccount(false) })

        root.addView(label("Configurações"))
        root.addView(label("Delay antes de resgatar (ms)"))
        delayInput = input("0", true)
        root.addView(delayInput)

        val useAlts = check("Usar alts se a principal falhar", Prefs.useAlts(this)) { Prefs.setUseAlts(this, it) }
        val ignoreOwn = check("Ignorar links enviados por mim", Prefs.ignoreOwn(this)) { Prefs.setIgnoreOwn(this, it) }
        val ignoreBots = check("Ignorar links de bots", Prefs.ignoreBots(this)) { Prefs.setIgnoreBots(this, it) }
        val sound = check("Som e vibração ao resgatar", Prefs.sound(this)) { Prefs.setSound(this, it) }
        val autoStart = check("Iniciar sozinho ao ligar o celular", Prefs.autoStart(this)) { Prefs.setAutoStart(this, it) }
        root.addView(useAlts)
        root.addView(ignoreOwn)
        root.addView(ignoreBots)
        root.addView(sound)
        root.addView(autoStart)

        root.addView(label("Webhook (opcional)"))
        webhookInput = input("https://discord.com/api/webhooks/...", false)
        webhookInput.setText(Prefs.webhook(this))
        root.addView(webhookInput)

        toggle = button("Iniciar") { toggleService() }
        root.addView(toggle)
        root.addView(button("Limpar log") { logView.text = "" })
        logView = label("")
        root.addView(logView)

        delayInput.setText(Prefs.delay(this).toString())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)

        Bus.sink = { logView.append(it + "\n") }
        refresh()
    }

    private fun title(t: String, size: Float): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        v.setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        return v
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
        return v
    }

    private fun input(hint: String, numeric: Boolean): EditText {
        val v = EditText(this)
        v.hint = hint
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        v.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        return v
    }

    private fun button(t: String, action: () -> Unit): Button {
        val v = Button(this)
        v.text = t
        v.setOnClickListener { action() }
        return v
    }

    private fun check(t: String, on: Boolean, action: (Boolean) -> Unit): CheckBox {
        val v = CheckBox(this)
        v.text = t
        v.isChecked = on
        v.setOnCheckedChangeListener { _, b -> action(b) }
        return v
    }

    private fun addAccount(isMain: Boolean) {
        val field = if (isMain) tokenInput else altInput
        val token = field.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "cole o token", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "validando...", Toast.LENGTH_SHORT).show()
        Thread {
            val res = Redeemer.whoami(token)
            runOnUiThread {
                if (res == null) {
                    Toast.makeText(this, "token inválido", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val user = res.substringBefore("|")
                val id = res.substringAfter("|")
                Accounts.add(this, Account(id, token, user, isMain))
                field.setText("")
                refresh()
                Toast.makeText(this, "adicionado: " + user, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun refresh() {
        val main = Accounts.main(this)
        mainLabel.text = if (main == null) "Nenhuma conta principal" else "Principal: " + main.username

        altsBox.removeAllViews()
        Accounts.alts(this).forEach { acc ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val name = TextView(this)
            name.text = acc.username
            name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(name)
            val del = TextView(this)
            del.text = "remover"
            del.setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            del.setOnClickListener { Accounts.remove(this, acc.id); refresh() }
            row.addView(del)
            altsBox.addView(row)
        }
        toggle.text = if (SniperService.running) "Parar" else "Iniciar"
    }

    private fun toggleService() {
        Prefs.setDelay(this, delayInput.text.toString().toLongOrNull() ?: 0L)
        Prefs.setWebhook(this, webhookInput.text.toString().trim())

        if (SniperService.running) {
            SniperService.stop(this)
        } else {
            if (Accounts.main(this) == null) {
                Toast.makeText(this, "adicione a conta principal", Toast.LENGTH_LONG).show()
                return
            }
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + packageName)))
                }
            }
            SniperService.start(this)
        }
        toggle.postDelayed({ refresh() }, 400)
    }

    override fun onResume() {
        super.onResume()
        toggle.postDelayed({ refresh() }, 200)
    }
}
