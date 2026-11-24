package org.openfilemanager.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import java.io.File
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** وضعيات التكرار. التسميات في strings.xml (repeat_mode_*). */
enum class RepeatMode { OFF, ALL, ONE }

/** حالة التشغيل كما تراها الواجهة. بلا أي مرجع نشاط. */
data class PlaybackState(
    val playing: Boolean,
    val index: Int,
    val count: Int,
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val durationMs: Long,
    val repeat: RepeatMode,
    val shuffle: Boolean
)

/** بيانات المقطع الحالي من MediaMetadataRetriever (تفيد مع الملفات غير المفهرسة). */
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ByteArray? = null
)

/** سطر كلمات: timeMs سالب يعني نصاً غير متزامن (بلا طابع زمني). */
data class LyricLine(val timeMs: Long, val text: String)

/** جسر الحالة بين الخدمة والواجهة: الخدمة تكتب والواجهة تقرأ في STARTED فقط. */
object PlayerBus {
    val state = MutableStateFlow<PlaybackState?>(null)

    /** غلاف المقطع الحالي (صورة واحدة صغيرة تُستبدل عند تغيير المقطع). */
    val cover = MutableStateFlow<Bitmap?>(null)

    /** كلمات المقطع الحالي من ملف .lrc بجواره (null إن لم يوجد). */
    val lyrics = MutableStateFlow<List<LyricLine>?>(null)

    val errors = MutableSharedFlowSafe<String>()
}

/** تدفق رسائل خطأ بسيط (extraBufferCapacity) بلا اعتماد إضافي. */
class MutableSharedFlowSafe<T>(private val capacity: Int = 8) {
    private val listeners = ArrayList<(T) -> Unit>()
    fun tryEmit(value: T) {
        synchronized(listeners) { listeners.toList() }.forEach { it(value) }
    }

    fun subscribe(listener: (T) -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }
}

/**
 * خدمة تشغيل الموسيقى على **Media3**: ExoPlayer وحيد يعيش هنا ويُحرَّر في
 * onDestroy، تركيز الصوت وWAKE_MODE_LOCAL والعودة عند انقطاع السماعات
 * يديرها ExoPlayer نفسه، وMediaSession (media3) لأزرار السماعة وشاشة القفل مع بيانات
 * المقطع (عنوان/فنان/ألبوم/غلاف من MediaMetadataRetriever داخل use{}).
 *
 * الواجهة تتصل بأوامر startService كما هي (المشغل لا يغادر الخدمة أبداً)، والإشعار
 * ثابت وأدنى أهمية بلا أزرار (التحكم من لوحة النظام)؛ الجلسة نفسها لا تنشر إشعاراً لأن الخدمة ليست MediaSessionService
 * — التشغيل يبدأ من واجهة ظاهرة فقط (قيود تركيز الصوت في أندرويد 16).
 */
