package com.study.focus

import android.app.Activity
import android.content.Intent
import android.app.DatePickerDialog
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.study.focus.data.database.FocusSessionEntity
import com.study.focus.data.database.StudyFocusDatabase
import com.study.focus.data.database.TaskEntity
import com.study.focus.data.migration.LegacyPreferencesImporter
import com.study.focus.data.repository.StudyFocusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val BG = 0xFF090A0B.toInt()
private const val CARD = 0xFF17191B.toInt()
private const val FIELD = 0xFF25292C.toInt()
private const val FG = 0xFFE7E9EB.toInt()
private const val MUTED = 0xFF969DA2.toInt()
private const val ACCENT = 0xFFC0C6CA.toInt()
private const val GREEN = 0xFF70D6A2.toInt()
private val SUBJECTS = listOf("General", "Physics", "Chemistry", "Mathematics", "Biology", "English")

private data class Task(val id: Long, val title: String, val subject: String, val priority: Int, val due: Long, val done: Boolean)
private data class Session(val subject: String, val seconds: Int, val ended: Long, val assignment: Boolean = false)

private class Store(a: Activity) {
    private val p = a.getSharedPreferences("study_focus_v2", Activity.MODE_PRIVATE)
    init {
        // Preserve tasks created by the previous StudyFocus version.
        if (!p.contains("tasks")) {
            val legacy = a.getSharedPreferences("study_focus_tasks", Activity.MODE_PRIVATE).getString("tasks", null)
            if (legacy != null) {
                try {
                    val source = JSONArray(legacy)
                    val migrated = JSONArray()
                    for (i in 0 until source.length()) {
                        val old = source.getJSONObject(i)
                        migrated.put(JSONObject().apply {
                            put("id", old.optLong("id", i.toLong() + 1L))
                            put("title", old.optString("title"))
                            put("subject", "General")
                            put("priority", old.optInt("priority", 2).coerceIn(1, 3))
                            put("due", 0L)
                            put("done", old.optBoolean("done", false))
                        })
                    }
                    p.edit().putString("tasks", migrated.toString()).apply()
                } catch (_: Exception) { /* Ignore damaged legacy data. */ }
            }
        }
    }
    fun tasks(): MutableList<Task> {
        val out = mutableListOf<Task>()
        return try {
            val a = JSONArray(p.getString("tasks", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Task(o.optLong("id"), o.optString("title"), o.optString("subject", "General"), o.optInt("priority", 2).coerceIn(1, 3), o.optLong("due"), o.optBoolean("done")))
            }
            out
        } catch (_: Exception) { mutableListOf() }
    }
    fun saveTasks(list: List<Task>) {
        val a = JSONArray()
        list.forEach { t -> a.put(JSONObject().apply {
            put("id", t.id); put("title", t.title); put("subject", t.subject)
            put("priority", t.priority); put("due", t.due); put("done", t.done)
        }) }
        p.edit().putString("tasks", a.toString()).apply()
    }
    fun sessions(): MutableList<Session> {
        val out = mutableListOf<Session>()
        return try {
            val a = JSONArray(p.getString("sessions", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Session(o.optString("subject", "General"), o.optInt("seconds", o.optInt("minutes") * 60), o.optLong("ended"), o.optBoolean("assignment", false)))
            }
            out
        } catch (_: Exception) { mutableListOf() }
    }
    fun addSession(s: Session) {
        val all = sessions(); all.add(s)
        val a = JSONArray()
        all.takeLast(500).forEach { x -> a.put(JSONObject().apply {
            put("subject", x.subject); put("seconds", x.seconds); put("ended", x.ended); put("assignment", x.assignment)
        }) }
        p.edit().putString("sessions", a.toString()).apply()
    }
}

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var repository: StudyFocusRepository
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var roomReady = false
    private var taskCache = mutableListOf<Task>()
    private var sessionCache = mutableListOf<Session>()
    private lateinit var page: LinearLayout
    private var tab = "Home"
    private var due = 0L
    private var subject = "General"
    private var duration = 25
    private var left = 25L * 60000L
    private var running = false
    private var timer: CountDownTimer? = null
    private val flipDigitViews = mutableListOf<TextView>()
    private var lastFlipDigits = ""
    private var flipControlsVisible = false
    private var flip24Hour = true
    private var flipShowSeconds = true
    private var flipFullscreen = true
    private var flipDateView: TextView? = null
    private var flipPeriodView: TextView? = null
    private val flipHandler = Handler(Looper.getMainLooper())
    private val flipTicker = object : Runnable {
        override fun run() {
            if (tab == "Flip") updateFlipClock()
            flipHandler.postDelayed(this, 1000L)
        }
    }
    private val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val dtf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        taskCache = store.tasks()
        sessionCache = store.sessions()
        window.statusBarColor = BG; window.navigationBarColor = BG
        flipHandler.post(flipTicker)
        initializeRoom()
    }
    override fun onBackPressed() {
        if (tab == "Flip") {
            tab = "Home"
            show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        activityScope.cancel()
        flipHandler.removeCallbacks(flipTicker)
        applyFlipImmersiveMode(false)
        super.onDestroy()
    }

    private fun initializeRoom() {
        val database = StudyFocusDatabase.getInstance(applicationContext)
        repository = StudyFocusRepository(database.studyFocusDao())
        activityScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    LegacyPreferencesImporter.migrate(applicationContext, database.studyFocusDao())
                }
                val persisted = withContext(Dispatchers.IO) {
                    repository.tasks() to repository.sessions()
                }
                taskCache = persisted.first.map {
                    Task(it.id, it.title, it.subject, it.priority, it.dueAtMillis, it.isDone)
                }.toMutableList()
                sessionCache = persisted.second.map {
                    Session(it.subject, it.durationSeconds, it.endedAtMillis, it.isAssignment)
                }.toMutableList()
                roomReady = true
                show()
                if (imported.recoveredPayloads.isNotEmpty()) {
                    Toast.makeText(this@MainActivity,
                        "Some older entries were saved for recovery.", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                // Keep the SharedPreferences-backed app usable if Room setup or import fails.
                roomReady = false
                taskCache = taskList()
                sessionCache = sessionList()
                show()
                Toast.makeText(this@MainActivity,
                    "Local database setup failed. Existing saved data is still available.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun taskList(): MutableList<Task> = taskCache.toMutableList()
    private fun sessionList(): MutableList<Session> = sessionCache.toMutableList()

    private fun saveTasks(tasks: List<Task>) {
        taskCache = tasks.toMutableList()
        store.saveTasks(tasks)
        if (roomReady) activityScope.launch {
            repository.saveTasks(tasks.map {
                TaskEntity(it.id, it.title, it.subject, it.priority, it.due, it.done)
            })
        }
    }

    private fun addSession(session: Session) {
        sessionCache.add(session)
        while (sessionCache.size > 500) sessionCache.removeAt(0)
        store.addSession(session)
        if (roomReady) activityScope.launch {
            repository.addSession(FocusSessionEntity(
                id = UUID.randomUUID().toString(),
                subject = session.subject,
                durationSeconds = session.seconds,
                endedAtMillis = session.ended,
                isAssignment = session.assignment
            ))
        }
    }

    @Suppress("DEPRECATION")
    private fun applyFlipImmersiveMode(enabled: Boolean) {
        flipFullscreen = enabled
        if (enabled) {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(15).toFloat() }
    private fun txt(s: String, size: Float = 14f, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun gap(p: LinearLayout, n: Int = 9) { p.addView(View(this), LinearLayout.LayoutParams(1, dp(n))) }
    private fun card(p: LinearLayout): LinearLayout {
        val v = col().apply { background = shape(CARD); setPadding(dp(15), dp(15), dp(15), dp(15)) }
        p.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(11) }); return v
    }
    private fun metalButton(primary: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (primary) intArrayOf(0xFFD2D6D9.toInt(), 0xFF969FA4.toInt())
        else intArrayOf(0xFF737D83.toInt(), 0xFF4A5359.toInt())
    ).apply {
        cornerRadius = dp(15).toFloat()
        setStroke(dp(1), if (primary) 0xFFE0E3E5.toInt() else 0xFF899197.toInt())
    }
    private fun btn(s: String, primary: Boolean = false, f: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 13f
        setTextColor(if (primary) 0xFF171A1C.toInt() else 0xFFF0F2F3.toInt())
        background = metalButton(primary)
        stateListAnimator = null
        setOnClickListener { f() }
    }
    private fun field(h: String) = EditText(this).apply {
        hint = h; setHintTextColor(MUTED); setTextColor(FG); setSingleLine(true)
        background = shape(FIELD); setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun spin(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        background = shape(FIELD)
    }
    private fun show() {
        if (tab == "Flip") {
            applyFlipImmersiveMode(true)
            flipControlsVisible = false
            flipTimer()
            return
        }
        applyFlipImmersiveMode(false)
        val root = col().apply { setBackgroundColor(BG) }
        val head = row().apply { setPadding(dp(20), dp(18), dp(20), dp(12)) }
        val brand = col()
        val brandLine = row()
        val brandIcon = ImageView(this).apply {
            setImageResource(R.drawable.studyfocus_mark)
            background = shape(FIELD)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brandLine.addView(brandIcon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) })
        brandLine.addView(txt("STUDYFOCUS", 10f, ACCENT, true))
        brand.addView(brandLine)
        brand.addView(txt(tab, 25f, FG, true))
        head.addView(brand, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(txt("●", 14f, GREEN, true))
        root.addView(head)
        val scroll = ScrollView(this)
        page = col().apply { setPadding(dp(20), dp(8), dp(20), dp(18)) }
        scroll.addView(page); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = row().apply { background = shape(CARD); setPadding(dp(5), dp(5), dp(5), dp(5)) }
        listOf("Home", "Tasks", "Focus", "Flip", "Stats").forEach { name ->
            nav.addView(txt(name, 12f, if (tab == name) ACCENT else MUTED, tab == name).apply {
                gravity = Gravity.CENTER; setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { tab = name; show() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })
        setContentView(root)
        when (tab) { "Home" -> home(); "Tasks" -> tasks(); "Focus" -> focus(); "Flip" -> flipTimer(); "Stats" -> stats() }
    }
    private fun metric(p: LinearLayout, v: String, cap: String, c: Int) {
        val b = col(); b.addView(txt(v, 22f, c, true)); b.addView(txt(cap, 10f, MUTED))
        p.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun home() {
        val all = taskList()
        val welcome = card(page)
        welcome.addView(txt("Make today count.", 23f, FG, true)); gap(welcome, 5)
        welcome.addView(txt("Small focused sessions add up to big progress.", 13f, MUTED)); gap(welcome, 15)
        val m = row()
        metric(m, all.count { !it.done }.toString(), "To do", ACCENT)
        metric(m, all.count { it.done }.toString(), "Completed", GREEN)
        metric(m, (sessionList().filter { today(it.ended) }.sumOf { it.seconds } / 60).toString() + "m", "Focused today", 0xFFFFC979.toInt())
        welcome.addView(m)
        val f = card(page)
        f.addView(txt("FOCUS SESSION", 10f, ACCENT, true)); gap(f, 7)
        f.addView(txt("Ready for a distraction-free session?", 17f, FG, true)); gap(f)
        f.addView(btn("Start focusing  →", true) { tab = "Focus"; show() })
        val launch = card(page)
        launch.addView(txt("QUICK LAUNCH", 10f, ACCENT, true)); gap(launch, 7)
        launch.addView(btn("Open Spotify", false) { launchApp("com.spotify.music", "Spotify") })
        val nextCard = card(page)
        nextCard.addView(txt("UP NEXT", 10f, ACCENT, true)); gap(nextCard, 8)
        val next = all.filter { !it.done }.sortedBy { if (it.due == 0L) Long.MAX_VALUE else it.due }.take(5)
        if (next.isEmpty()) nextCard.addView(txt("You're all caught up. Add a task when you're ready.", 13f, MUTED))
        next.forEach { t ->
            nextCard.addView(txt(t.subject + "  ·  " + t.title, 14f))
            if (t.due > 0) nextCard.addView(txt("Due " + df.format(Date(t.due)), 10f, MUTED))
            gap(nextCard, 7)
        }
        nextCard.addView(btn("View all tasks", false) { tab = "Tasks"; show() })
    }
    private fun tasks() {
        val name = field("What do you need to study?")
        val subj = spin(SUBJECTS)
        val priority = spin(listOf("High priority", "Medium priority", "Low priority"))
        lateinit var dateBtn: Button
        dateBtn = btn(if (due == 0L) "＋ Add due date (optional)" else "Due " + df.format(Date(due))) {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                due = Calendar.getInstance().apply { set(y, m, d, 23, 59, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                dateBtn.text = "Due " + df.format(Date(due))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
        }
        val form = card(page)
        form.addView(txt("ADD A TASK", 10f, ACCENT, true)); gap(form, 8)
        form.addView(name, LinearLayout.LayoutParams(-1, dp(50))); gap(form, 7)
        form.addView(txt("Subject", 11f, MUTED)); form.addView(subj); gap(form, 7)
        form.addView(txt("Priority", 11f, MUTED)); form.addView(priority); gap(form, 7)
        form.addView(dateBtn); gap(form, 8)
        form.addView(btn("Add task", true) {
            val s = name.text.toString().trim()
            if (s.isEmpty()) name.error = "Enter a task name" else {
                val list = taskList()
                list.add(Task((list.maxOfOrNull { it.id } ?: 0L) + 1L, s, subj.selectedItem.toString(), priority.selectedItemPosition + 1, due, false))
                saveTasks(list); due = 0L; show()
                Toast.makeText(this, "Task added", Toast.LENGTH_SHORT).show()
            }
        })
        val all = taskList()
        val open = all.filter { !it.done }.sortedBy { if (it.due == 0L) Long.MAX_VALUE else it.due }
        val done = all.filter { it.done }
        page.addView(txt("PENDING · " + open.size, 10f, MUTED, true)); gap(page, 7)
        if (open.isEmpty()) page.addView(txt("No pending tasks. Nice work!", 13f, MUTED))
        open.forEach { taskRow(it) }
        page.addView(txt("COMPLETED · " + done.size, 10f, MUTED, true)); gap(page, 7)
        done.forEach { taskRow(it) }
        if (done.isNotEmpty()) page.addView(btn("Clear completed", false) {
            saveTasks(taskList().filterNot { it.done }); show()
        })
    }
    private fun taskRow(t: Task) {
        val box = card(page); val line = row()
        line.addView(txt(if (t.done) "✓" else "○", 21f, if (t.done) GREEN else ACCENT, true).apply {
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { saveTasks(taskList().map { if (it.id == t.id) it.copy(done = !it.done) else it }); show() }
        })
        val details = col()
        val title = txt(t.title, 14f, if (t.done) MUTED else FG, true)
        if (t.done) title.paintFlags = title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        details.addView(title)
        val p = listOf("High", "Medium", "Low")[t.priority - 1]
        val dueText = if (t.due > 0) " · Due " + df.format(Date(t.due)) else ""
        details.addView(txt(t.subject + " · " + p + " priority" + dueText, 10f, MUTED))
        line.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(txt("×", 21f, MUTED).apply { setOnClickListener { saveTasks(taskList().filterNot { it.id == t.id }); show() } })
        box.addView(line)
    }
    private fun focus() {
        val box = card(page).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(txt("FOCUS TIMER", 10f, ACCENT, true)); gap(box, 18)
        box.addView(txt(time(left), 50f, FG, true).apply { gravity = Gravity.CENTER })
        box.addView(txt(if (running) "Session in progress · " + subject else "Ready when you are", 12f, MUTED).apply { gravity = Gravity.CENTER })
        gap(box, 18)
        val presets = row()
        listOf(15, 25, 45, 60).forEach { n ->
            presets.addView(btn(n.toString() + "m", duration == n) { if (!running) { duration = n; left = n * 60000L; show() } },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(presets); gap(box, 14)
        box.addView(txt("Study subject", 11f, MUTED))
        val sp = spin(SUBJECTS); sp.setSelection(SUBJECTS.indexOf(subject).coerceAtLeast(0)); sp.isEnabled = !running
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { subject = SUBJECTS[pos] }
        }
        box.addView(sp); gap(box, 14)
        val controls = row()
        controls.addView(btn(if (running) "Pause" else "Start session", true) { if (running) pause() else start() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        controls.addView(btn("Reset", false) { reset() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        box.addView(controls)
        val note = card(page)
        note.addView(txt("MAKE IT COUNT", 10f, ACCENT, true)); gap(note, 6)
        note.addView(txt("Silence notifications, choose one goal, and take a short break when the session ends. Completed sessions appear in your study stats.", 13f, MUTED))
    }
    private fun start() {
        if (left <= 0L) left = duration * 60000L
        running = true
        timer = object : CountDownTimer(left, 1000L) {
            override fun onTick(ms: Long) { left = ms; if (tab == "Focus") show() }
            override fun onFinish() {
                left = 0L; running = false; addSession(Session(subject, duration * 60, System.currentTimeMillis(), false))
                Toast.makeText(this@MainActivity, "Focus session complete!", Toast.LENGTH_LONG).show(); show()
            }
        }.start()
        show()
    }
    private fun pause() { timer?.cancel(); running = false; show() }
    private fun reset() { timer?.cancel(); running = false; left = duration * 60000L; show() }
    private fun time(ms: Long) = String.format(Locale.getDefault(), "%02d:%02d", ms / 60000L, (ms / 1000L) % 60L)
    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun flipTimer() {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(20), dp(10), dp(20))
        }
        val brand = txt("STUDYFOCUS  ·  FLIP CLOCK", 10f, MUTED, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }
        content.addView(brand)
        gap(content, 24)

        val clockRow = row().apply { gravity = Gravity.CENTER }
        flipDigitViews.clear()
        lastFlipDigits = ""
        val initial = currentFlipTime()
        val initialDigits = initial.first
        val chars = if (flipShowSeconds) initialDigits.toList() else initialDigits.take(4).toList()
        chars.forEachIndexed { index, ch ->
            if (index == 2 || (flipShowSeconds && index == 4)) {
                clockRow.addView(txt(":", 40f, MUTED, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(1), 0, dp(1), dp(8))
                })
            }
            clockRow.addView(flipDigit(ch.toString()), LinearLayout.LayoutParams(dp(47), dp(76)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        content.addView(clockRow)
        lastFlipDigits = chars.joinToString("")
        gap(content, 14)
        flipPeriodView = txt(initial.second, 13f, ACCENT, true).apply { gravity = Gravity.CENTER }
        content.addView(flipPeriodView)
        gap(content, 8)
        flipDateView = txt(SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date()), 13f, MUTED).apply {
            gravity = Gravity.CENTER
        }
        content.addView(flipDateView)
        gap(content, 28)
        val hint = txt("Tap anywhere to reveal controls", 11f, 0xFF697177.toInt()).apply { gravity = Gravity.CENTER }
        content.addView(hint)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF202427.toInt(), 0xFF111315.toInt())
            ).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF3A4146.toInt()) }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            visibility = if (flipControlsVisible) View.VISIBLE else View.GONE
        }
        val formatRow = row()
        formatRow.addView(btn(if (flip24Hour) "24-hour  ✓" else "12-hour  ✓", true) {
            flip24Hour = !flip24Hour
            flipTimer()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        formatRow.addView(btn(if (flipShowSeconds) "Seconds  ON" else "Seconds  OFF", false) {
            flipShowSeconds = !flipShowSeconds
            flipTimer()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        controls.addView(formatRow)
        gap(controls, 8)
        controls.addView(btn(if (flipFullscreen) "Exit full-screen" else "Enter full-screen", false) {
            applyFlipImmersiveMode(!flipFullscreen)
            flipControlsVisible = true
            flipTimer()
        })
        gap(controls, 7)
        controls.addView(btn("Back to StudyFocus", false) {
            tab = "Home"
            show()
        })
        val controlParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(18), 0, dp(18), dp(22))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls, controlParams)
        root.setOnClickListener {
            flipControlsVisible = !flipControlsVisible
            controls.visibility = if (flipControlsVisible) View.VISIBLE else View.GONE
            hint.visibility = if (flipControlsVisible) View.INVISIBLE else View.VISIBLE
        }
        controls.setOnClickListener { /* Keep taps inside the control surface from dismissing it. */ }
        setContentView(root)
        updateFlipClock()
    }

    private fun flipDigit(value: String): FrameLayout {
        val tile = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF343A3E.toInt(), 0xFF202427.toInt())
            ).apply { cornerRadius = dp(7).toFloat(); setStroke(dp(1), 0xFF4A5258.toInt()) }
            cameraDistance = dp(900).toFloat()
        }
        val number = txt(value, 42f, 0xFFE4E7E9.toInt(), true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        tile.addView(number, FrameLayout.LayoutParams(-1, -1))
        val seam = View(this).apply { setBackgroundColor(0xFF111416.toInt()) }
        tile.addView(seam, FrameLayout.LayoutParams(-1, dp(1), Gravity.CENTER))
        tile.tag = number
        flipDigitViews.add(number)
        return tile
    }

    private fun currentFlipTime(): Pair<String, String> {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val displayHour = if (flip24Hour) hour else ((hour + 11) % 12) + 1
        val digits = if (flipShowSeconds) {
            String.format(Locale.getDefault(), "%02d%02d%02d", displayHour, now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
        } else {
            String.format(Locale.getDefault(), "%02d%02d", displayHour, now.get(Calendar.MINUTE))
        }
        val period = if (flip24Hour) "" else if (hour < 12) "AM" else "PM"
        return digits to period
    }

    private fun updateFlipClock() {
        if (tab != "Flip" || flipDigitViews.isEmpty()) return
        val (digits, period) = currentFlipTime()
        if (digits.length != flipDigitViews.size) {
            flipTimer()
            return
        }
        digits.forEachIndexed { index, digit ->
            val view = flipDigitViews[index]
            if (view.text.toString() != digit.toString()) {
                view.animate().cancel()
                view.animate().rotationX(88f).setDuration(105L).withEndAction {
                    view.text = digit.toString()
                    view.rotationX = -88f
                    view.animate().rotationX(0f).setDuration(115L).start()
                }.start()
            }
        }
        flipPeriodView?.text = period
        flipDateView?.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
    }

    private fun stats() {
        val ts = taskList(); val ss = sessionList()
        val todaySessions = ss.filter { today(it.ended) }
        val startWeek = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val week = ss.filter { it.ended >= startWeek }
        val overview = card(page)
        overview.addView(txt("YOUR STUDY OVERVIEW", 10f, ACCENT, true)); gap(overview, 12)
        val m = row()
        metric(m, (todaySessions.sumOf { it.seconds } / 60).toString() + "m", "Today", ACCENT)
        metric(m, (week.sumOf { it.seconds } / 60).toString() + "m", "This week", GREEN)
        metric(m, ss.size.toString(), "Sessions", 0xFFFFC979.toInt())
        overview.addView(m)
        val done = ts.count { it.done }; val percent = if (ts.isEmpty()) 0 else done * 100 / ts.size
        val progress = card(page)
        progress.addView(txt("TASK COMPLETION", 10f, ACCENT, true)); gap(progress, 8)
        progress.addView(txt(done.toString() + " of " + ts.size + " completed · " + percent + "%", 15f, FG, true)); gap(progress, 8)
        val bar = FrameLayout(this).apply { background = shape(FIELD) }
        if (percent > 0) bar.addView(View(this).apply { background = shape(ACCENT) }, FrameLayout.LayoutParams(dp(260) * percent / 100, dp(8)))
        progress.addView(bar, LinearLayout.LayoutParams(-1, dp(8)))
        val breakdown = card(page)
        breakdown.addView(txt("SUBJECT BREAKDOWN", 10f, ACCENT, true)); gap(breakdown, 8)
        var found = false
        SUBJECTS.forEach { s ->
            val subjectTasks = ts.filter { it.subject == s }
            val mins = ss.filter { it.subject == s }.sumOf { it.seconds } / 60
            if (subjectTasks.isNotEmpty() || mins > 0) {
                found = true; val line = row()
                line.addView(txt(s, 13f, FG, true), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(txt(subjectTasks.count { it.done }.toString() + "/" + subjectTasks.size + " tasks · " + mins + "m", 11f, MUTED))
                breakdown.addView(line); gap(breakdown, 8)
            }
        }
        if (!found) breakdown.addView(txt("Subject stats will appear here as you study.", 13f, MUTED))
        val recent = card(page)
        recent.addView(txt("RECENT SESSIONS", 10f, ACCENT, true)); gap(recent, 8)
        val last = ss.sortedByDescending { it.ended }.take(8)
        if (last.isEmpty()) recent.addView(txt("No completed focus sessions yet. Start your first one from Focus.", 13f, MUTED))
        last.forEach { s ->
            recent.addView(txt(s.subject + " · " + formatElapsed(s.seconds * 1000L) + if (s.assignment) " · Assignment" else "", 13f, FG, true))
            recent.addView(txt(dtf.format(Date(s.ended)), 10f, MUTED)); gap(recent, 8)
        }
    }
    private fun launchApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) startActivity(intent)
        else Toast.makeText(this, label + " is not installed", Toast.LENGTH_SHORT).show()
    }
    private fun today(time: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = time }; val b = Calendar.getInstance()
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
