package app.onion.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class AppGenerationManager(
    context: Context,
    private val preferences: SharedPreferences,
    private val localLlmClient: LocalLlmClient = LocalLlmNotConnectedClient(),
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<GenerationWork>()
    private var activeJob: Job? = null
    private var activeWork: GenerationWork? = null

    private val _state = MutableStateFlow(
        AppGenerationState(apps = loadSavedApps()),
    )
    val state: StateFlow<AppGenerationState> = _state.asStateFlow()

    fun enqueue(
        request: AppGenerationRequest,
        replacingAppId: String? = null,
    ): String {
        val draft = GeneratedAppDraft(
            id = replacingAppId ?: UUID.randomUUID().toString(),
            title = "",
            prompt = request.prompt,
            category = request.category,
            style = request.style,
            useLocalStorage = request.useLocalStorage,
            html = "",
            status = GenerationStatus.Queued,
            progressLabel = "대기 중이에요",
        )
        queue.addLast(GenerationWork(draft.id, request, replacingAppId))
        _state.update {
            it.copy(
                drafts = it.drafts + draft,
                current = draft,
            )
        }
        processNextIfIdle()
        return draft.id
    }

    fun cancelGeneration(draftId: String) {
        queue.removeAll { it.id == draftId }
        if (activeWork?.id == draftId) {
            activeJob?.cancel()
            activeJob = null
            activeWork = null
        }
        notificationManager.cancel(draftId.notificationId())
        _state.update { state ->
            val drafts = state.drafts.filterNot { it.id == draftId }
            state.copy(
                drafts = drafts,
                current = state.current?.takeUnless { it.id == draftId } ?: drafts.firstOrNull(),
            )
        }
        processNextIfIdle()
    }

    fun draftById(draftId: String): GeneratedAppDraft? {
        return _state.value.drafts.firstOrNull { it.id == draftId }
    }

    private fun processNextIfIdle() {
        if (activeJob?.isActive == true) return
        val next = if (queue.isEmpty()) return else queue.removeFirst()
        activeWork = next
        activeJob = scope.launch {
            runCatching {
                runGeneration(next)
            }.onFailure { error ->
                updateDraft(next.id) {
                    it.copy(
                        html = errorHtml(it.title, error.message ?: "앱 생성을 완료하지 못했습니다.", it.prompt),
                        status = GenerationStatus.Failed,
                        progressLabel = "생성 실패",
                    )
                }
                postGenerationNotification(next.id)
            }
            activeWork = null
            activeJob = null
            processNextIfIdle()
        }
    }

    private suspend fun runGeneration(work: GenerationWork) {
        val request = work.request
        updateDraft(work.id) {
            it.copy(status = GenerationStatus.Generating, progressLabel = "AI가 앱 이름을 정하고 있어요")
        }
        postGenerationNotification(work.id)

        updateDraft(work.id) {
            it.copy(progressLabel = "AI가 계획 중이에요")
        }
        postGenerationNotification(work.id)

        delay(350)
        updateDraft(work.id) {
            it.copy(progressLabel = "요청을 앱 생성 도구에 전달했어요")
        }

        val htmlPrompt = HtmlAppPrompt.build(request)
        val htmlResult = runCatching {
            localLlmClient.generateHtml(htmlPrompt) { partialHtml ->
                val label = progressLabelFor(partialHtml)
                val partialTitle = extractTitle(partialHtml)
                updateDraft(work.id) {
                    it.copy(
                        title = partialTitle ?: it.title,
                        html = partialHtml,
                        progressLabel = label,
                        status = GenerationStatus.Generating,
                    )
                }
                postGenerationNotification(work.id)
            }
        }
        val html = htmlResult.getOrElse { error ->
            errorHtml(
                title = draftById(work.id)?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(request.prompt),
                message = error.message ?: "앱 생성 도구를 실행할 수 없습니다.",
                prompt = request.prompt,
            )
        }
        val title = extractTitle(html)
            ?: draftById(work.id)?.title?.takeIf { it.isNotBlank() }
            ?: fallbackTitle(request.prompt)

        val finished = draftById(work.id)?.copy(
            title = title,
            html = html,
            status = if (htmlResult.isSuccess) GenerationStatus.Done else GenerationStatus.Failed,
            progressLabel = if (htmlResult.isSuccess) "완성됨" else "앱 생성 도구 연결 필요",
        ) ?: return

        _state.update { state ->
            val apps = if (finished.status == GenerationStatus.Failed) state.apps else {
                listOf(finished.toSavedApp()) + state.apps.filterNot { it.id == finished.id }
            }
            saveApps(apps)
            state.copy(
                current = finished,
                drafts = state.drafts.map { if (it.id == finished.id) finished else it },
                apps = apps,
            )
        }
        postGenerationNotification(work.id)
    }

    private fun updateDraft(
        id: String,
        transform: (GeneratedAppDraft) -> GeneratedAppDraft,
    ) {
        _state.update { state ->
            val drafts = state.drafts.map { if (it.id == id) transform(it) else it }
            state.copy(
                drafts = drafts,
                current = state.current?.let { current ->
                    drafts.firstOrNull { it.id == current.id } ?: current
                } ?: drafts.firstOrNull { it.id == id },
            )
        }
    }

    private fun progressLabelFor(html: String): String {
        val lower = html.lowercase()
        return when {
            "<script" in lower -> "기능 연결 중"
            "<body" in lower -> "화면 생성 중"
            "<style" in lower || "<css" in lower -> "디자인 적용 중"
            else -> "AI가 계획 중이에요"
        }
    }

    private fun extractTitle(html: String): String? {
        return Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(32)
            ?.takeIf { it.isNotBlank() }
    }

    private fun postGenerationNotification(draftId: String) {
        val draft = draftById(draftId) ?: return
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = when (draft.status) {
            GenerationStatus.Queued -> "${draft.displayTitle()} 대기 중"
            GenerationStatus.Generating -> "${draft.displayTitle()} 만드는 중"
            GenerationStatus.Done -> "${draft.displayTitle()} 생성 완료"
            GenerationStatus.Failed -> "${draft.displayTitle()} 생성 실패"
        }
        val notification = Notification.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(draft.progressLabel)
            .setOngoing(draft.status == GenerationStatus.Queued || draft.status == GenerationStatus.Generating)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(0, 0, draft.status == GenerationStatus.Queued || draft.status == GenerationStatus.Generating)
            .build()
        notificationManager.notify(draftId.notificationId(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "앱 생성",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun String.notificationId(): Int = hashCode()

    private fun GeneratedAppDraft.displayTitle(): String {
        return title.ifBlank { "새 앱" }
    }

    private data class GenerationWork(
        val id: String,
        val request: AppGenerationRequest,
        val replacingAppId: String?,
    )

    fun deleteApp(appId: String) {
        _state.update { state ->
            val apps = state.apps.filterNot { it.id == appId }
            saveApps(apps)
            state.copy(apps = apps)
        }
    }

    fun clearCurrent() {
        _state.update { it.copy(current = null) }
    }

    private fun initialHtml(title: String): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <style>
            body { margin:0; font-family:-apple-system, BlinkMacSystemFont, sans-serif; background:#f7f8f1; color:#101510; }
            main { min-height:100vh; display:grid; place-items:center; padding:24px; box-sizing:border-box; }
            section { width:100%; max-width:320px; }
            h1 { font-size:26px; line-height:1.08; margin:0 0 12px; }
            p { color:#5d6559; line-height:1.5; }
            .bar { height:8px; background:#dfe8d7; border-radius:999px; overflow:hidden; margin-top:18px; }
            .bar span { display:block; width:18%; height:100%; background:#5bae31; border-radius:999px; animation:pulse 1.2s infinite alternate; }
            @keyframes pulse { from { width:18%; } to { width:42%; } }
          </style>
        </head>
        <body><main><section><h1>${title.escapeHtml()}</h1><p>앱 생성 도구가 화면 구성을 준비하고 있습니다.</p><div class="bar"><span></span></div></section></main></body>
        </html>
    """.trimIndent()

    private fun fallbackTitle(prompt: String): String {
        val words = prompt
            .replace(Regex("[^가-힣A-Za-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return words.take(4).joinToString(" ").ifBlank { "새 앱" }.take(24)
    }

    private fun errorHtml(
        title: String,
        message: String,
        prompt: String,
    ): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <style>
            body { margin:0; font-family:-apple-system, BlinkMacSystemFont, sans-serif; background:#f7f8f1; color:#101510; }
            main { min-height:100vh; display:grid; place-items:center; padding:24px; box-sizing:border-box; }
            section { width:100%; max-width:420px; background:white; border:1px solid #dfe7d8; border-radius:22px; padding:20px; }
            h1 { margin:0 0 10px; font-size:24px; }
            p { color:#5d6559; line-height:1.5; }
            pre { white-space:pre-wrap; word-break:break-word; background:#f1f5eb; border-radius:14px; padding:14px; }
          </style>
        </head>
        <body>
          <main>
            <section>
              <h1>${title.escapeHtml()}</h1>
              <p>${message.escapeHtml()}</p>
              <pre>${prompt.escapeHtml()}</pre>
            </section>
          </main>
        </body>
        </html>
    """.trimIndent()

    private fun streamHtml(title: String, request: AppGenerationRequest): List<HtmlUpdate> {
        return listOf(
            HtmlUpdate(
                label = "요구사항을 화면 구조로 바꾸는 중",
                html = appHtml(title, request, stage = 1),
            ),
            HtmlUpdate(
                label = "입력과 상태 변화를 연결하는 중",
                html = appHtml(title, request, stage = 2),
            ),
            HtmlUpdate(
                label = "저장 방식과 모바일 레이아웃을 다듬는 중",
                html = appHtml(title, request, stage = 3),
            ),
            HtmlUpdate(
                label = "마지막 정리 중",
                html = appHtml(title, request, stage = 4),
            ),
        )
    }

    private fun appHtml(
        title: String,
        request: AppGenerationRequest,
        stage: Int,
    ): String = when (detectIntent(request)) {
        PromptIntent.Timer -> timerHtml(title, request, stage)
        PromptIntent.Todo -> todoHtml(title, request, stage)
        PromptIntent.Water -> waterHtml(title, request, stage)
        PromptIntent.Game -> gameHtml(title, request, stage)
        PromptIntent.Study -> studyHtml(title, request, stage)
        PromptIntent.Tracker -> trackerHtml(title, request, stage)
        PromptIntent.Utility -> utilityHtml(title, request, stage)
    }

    private fun detectIntent(request: AppGenerationRequest): PromptIntent {
        val prompt = request.prompt.lowercase()
        return when {
            prompt.containsAny("타이머", "timer", "스톱워치", "stopwatch", "카운트다운", "countdown", "알람", "alarm", "뽀모도로", "pomodoro") -> PromptIntent.Timer
            prompt.containsAny("투두", "todo", "to-do", "할일", "할 일", "체크리스트", "checklist", "task") -> PromptIntent.Todo
            prompt.containsAny("물", "water", "수분", "마신", "마시") -> PromptIntent.Water
            prompt.containsAny("게임", "game", "점수", "score", "피하기", "클릭") -> PromptIntent.Game
            prompt.containsAny("퀴즈", "quiz", "플래시", "flash", "암기", "공부", "학습", "단어") -> PromptIntent.Study
            request.category == AppCategory.Game -> PromptIntent.Game
            request.category == AppCategory.Study -> PromptIntent.Study
            request.category == AppCategory.Tracker -> PromptIntent.Tracker
            else -> PromptIntent.Utility
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it) }
    }

    private fun timerHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val presets = if (stage >= 3) """
            <section class="actions">
              <button onclick="preset(60)">1분</button>
              <button onclick="preset(300)">5분</button>
              <button onclick="preset(600)">10분</button>
              <button onclick="preset(1500)">25분</button>
            </section>
        """ else ""
        return shell(title, request, accent, """
            <header><span>TIMER</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            <section class="timer-card">
              <div id="display">05:00</div>
              <p id="timerState">준비됨</p>
            </section>
            <section class="panel">
              <div class="actions">
                <label>분<input id="minutes" type="number" min="0" value="5" /></label>
                <label>초<input id="seconds" type="number" min="0" max="59" value="0" /></label>
              </div>
              <div class="actions">
                <button onclick="startTimer()">시작</button>
                <button onclick="pauseTimer()">일시정지</button>
              </div>
              <button onclick="resetTimer()">리셋</button>
            </section>
            $presets
            <script>
              let remaining = 300;
              let initial = 300;
              let timer = null;
              const display = document.getElementById('display');
              const state = document.getElementById('timerState');
              function format(value){
                const m = Math.floor(value / 60).toString().padStart(2,'0');
                const s = Math.floor(value % 60).toString().padStart(2,'0');
                return m + ':' + s;
              }
              function readInputs(){
                const m = Math.max(0, Number(document.getElementById('minutes').value || 0));
                const s = Math.max(0, Math.min(59, Number(document.getElementById('seconds').value || 0)));
                return Math.max(1, Math.floor(m * 60 + s));
              }
              function render(){
                display.textContent = format(remaining);
                document.title = format(remaining) + ' - $safeTitle';
              }
              function preset(seconds){
                pauseTimer();
                initial = seconds;
                remaining = seconds;
                document.getElementById('minutes').value = Math.floor(seconds / 60);
                document.getElementById('seconds').value = seconds % 60;
                state.textContent = '준비됨';
                render();
              }
              function startTimer(){
                if(timer) return;
                if(remaining <= 0 || remaining === initial) {
                  initial = readInputs();
                  remaining = initial;
                }
                state.textContent = '진행 중';
                render();
                timer = setInterval(() => {
                  remaining -= 1;
                  render();
                  if(remaining <= 0){
                    clearInterval(timer);
                    timer = null;
                    remaining = 0;
                    state.textContent = '완료';
                    render();
                    if(navigator.vibrate) navigator.vibrate([160,80,160]);
                  }
                }, 1000);
              }
              function pauseTimer(){
                clearInterval(timer);
                timer = null;
                state.textContent = remaining <= 0 ? '완료' : '일시정지';
              }
              function resetTimer(){
                pauseTimer();
                initial = readInputs();
                remaining = initial;
                state.textContent = '준비됨';
                render();
              }
              render();
            </script>
        """.trimIndent())
    }

    private fun todoHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val summary = if (stage >= 3) """<section class="stats"><b id="left">0</b><span>남음</span><b id="doneCount">0</b><span>완료</span></section>""" else ""
        return shell(title, request, accent, """
            <header><span>TODO</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            $summary
            <section class="panel">
              <label>새 할 일<input id="taskInput" placeholder="예: 장보기, 운동하기" /></label>
              <button onclick="addTask()">추가</button>
            </section>
            <section class="list" id="todoList"></section>
            <script>
              const key = 'onion-todo-${safeTitle.hashCode()}';
              let tasks = JSON.parse(localStorage.getItem(key) || '[]');
              function save(){ ${if (request.useLocalStorage) "localStorage.setItem(key, JSON.stringify(tasks));" else ""} }
              function render(){
                const list = document.getElementById('todoList');
                list.innerHTML = tasks.map((task,i) =>
                  '<article class="'+(task.done?'done':'')+'"><button onclick="toggleTask('+i+')">'+(task.done?'✓':'○')+'</button><div><strong>'+escapeText(task.text)+'</strong><small>'+task.created+'</small></div><button onclick="removeTask('+i+')">삭제</button></article>'
                ).join('');
                const left=document.getElementById('left'); if(left) left.textContent=tasks.filter(t=>!t.done).length;
                const done=document.getElementById('doneCount'); if(done) done.textContent=tasks.filter(t=>t.done).length;
              }
              function escapeText(value){ return value.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
              function addTask(){
                const input=document.getElementById('taskInput');
                const text=input.value.trim();
                if(!text) return;
                tasks.unshift({text, done:false, created:new Date().toLocaleDateString()});
                input.value=''; save(); render();
              }
              function toggleTask(i){ tasks[i].done=!tasks[i].done; save(); render(); }
              function removeTask(i){ tasks.splice(i,1); save(); render(); }
              render();
            </script>
        """.trimIndent())
    }

    private fun waterHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val goal = if (stage >= 3) 8 else 6
        return shell(title, request, accent, """
            <header><span>WATER</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            <section class="score"><b id="cups">0</b><span>잔</span><b>$goal</b><span>목표</span></section>
            <section class="panel">
              <button onclick="addCup()">한 잔 마셨어요</button>
              <button onclick="resetDay()">오늘 기록 초기화</button>
              <p class="status" id="message">오늘 마신 물을 기록하세요.</p>
            </section>
            <section class="grid" id="cupGrid"></section>
            <script>
              const key='onion-water-${safeTitle.hashCode()}';
              let cups=Number(localStorage.getItem(key)||0);
              function save(){ ${if (request.useLocalStorage) "localStorage.setItem(key, String(cups));" else ""} }
              function render(){
                document.getElementById('cups').textContent=cups;
                document.getElementById('message').textContent = cups >= $goal ? '목표를 달성했습니다.' : '목표까지 '+($goal-cups)+'잔 남았습니다.';
                document.getElementById('cupGrid').innerHTML = Array.from({length:$goal}, (_,i)=>'<article><strong>'+(i<cups?'💧':'○')+'</strong><span>'+(i+1)+'잔</span></article>').join('');
              }
              function addCup(){ cups++; save(); render(); }
              function resetDay(){ cups=0; save(); render(); }
              render();
            </script>
        """.trimIndent())
    }

    private fun utilityHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val extra = if (stage >= 3) """<section class="panel"><h2>최근 결과</h2><ul id="history"></ul></section>""" else ""
        return shell(title, request, accent, """
            <header><span>UTILITY</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            <section class="panel">
              <label>값 A<input id="a" type="number" inputmode="decimal" placeholder="0" /></label>
              <label>값 B<input id="b" type="number" inputmode="decimal" placeholder="0" /></label>
              <div class="actions">
                <button onclick="calc('+')">더하기</button>
                <button onclick="calc('-')">빼기</button>
                <button onclick="calc('*')">곱하기</button>
                <button onclick="calc('/')">나누기</button>
              </div>
              <output id="result">결과가 여기에 표시됩니다.</output>
            </section>
            $extra
            <script>
              const historyEl = document.getElementById('history');
              const key = 'onion-util-${safeTitle.hashCode()}';
              const history = JSON.parse(localStorage.getItem(key) || '[]');
              function render(){ if(historyEl) historyEl.innerHTML = history.map(x => '<li>'+x+'</li>').join(''); }
              function calc(op){
                const a = Number(document.getElementById('a').value || 0);
                const b = Number(document.getElementById('b').value || 0);
                const value = op === '+' ? a+b : op === '-' ? a-b : op === '*' ? a*b : (b === 0 ? '나눌 수 없음' : a/b);
                const text = a + ' ' + op + ' ' + b + ' = ' + value;
                document.getElementById('result').textContent = text;
                history.unshift(text); history.splice(8);
                ${if (request.useLocalStorage) "localStorage.setItem(key, JSON.stringify(history));" else ""}
                render();
              }
              render();
            </script>
        """.trimIndent())
    }

    private fun trackerHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val stats = if (stage >= 3) """<section class="stats"><b id="count">0</b><span>개의 기록</span><b id="done">0</b><span>완료</span></section>""" else ""
        return shell(title, request, accent, """
            <header><span>TRACKER</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            $stats
            <section class="panel">
              <label>오늘 기록<input id="entry" placeholder="예: 물 2잔, 운동 20분" /></label>
              <button onclick="add()">기록하기</button>
            </section>
            <section class="list" id="list"></section>
            <script>
              const key = 'onion-tracker-${safeTitle.hashCode()}';
              let items = JSON.parse(localStorage.getItem(key) || '[]');
              function save(){ ${if (request.useLocalStorage) "localStorage.setItem(key, JSON.stringify(items));" else ""} }
              function render(){
                document.getElementById('list').innerHTML = items.map((item,i) =>
                  '<article class="'+(item.done?'done':'')+'"><button onclick="toggle('+i+')">'+(item.done?'✓':'○')+'</button><div><strong>'+item.text+'</strong><small>'+item.date+'</small></div></article>'
                ).join('');
                const c=document.getElementById('count'); if(c)c.textContent=items.length;
                const d=document.getElementById('done'); if(d)d.textContent=items.filter(x=>x.done).length;
              }
              function add(){
                const input = document.getElementById('entry');
                if(!input.value.trim()) return;
                items.unshift({text:input.value.trim(), done:false, date:new Date().toLocaleDateString()});
                input.value=''; save(); render();
              }
              function toggle(i){ items[i].done=!items[i].done; save(); render(); }
              render();
            </script>
        """.trimIndent())
    }

    private fun studyHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val seed = request.prompt.split(Regex("\\s+")).filter { it.length > 1 }.take(3).ifEmpty { listOf("핵심 개념", "예시", "복습") }
        val cards = seed.joinToString(",") { """{q:"${it.escapeJs()}?", a:"${it.escapeJs()}를 다시 설명해보세요."}""" }
        val editor = if (stage >= 3) """
            <section class="panel">
              <label>질문<input id="q" placeholder="질문" /></label>
              <label>답<input id="a" placeholder="답" /></label>
              <button onclick="addCard()">카드 추가</button>
            </section>
        """ else ""
        return shell(title, request, accent, """
            <header><span>STUDY</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            <section class="flashcard" onclick="flip()">
              <small id="side">QUESTION</small>
              <h2 id="cardText"></h2>
            </section>
            <div class="actions"><button onclick="prev()">이전</button><button onclick="next()">다음</button></div>
            $editor
            <script>
              const key='onion-study-${safeTitle.hashCode()}';
              let cards = JSON.parse(localStorage.getItem(key) || 'null') || [$cards];
              let index=0, answer=false;
              function save(){ ${if (request.useLocalStorage) "localStorage.setItem(key, JSON.stringify(cards));" else ""} }
              function show(){ const c=cards[index]; document.getElementById('side').textContent=answer?'ANSWER':'QUESTION'; document.getElementById('cardText').textContent=answer?c.a:c.q; }
              function flip(){ answer=!answer; show(); }
              function next(){ index=(index+1)%cards.length; answer=false; show(); }
              function prev(){ index=(index-1+cards.length)%cards.length; answer=false; show(); }
              function addCard(){ const q=document.getElementById('q'), a=document.getElementById('a'); if(!q.value.trim()||!a.value.trim())return; cards.push({q:q.value,a:a.value}); q.value=''; a.value=''; save(); index=cards.length-1; answer=false; show(); }
              show();
            </script>
        """.trimIndent())
    }

    private fun gameHtml(title: String, request: AppGenerationRequest, stage: Int): String {
        val accent = accentFor(request.style)
        val safeTitle = title.escapeHtml()
        val safePrompt = request.prompt.escapeHtml()
        val advanced = if (stage >= 3) "target.style.transform='translate('+x+'px,'+y+'px) scale('+scale+')';" else "target.style.transform='translate('+x+'px,'+y+'px)';"
        return shell(title, request, accent, """
            <header><span>GAME</span><h1>$safeTitle</h1><p>$safePrompt</p></header>
            <section class="score"><b id="score">0</b><span>점</span><b id="time">20</b><span>초</span></section>
            <section class="arena" id="arena"><button id="target" onclick="hit()">●</button></section>
            <button onclick="start()">게임 시작</button>
            <script>
              let score=0, time=20, timer=null;
              const target=document.getElementById('target'), arena=document.getElementById('arena');
              function move(){
                const x=Math.random()*(arena.clientWidth-76);
                const y=Math.random()*(arena.clientHeight-76);
                const scale=0.8+Math.random()*0.7;
                $advanced
              }
              function start(){ score=0; time=20; update(); move(); clearInterval(timer); timer=setInterval(()=>{time--; update(); if(time<=0){clearInterval(timer); target.textContent='끝';}},1000); }
              function hit(){ if(time<=0)return; score++; target.textContent=['●','◆','✦','★'][score%4]; update(); move(); }
              function update(){ document.getElementById('score').textContent=score; document.getElementById('time').textContent=time; }
              start();
            </script>
        """.trimIndent())
    }

    private fun shell(
        title: String,
        request: AppGenerationRequest,
        accent: String,
        body: String,
    ): String {
        val bg = when (request.style) {
            AppStyle.Calm -> "#F7F8F1"
            AppStyle.Playful -> "#FFF8EC"
            AppStyle.Focus -> "#F4F7FC"
        }
        val input = when (request.style) {
            AppStyle.Calm -> "#F1F5EB"
            AppStyle.Playful -> "#FFF0D8"
            AppStyle.Focus -> "#EAF0FB"
        }
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <style>
                * { box-sizing:border-box; }
                body { margin:0; font-family:-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background:$bg; color:#101510; }
                main { min-height:100vh; padding:22px; }
                header { margin-bottom:22px; }
                header span { color:$accent; font-weight:900; font-size:12px; letter-spacing:.08em; }
                h1 { font-size:30px; line-height:1.04; margin:8px 0 10px; letter-spacing:0; }
                p { color:#5D6559; line-height:1.48; margin:0; }
                .panel, .flashcard, .arena, .timer-card, article, .score, .stats { background:#fff; border:1px solid #DFE7D8; border-radius:20px; padding:16px; margin-bottom:14px; box-shadow:0 10px 28px rgba(16,21,16,.06); }
                label { display:block; font-size:13px; font-weight:800; margin-bottom:12px; }
                input { width:100%; margin-top:7px; border:1px solid #DFE7D8; background:$input; border-radius:14px; padding:14px; font-size:16px; color:#101510; }
                button { border:0; border-radius:14px; background:$accent; color:white; padding:14px; font-size:16px; font-weight:900; }
                .panel > button, main > button { width:100%; }
                .actions { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin:10px 0 14px; }
                .grid { display:grid; gap:10px; }
                output { display:block; margin-top:14px; padding:16px; border-radius:16px; background:$input; font-weight:900; }
                ul { padding:0; margin:0; list-style:none; display:grid; gap:8px; }
                li { padding:12px; border-radius:12px; background:$input; }
                .list { display:grid; gap:10px; }
                .list article { display:flex; align-items:center; gap:12px; margin:0; }
                .list article button { width:44px; height:44px; padding:0; border-radius:999px; }
                .list .done strong { text-decoration:line-through; color:#7A8375; }
                small, .status, article span { color:#5D6559; }
                .stats, .score { display:grid; grid-template-columns:1fr 1fr 1fr 1fr; align-items:end; gap:8px; text-align:center; }
                .stats b, .score b { font-size:32px; color:$accent; }
                .flashcard { min-height:240px; display:grid; place-items:center; text-align:center; }
                .flashcard h2 { font-size:28px; line-height:1.15; }
                .arena { height:360px; position:relative; overflow:hidden; background:linear-gradient(180deg,#fff,$input); }
                #target { position:absolute; width:72px; height:72px; border-radius:999px; font-size:30px; padding:0; transition:transform .18s ease; }
                .timer-card { display:grid; place-items:center; min-height:220px; text-align:center; }
                #display { font-size:64px; font-weight:900; letter-spacing:0; color:$accent; font-variant-numeric:tabular-nums; }
                #timerState { margin-top:8px; font-weight:800; }
              </style>
            </head>
            <body><main>$body</main></body>
            </html>
        """.trimIndent()
    }

    private fun accentFor(style: AppStyle): String = when (style) {
        AppStyle.Calm -> "#5BAE31"
        AppStyle.Playful -> "#E9822E"
        AppStyle.Focus -> "#3469D8"
    }

    private fun loadSavedApps(): List<SavedMiniApp> {
        val raw = preferences.getString(KEY_APPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                SavedMiniApp(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    prompt = item.getString("prompt"),
                    html = item.getString("html"),
                    category = item.optString("category", AppCategory.Utility.name).toEnum(AppCategory.Utility),
                    style = item.optString("style", AppStyle.Calm.name).toEnum(AppStyle.Calm),
                    useLocalStorage = item.optBoolean("useLocalStorage", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveApps(apps: List<SavedMiniApp>) {
        val array = JSONArray()
        apps.forEach { app ->
            array.put(
                JSONObject()
                    .put("id", app.id)
                    .put("title", app.title)
                    .put("prompt", app.prompt)
                    .put("html", app.html)
                    .put("category", app.category.name)
                    .put("style", app.style.name)
                    .put("useLocalStorage", app.useLocalStorage),
            )
        }
        preferences.edit().putString(KEY_APPS, array.toString()).apply()
    }

    private inline fun <reified T : Enum<T>> String.toEnum(fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == this } ?: fallback
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun String.escapeJs(): String {
        return replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private data class AppProfile(
        val accent: String,
        val background: String,
        val surface: String,
        val input: String,
        val text: String,
        val muted: String,
        val border: String,
        val fields: List<String>,
        val cards: List<String>,
        val cardBody: String,
        val action: String,
    ) {
        companion object {
            fun from(request: AppGenerationRequest): AppProfile {
                val palette = when (request.style) {
                    AppStyle.Calm -> Palette("#5BAE31", "#F7F8F1", "#FFFFFF", "#F1F5EB")
                    AppStyle.Playful -> Palette("#E9822E", "#FFF8EC", "#FFFFFF", "#FFF0D8")
                    AppStyle.Focus -> Palette("#3469D8", "#F4F7FC", "#FFFFFF", "#EAF0FB")
                }
                val fields = when (request.category) {
                    AppCategory.Utility -> listOf("입력값", "조건", "메모", "결과 이름")
                    AppCategory.Tracker -> listOf("오늘의 기록", "목표", "메모", "완료 상태")
                    AppCategory.Study -> listOf("질문", "정답", "힌트", "복습 날짜")
                    AppCategory.Game -> listOf("플레이어", "점수", "라운드", "규칙")
                }
                val cards = request.prompt
                    .split(Regex("[,，.。\\n]"))
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                    .take(4)
                    .ifEmpty {
                        when (request.category) {
                            AppCategory.Utility -> listOf("빠른 계산", "최근 입력", "결과 요약")
                            AppCategory.Tracker -> listOf("오늘", "이번 주", "연속 기록")
                            AppCategory.Study -> listOf("새 카드", "복습", "오답")
                            AppCategory.Game -> listOf("시작", "진행", "결과")
                        }
                    }
                val action = when (request.category) {
                    AppCategory.Utility -> "계산하기"
                    AppCategory.Tracker -> "기록하기"
                    AppCategory.Study -> "카드 추가"
                    AppCategory.Game -> "시작하기"
                }
                return AppProfile(
                    accent = palette.accent,
                    background = palette.background,
                    surface = palette.surface,
                    input = palette.input,
                    text = "#101510",
                    muted = "#5D6559",
                    border = "#DFE7D8",
                    fields = fields,
                    cards = cards,
                    cardBody = "프롬프트를 바탕으로 구성된 영역입니다.",
                    action = action,
                )
            }
        }
    }

    private data class Palette(
        val accent: String,
        val background: String,
        val surface: String,
        val input: String,
    )

    companion object {
        private const val KEY_APPS = "generated_apps"
        private const val NOTIFICATION_CHANNEL_ID = "app_generation"
    }
}

data class AppGenerationState(
    val apps: List<SavedMiniApp> = emptyList(),
    val drafts: List<GeneratedAppDraft> = emptyList(),
    val current: GeneratedAppDraft? = null,
)

data class AppGenerationRequest(
    val prompt: String,
    val category: AppCategory,
    val style: AppStyle,
    val useLocalStorage: Boolean,
)

data class GeneratedAppDraft(
    val id: String,
    val title: String,
    val prompt: String,
    val category: AppCategory,
    val style: AppStyle,
    val useLocalStorage: Boolean,
    val html: String,
    val status: GenerationStatus,
    val progressLabel: String,
) {
    fun toSavedApp(): SavedMiniApp = SavedMiniApp(
        id = id,
        title = title,
        prompt = prompt,
        html = html,
        category = category,
        style = style,
        useLocalStorage = useLocalStorage,
    )
}

data class SavedMiniApp(
    val id: String,
    val title: String,
    val prompt: String,
    val html: String,
    val category: AppCategory = AppCategory.Utility,
    val style: AppStyle = AppStyle.Calm,
    val useLocalStorage: Boolean = true,
)

data class HtmlUpdate(
    val label: String,
    val html: String,
)

enum class GenerationStatus {
    Queued,
    Generating,
    Done,
    Failed,
}

enum class AppCategory(val label: String) {
    Utility("도구"),
    Tracker("기록"),
    Study("학습"),
    Game("게임"),
}

enum class AppStyle(val label: String) {
    Calm("차분하게"),
    Playful("경쾌하게"),
    Focus("집중형"),
}

private enum class PromptIntent {
    Timer,
    Todo,
    Water,
    Game,
    Study,
    Tracker,
    Utility,
}