class MusicPlaybackService : Service() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private lateinit var notifications: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var playlist: List<String> = emptyList()
    private var index = 0
    private var repeat = RepeatMode.OFF
    private var shuffle = false
    private var consecutiveFailures = 0
    private var tags: TrackTags = TrackTags()
    private var tickerJob: Job? = null
    // Latest open request wins: a new openCurrent() cancels the previous load.
    private var openJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        scope.launch {
            AppSettings.get(applicationContext).ensureLoaded()
            repeat = AppSettings.get(applicationContext).defaultRepeat
            applyRepeatMode()
            if (playlist.isNotEmpty()) publish()
        }
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.addListener(playerListener) }
        player = exo
        session = MediaSession.Builder(this, exo).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** مسح حالة الواجهة فوراً كي يختفي المشغل المصغر ولا يبقى زراً ميتاً. */
    private fun clearBus() {
        PlayerBus.state.value = null
        PlayerBus.cover.value = null
        PlayerBus.lyrics.value = null
    }

    /**
     * إزالة التطبيق من التطبيقات الأخيرة: (تصحيح F20-استعادة) يُبقَى المشغل ما
     * دامت قائمة تشغيل محملة — قتل الخدمة عند الإيقاف المؤقت كان يجعل إشعار
     * الوسائط وزر التشغيل ميتين بعد تبديل التطبيقات أو مرور وقت، فلا يمكن
     * استئناف التشغيل (وهذا هو السلوك المعياري لتطبيقات الوسائط في أندرويد).
     * أمان إضافي الآن من الدفعة الثانية: علم running + مزامنة ON_RESUME في
     * الواجهة تمسح أي حالة قديمة إن قتل النظام الخدمة فعلاً.
     * الإيقاف مع قائمة فارغة (لا شيء للاستئناف) يوقف الخدمة.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (playlist.isEmpty()) { clearBus(); stopSelf() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (تصحيح) لا نترك الاستثناء يُسقط الخدمة؛ ويُسجَّل السبب للتشخيص.
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            AppLog.e("music", "تعذّر بدء الخدمة الأمامية: ${e.javaClass.simpleName}")
        }
        // (تصحيح) أمر تحكم وصل لخدمة بلا قائمة تشغيل (أُعيد إنشاؤها بعد أن أنهاها النظام
        // أو بعد مقاطعة طويلة): كانت تبقى حيّة بلا مشغل فتبدو أزرار المشغل المصغر ميتة.
        // نُنهيها ونُخفي المشغل بدل ذلك.
        if (intent?.action != ACTION_START && playlist.isEmpty()) {
            clearBus()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SYNC -> publish()
            ACTION_START -> {
                // القائمة عبر رمز المخزن عند كبرها (حد Binder، 6)؛ extras للقوائم
                // القصيرة وللتوافق مع النوايا القديمة.
                val uris = intent.getStringExtra(EXTRA_TOKEN)?.let { token ->
                    runCatching { PendingRequestStore.consumeViewerPlaylist(filesDir, token) }
                        .getOrNull()
                        ?.takeIf { it.kind == PendingRequestStore.KIND_PLAYER_URIS }
                        ?.items
                } ?: intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                val start = intent.getIntExtra(EXTRA_INDEX, 0)
                if (uris.isNotEmpty()) {
                    playlist = uris
                    index = start.coerceIn(0, uris.lastIndex)
                    openCurrent(autoplay = true)
                }
            }
            ACTION_TOGGLE -> if (player?.isPlaying == true) pause() else startPlayback()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_SEEK -> {
                intent.getLongExtra(EXTRA_POSITION_MS, -1).takeIf { it >= 0 }?.let { player?.seekTo(it) }
                publish()
            }
            ACTION_TOGGLE_SHUFFLE -> {
                shuffle = !shuffle
                publish()
            }
            ACTION_CYCLE_REPEAT -> {
                repeat = when (repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }
                applyRepeatMode()
                publish()
            }
            ACTION_STOP -> { clearBus(); stopSelf(); return START_NOT_STICKY }
            else -> if (playlist.isEmpty()) { clearBus(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    // ---------- منطق المشغل (كله على الخيط الرئيسي — ExoPlayer أحادي الخيط) ----------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
            if (isPlaying) {
                // نجاح التشغيل الفعلي يصفّر عداد الإخفاقات المتتالية (8.4): كان
                // العداد لا يُصفَّر أبداً فتخطي مقطعين فاشلين في جولتين منفصلتين
                // كان يكفي لإيقاف الخدمة كلها.
                consecutiveFailures = 0
                startTicker()
            }
        }

        // (تصحيح) مقاطعة من تطبيق آخر (فقد تركيز الصوت) تغيّر playWhenReady/السبب قبل أن
        // يتغيّر isPlaying — ننشر الحالة فوراً كي لا يبقى زر المشغل المصغر على حالة قديمة.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publish()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            publish()
        }

        // Keep service state in sync with ExoPlayer transitions that did not
        // originate from openCurrent() (auto-advance, headset/lock-screen next/prev).
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) return
            val newIndex = player?.currentMediaItemIndex ?: return
            if (newIndex !in playlist.indices || newIndex == index) return
            if (shuffle && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Shuffle order is owned by the service, not by ExoPlayer's sequence.
                index = newIndex
                next()
                return
            }
            syncToPlayerIndex(newIndex)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveFailures = 0
            if (playbackState == Player.STATE_ENDED) advance()
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            consecutiveFailures++
            AppLog.e("music", "خطأ تشغيل: ${error.errorCodeName}")
            if (consecutiveFailures >= playlist.size) {
                consecutiveFailures = 0
                PlayerBus.errors.tryEmit(getString(R.string.music_error_all_failed))
                clearBus()
                stopSelf()
                return
            }
            PlayerBus.errors.tryEmit(getString(R.string.music_error_skipping, BidiText.wrap(titleOf(playlist[index]))))
            next()
        }
    }

    /** تكرار المقطع الواحد يديره المشغل نفسه؛ الباقي منطق القائمة أدناه. */
    private fun applyRepeatMode() {
        player?.repeatMode = if (repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Reload tags/cover/lyrics for an item ExoPlayer already moved to. */
    private fun syncToPlayerIndex(newIndex: Int) {
        index = newIndex
        val entry = playlist[newIndex]
        openJob?.cancel()
        openJob = scope.launch {
            val loadedTags = readTags(entry)
            if (index != newIndex) return@launch
            tags = loadedTags
            PlayerBus.cover.value = tags.artwork?.let { decodeCover(it) }
            PlayerBus.lyrics.value = readLyrics(entry)
            refreshNotification()
            publish()
        }
        publish()
    }

    private fun openCurrent(autoplay: Boolean) {
        val p = player ?: return
        if (playlist.isEmpty()) return
        // التقاط الموضع مرة واحدة (8.4): قراءة index مرتين كانت تخلط وسوم
        // مقطع بـ URI مقطع آخر إن غيّر next() الموضع أثناء فتح السابق.
        val i = index
        val entry = playlist[i]
        openJob?.cancel()
        openJob = scope.launch {
            val loadedTags = readTags(entry)
            if (playlist.getOrNull(i) != entry || index != i) {
                // الموضع تغير أثناء الفتح: النتيجة ملغاة، المقطع الجديد مفتوح
                // من ندائه — لا نخلط وسوم القديم بـ URI الجديد.
                return@launch
            }
            tags = loadedTags
            val uri = playbackUriOf(entry)
            val meta = MediaMetadata.Builder()
                .setTitle(tags.title ?: titleOf(entry))
                .setArtist(tags.artist ?: "")
                .setAlbumTitle(tags.album ?: "")
                .apply { tags.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
            // القائمة كاملة للمشغل مع بداية عند المقطع الحالي (8.4): أزرار
            // السماعة وشاشة القفل (SEEK_TO_NEXT/PREVIOUS عبر MediaSession) لا
            // تعمل مع عنصر وحيد. الانتقال بين المقاطع يظل بمنطقنا (next/previous/
            // advance مع الالتفاف والتكرار والعشوائية) — يُعاد تقديم القائمة عند
            // كل فتح فيبقى موضع البدء صحيحاً.
            val items = playlist.map { item ->
                MediaItem.Builder().setUri(playbackUriOf(item).toUri()).build()
            }
            if (items.size == 1) {
                p.setMediaItem(MediaItem.Builder().setUri(uri.toUri()).setMediaMetadata(meta).build(), 0L)
            } else {
                val withMeta = items.toMutableList().also { list -> list[i] = list[i].buildUpon().setMediaMetadata(meta).build() }
                p.setMediaItems(withMeta, i, 0L)
            }
            p.prepare()
            applyRepeatMode()
            PlayerBus.cover.value = tags.artwork?.let { decodeCover(it) }
            PlayerBus.lyrics.value = readLyrics(entry)
            refreshNotification()
            if (autoplay) startPlayback() else publish()
        }
    }

    /**
     * (F-03) أصل التشغيل منفصل عن مصدر الوسوم ومسار الكلمات الجانبية: مسار
     * مطلق يُحوّل إلى file:// ليقرأه ExoPlayer، وfile:// وcontent:// تبقى كما
     * هي. الوسوم والكلمات تمر عبر [localPathOf] و[readTags] لا عبر مسار نصي واحد
     * يُلصق في كل المستهلكين.
     */
    private fun playbackUriOf(entry: String): String =
        if (entry.startsWith("/")) android.net.Uri.fromFile(File(entry)).toString() else entry

    /**
     * (F-03) المسار المحلي الحقيقي لعنصر القائمة إن وُجد: مسار مطلق أو file://
     * مباشرة، ومحتوى content:// عبر استعلام عمود DATA (MediaStore) لا تمرير نص
     * content:// إلى File. null = لا مسار محلي معروف (كلمات LRC تبقى غير
     * متاحة بدل فشل صامت في File).
     */
    private suspend fun localPathOf(entry: String): String? = withContext(AppDispatchers.io) {
        runCatching {
            when {
                entry.startsWith("file:") -> entry.toUri().path
                entry.startsWith("/") -> entry
                entry.startsWith("content:") -> applicationContext.contentResolver
                    .query(entry.toUri(), arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
                else -> null
            }
        }.getOrNull()
    }

    /** MediaMetadataRetriever داخل use{} على خيط IO: مسار محلي عبر المسار، ومحتوى content:// عبر setDataSource(Context, Uri) (F-03). */
    private suspend fun readTags(entry: String?): TrackTags = withContext(AppDispatchers.io) {
        if (entry == null) return@withContext TrackTags()
        val local = localPathOf(entry)
        if (local != null) return@withContext readTagsFromPath(local)
        val uri = entry.takeIf { it.startsWith("content:") }?.toUri() ?: return@withContext TrackTags()
        runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(applicationContext, uri)
                TrackTags(
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    artwork = mmr.embeddedPicture
                )
            }
        }.getOrDefault(TrackTags())
    }

    private fun readTagsFromPath(path: String): TrackTags = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(path)
            TrackTags(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artwork = mmr.embeddedPicture
            )
        }
    }.getOrDefault(TrackTags())

    /** كلمات من ملف .lrc بنفس اسم المقطع بجواره؛ متزامنة إن وُجدت أطوار زمنية وإلا نص عادي.
     *  (F-03) تُقرأ فقط عند توفر مسار محلي حقيقي — نص content:// لا يمر إلى File أبداً. */
    private suspend fun readLyrics(entry: String?): List<LyricLine>? = withContext(AppDispatchers.io) {
        val path = entry?.let { localPathOf(it) } ?: return@withContext null
        runCatching {
            val base = path.substringBeforeLast('.', path)
            val f = listOf("$base.lrc", "$base.LRC").map(::File).firstOrNull { it.isFile && it.length() in 1..512_000 }
                ?: return@runCatching null
            parseLrc(f.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun parseLrc(raw: String): List<LyricLine>? {
        val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val synced = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (line in raw.removePrefix("\uFEFF").lineSequence()) {
            val stamps = stamp.findAll(line).toList()
            val text = stamp.replace(line, "").trim()
            if (stamps.isEmpty()) {
                // وسوم المعلومات مثل [ar:..] [ti:..] تُتجاهل
                if (text.isNotEmpty() && !Regex("^\\[[a-zA-Z]+:.*]$").matches(text)) plain += LyricLine(-1L, text)
                continue
            }
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].let { if (it.isEmpty()) 0L else (it.padEnd(3, '0').take(3)).toLong() }
                synced += LyricLine(min * 60_000 + sec * 1000 + frac, text)
            }
        }
        return when {
            synced.isNotEmpty() -> synced.sortedBy { it.timeMs }
            plain.isNotEmpty() -> plain
            else -> null
        }
    }

    /**
     * فك غلاف بحجم محدود (8.4): الغلاف الخام قد يكون ميغابايتات، وتسليمه
     * كاملاً لـ MediaMetadata.setArtworkData ينسخه في Binder — يُفك بحد 512px.
     */
    private fun decodeCover(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > COVER_MAX_DIM) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    /**
     * (تصحيح) استئناف التشغيل بعد مقاطعة (تطبيق آخر أخذ تركيز الصوت أو غياب طويل):
     * المشغل قد يكون في IDLE (بعد خطأ/تحرير الموارد) أو ENDED أو بلا عناصر، وplay()
     * وحدها لا تفعل شيئاً في هذه الحالات فيبدو الزر ميتاً. نُعيد تجهيزه قبل التشغيل.
     */
    private fun startPlayback() {
        val p = player ?: return
        if (p.mediaItemCount == 0 && playlist.isNotEmpty()) {
            openCurrent(autoplay = true)
            return
        }
        when (p.playbackState) {
            Player.STATE_IDLE -> p.prepare()
            Player.STATE_ENDED -> p.seekToDefaultPosition()
            else -> {}
        }
        p.play()
        publish()
        // (تصحيح F20-استعادة) تحقق وإعادة محاولة: طلب التشغيل بعد فقدان تركيز
        // الصوت قد يُرفض صامتاً فيبقى الزر يبدو ميتاً. بعد مهلة قصيرة إن لم يبدأ
        // التشغيل فعلاً نعيد المحاولة مرة ونحدّث الإشعار.
        scope.launch {
            delay(700)
            if (!p.isPlaying && p.playWhenReady &&
                p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
            ) {
                AppLog.e("music", "لم يبدأ التشغيل بعد play() — إعادة محاولة")
                runCatching { p.play() }
                publish()
            }
        }
    }

    private fun pause() {
        player?.pause()
        publish()
    }

    private fun next() {
        if (playlist.isEmpty()) return
        index = if (shuffle && playlist.size > 1) {
            var candidate = index
            while (candidate == index) candidate = (0..playlist.lastIndex).random()
            candidate
        } else (index + 1) % playlist.size
        openCurrent(autoplay = true)
    }

    private fun previous() {
        if (playlist.isEmpty()) return
        index = if (index > 0) index - 1 else playlist.lastIndex
        openCurrent(autoplay = true)
    }

    /** اكتمال المقطع: OFF عند آخر مقطع يوقف التشغيل بلا القفز للأول (وONE يديره المشغل). */
    private fun advance() {
        if (repeat == RepeatMode.OFF && index == playlist.lastIndex) {
            player?.pause()
            player?.seekTo(0)
            return
        }
        if (repeat != RepeatMode.ONE) next()
    }

    private fun titleOf(uri: String): String =
        android.net.Uri.decode(uri.substringAfterLast('/')).substringBeforeLast('.')

    // ---------- الحالة والإشعار ----------

    /**
     * حالة الواجهة فقط: تحديث PlayerBus رخيص ويُستدعى من المؤقّت كل 500 مللي.
     * الإشعار ثابت ولا يُحدَّث.
     */
    private fun publish() {
        val p = player
        PlayerBus.state.value = PlaybackState(
            playing = p?.isPlaying == true,
            index = index,
            count = playlist.size,
            title = if (playlist.isEmpty()) "" else (tags.title ?: titleOf(playlist[index])),
            artist = tags.artist ?: "",
            album = tags.album ?: "",
            positionMs = p?.currentPosition ?: 0L,
            durationMs = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L,
            repeat = repeat,
            shuffle = shuffle
        )
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && player?.isPlaying == true) {
                delay(500)
                publish()
            }
        }
    }

    /**
     * أندرويد يشترط إشعاراً لأي خدمة أمامية، لذا يبقى إشعار واحد ثابت في قناة بأدنى أهمية
     * (بلا صوت ولا أزرار ولا تحديثات). التحكم يتم من لوحة النظام عبر MediaSession.
     */
    private fun createChannel() {
        notifications.deleteNotificationChannel(OLD_CHANNEL_ID)
        notifications.deleteNotificationChannel(OLD_SILENT_CHANNEL_ID)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.music_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.music_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * إشعار الخدمة الأمامية بنمط الوسائط مربوطاً بالجلسة: يدمجه النظام في بطاقة التحكم
     * الوحيدة (بعنوان المقطع وغلافه) بدل إشعار صامت منفصل بعنوان «تشغيل…».
     */
    // Media3's MediaStyle notification helper is opt-in (UnstableApi): the call site
    // accepts it explicitly instead of weakening the project-wide lint check.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildNotification(): Notification {
        val title = if (playlist.isEmpty()) getString(R.string.music_channel) else (tags.title ?: titleOf(playlist[index]))
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(tags.artist?.takeIf { it.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        PlayerBus.cover.value?.let { b.setLargeIcon(it) }
        session?.let { b.setStyle(MediaStyleNotificationHelper.MediaStyle(it)) }
        return b.build()
    }

    private fun refreshNotification() {
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification()) }
    }

    override fun onDestroy() {
        running = false
        session?.release()
        session = null
        player?.release()
        player = null
        tickerJob?.cancel()
        scope.cancel()
        PlayerBus.state.value = null
        PlayerBus.cover.value = null
        PlayerBus.lyrics.value = null
        notifications.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "music_media"
        private const val OLD_CHANNEL_ID = "music"
        private const val OLD_SILENT_CHANNEL_ID = "music_silent"
        const val NOTIFICATION_ID = 43
        const val ACTION_START = "org.openfilemanager.app.music.START"
        const val ACTION_TOGGLE = "org.openfilemanager.app.music.TOGGLE"
        const val ACTION_NEXT = "org.openfilemanager.app.music.NEXT"
        const val ACTION_PREV = "org.openfilemanager.app.music.PREV"
        const val ACTION_STOP = "org.openfilemanager.app.music.STOP"
        const val ACTION_TOGGLE_SHUFFLE = "org.openfilemanager.app.music.SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "org.openfilemanager.app.music.REPEAT"
        const val ACTION_SEEK = "org.openfilemanager.app.music.SEEK"
        const val ACTION_SYNC = "org.openfilemanager.app.music.SYNC"

        /**
         * (تصحيح) هل الخدمة حيّة فعلاً. PlayerBus يعيش في العملية فقد تبقى فيه حالة قديمة
         * بعد أن تموت الخدمة؛ الواجهة تتحقق من هذا قبل أن تعرض المشغل المصغر أو ترسل أمراً.
         */
        @Volatile
        var running: Boolean = false
            private set
        const val EXTRA_URIS = "uris"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION_MS = "position_ms"

        /** رمز قائمة التشغيل في المخزن (6) — بديل extras الكبيرة. */
        const val EXTRA_TOKEN = "playlist_token"

        /** فوق هذا العدد تُمرَّر القائمة عبر المخزن لا extras (أمان حد Binder). */
        private const val PLAYER_LIST_EXTRAS_LIMIT = 64

        /** حد بُعد غلاف المقطع بعد الفك (8.4). */
        private const val COVER_MAX_DIM = 512

        /** التشغيل يبدأ من واجهة ظاهرة فقط (قيود تركيز الصوت).
         *  القوائم الكبيرة تُمرَّر عبر رمز في المخزن لا extras (حد Binder، 6). */
        fun start(context: Context, uris: List<String>, index: Int): Boolean {
            var storedToken: String? = null
            return try {
                val intent = Intent(context, MusicPlaybackService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_INDEX, index)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.isNotEmpty()) {
                    // Only the current track is needed for launch-time grant metadata.
                    // A large playlist belongs in the private token store, not ClipData,
                    // otherwise its URIs would recreate the Binder-size problem.
                    val first = android.net.Uri.parse(uris[index.coerceIn(uris.indices)])
                    intent.clipData = android.content.ClipData.newUri(context.contentResolver, "Audio", first)
                }
                if (uris.size > PLAYER_LIST_EXTRAS_LIMIT) {
                    storedToken = PendingRequestStore.writeViewerPlaylist(
                        context.filesDir, PendingRequestStore.KIND_PLAYER_URIS, uris, index
                    )
                    intent.putExtra(EXTRA_TOKEN, storedToken)
                } else {
                    intent.putExtra(EXTRA_URIS, ArrayList(uris))
                }
                context.startForegroundService(intent)
                true
            } catch (_: Exception) {
                storedToken?.let { PendingRequestStore.deleteViewerPlaylist(context.filesDir, it) }
                false
            }
        }

        fun command(context: Context, action: String) {
            // خدمة ميتة: لا نُحييها بأمر تحكم (كانت تُنشأ فارغة وتبقى بلا فائدة)
            if (!running) {
                PlayerBus.state.value = null
                PlayerBus.cover.value = null
                PlayerBus.lyrics.value = null
                return
            }
            runCatching { context.startService(Intent(context, MusicPlaybackService::class.java).setAction(action)) }
        }

        /** أمر مع قيمة عددية (مثل موضع التقديم في ACTION_SEEK). */
        fun command(context: Context, action: String, extraName: String, value: Long) {
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, MusicPlaybackService::class.java)
                        .setAction(action)
                        .putExtra(extraName, value)
                )
            }
        }
    }
}
