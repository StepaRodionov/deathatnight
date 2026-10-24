package com.example.scooterauth

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Приложение для самоката Kugoo Kirin (модель kgkrn14, прошивка BNSH10S10):
 * сканирует BLE, подключается, проходит рукопожатие +VER/+PM/+PA (алгоритм из
 * KugooCrypto, портированный 1:1) и после этого даёт управлять тем, что самокат
 * реально поддерживает: свет, стробоскоп (мигание фарой), гудок (подбор команды),
 * блокировка, круиз, передачи.
 *
 * Скорость/батарея/пробег убраны: прошивка твоего самоката на все запросы этих
 * данных отвечает ERROR, а оригинальное приложение для kgkrn14 их тоже не запрашивает.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Реальные UUID из декомпилированного LFBluetootService.java (kugookirin / kugoo).
        // Индекс в этой карте == "mode" в KugooCrypto, т.к. именно так была
        // устроена CONNECT_TYPE в оригинальном приложении.
        data class KnownService(val mode: Int, val label: String, val service: UUID, val write: UUID, val read: UUID)

        val KNOWN_SERVICES = listOf(
            KnownService(1, "default", UUID.fromString("9546bdc0-60ba-11e9-8647-0002a5d5c51b"),
                UUID.fromString("9546bdc1-60ba-11e9-8647-0002a5d5c51b"),
                UUID.fromString("9546bdc4-60ba-11e9-8647-0002a5d5c51b")),
            KnownService(2, "kugoo", UUID.fromString("b1b99ca0-98e7-11e9-80fe-0002a5d5c51b"),
                UUID.fromString("b1b99ca1-98e7-11e9-80fe-0002a5d5c51b"),
                UUID.fromString("b1b99ca4-98e7-11e9-80fe-0002a5d5c51b")),
            KnownService(3, "fmi", UUID.fromString("331023c0-fdcb-11e8-8eb2-0002a5d5c51b"),
                UUID.fromString("331023c1-fdcb-11e8-8eb2-0002a5d5c51b"),
                UUID.fromString("331023c4-fdcb-11e8-8eb2-0002a5d5c51b")),
        )

        // Запрос большого MTU сразу после подключения: по умолчанию в одну запись BLE
        // помещается только 20 байт, а команды протокола бывают длиннее.
        const val REQUESTED_MTU = 185

        // Самокат отвечает примерно за 1 мс, но реальный цикл BLE (интервал соединения)
        // занимает ~10-30 мс. Это страховочный таймаут, если CODE_NG не пришёл.
        const val CODE_PROBE_TIMEOUT_MS = 50L

        // Кандидаты на команду гудка. В протоколе оригинального приложения Kugoo Kirin
        // команды гудка НЕТ, поэтому это подбор вслепую: только безопасные текстовые
        // команды вида "ключ=1"/"ключ=0" (самокат на неизвестные отвечает ERROR).
        val HORN_CANDIDATES = listOf(
            "+HORN=1" to "+HORN=0",
            "HORN=1" to "HORN=0",
            "+BELL=1" to "+BELL=0",
            "BELL=1" to "BELL=0",
            "+BEEP=1" to "+BEEP=0",
            "+BUZZ=1" to "+BUZZ=0",
            "+SIREN=1" to "+SIREN=0",
            "+ALRM=1" to "+ALRM=0",
            "+FIND=1" to "+FIND=0",
        )

        // Паттерны стробоскопа: список шагов (свет включён?, длительность в "тиках").
        // Длительность тика задаёт ползунок. null вместо шагов = генерируется случайно.
        class StrobePattern(val name: String, val steps: List<Pair<Boolean, Int>>?, val ultra: Boolean = false)

        private fun blink(n: Int, on: Int, off: Int): List<Pair<Boolean, Int>> =
            (1..n).flatMap { listOf(true to on, false to off) }

        val STROBE_PATTERNS = listOf(
            StrobePattern("1. Ровное мигание", listOf(true to 1, false to 1)),
            StrobePattern("2. Короткая вспышка", listOf(true to 1, false to 4)),
            StrobePattern("3. Двойная вспышка", blink(1, 1, 1) + listOf(true to 1, false to 5)),
            StrobePattern("4. Тройная вспышка", blink(2, 1, 1) + listOf(true to 1, false to 6)),
            StrobePattern("5. Полиция (серия + длинные)", blink(3, 1, 1) + listOf(true to 4, false to 4)),
            StrobePattern("6. SOS", blink(3, 1, 1) + blink(3, 3, 1) + blink(2, 1, 1) + listOf(true to 1, false to 6)),
            StrobePattern("7. Пульс (нарастание)", listOf(true to 1, false to 5, true to 2, false to 4,
                true to 3, false to 3, true to 4, false to 2, true to 5, false to 1)),
            StrobePattern("8. Маяк (редкая вспышка)", listOf(true to 1, false to 9)),
            StrobePattern("9. Случайный", null),
            StrobePattern("10. УЛЬТРА-быстро (макс. скорость)", listOf(true to 1, false to 1), ultra = true),
        )

        // Запросы для проверки поддержки: ключ ответа -> команда-запрос.
        val SUPPORT_QUERIES = listOf(
            "MODE" to "+MODE=?",
            "LOCK" to "+LOCK=?",
            "CRZE" to "+CRZE=?",
            "UNIT" to "+UNIT=?",
            "HLGT" to "HLGT=?",
        )
    }

    // ---- Общие элементы ----
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var listDevices: ListView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTabControl: Button
    private lateinit var btnTabDiag: Button
    private lateinit var tabControl: View
    private lateinit var tabDiagnostics: View

    // ---- Вкладка "Диагностика" ----
    private lateinit var btnSend: Button
    private lateinit var btnSendCode: Button
    private lateinit var btnHandshake: Button
    private lateinit var editManual: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var checkAutoReply: CheckBox
    private lateinit var tvTelemetry: TextView

    // ---- Вкладка "Управление" ----
    private lateinit var tvAuthState: TextView
    private lateinit var checkBinaryLight: CheckBox
    private lateinit var btnLightOn: Button
    private lateinit var btnLightOff: Button
    private lateinit var btnStrobe: Button
    private lateinit var spinnerStrobe: Spinner
    private lateinit var tvLockState: TextView
    private lateinit var tvCruiseState: TextView
    private lateinit var editCode: EditText
    private lateinit var btnCodeSend: Button
    private lateinit var checkAutoCode: CheckBox
    private lateinit var tvCodeState: TextView
    private lateinit var btnCodeList: Button
    private lateinit var btnCodeBrute: Button
    private lateinit var btnLockDiag: Button
    private lateinit var tvLockDiag: TextView
    private lateinit var tvStrobeSpeed: TextView
    private lateinit var seekStrobe: SeekBar
    private lateinit var spinnerHorn: Spinner
    private lateinit var btnHorn: Button
    private lateinit var btnHornProbe: Button
    private lateinit var btnHornFound: Button
    private lateinit var tvHornProbe: TextView
    private lateinit var btnLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnCruiseOn: Button
    private lateinit var btnCruiseOff: Button
    private lateinit var btnGearUp: Button
    private lateinit var btnGearDown: Button
    private lateinit var btnCheckSupport: Button
    private lateinit var tvSupport: TextView

    // Текущая передача (+MODE=<n>): кнопки шлют +1/-1, диапазон ограничивает сама железка.
    private var gearNum = 1

    // Известное состояние света (из нашей команды или push-сообщения HLGT=0/1 от самоката).
    private var lightOn: Boolean? = null

    // Разобранные текстовые поля протокола вида "+КЛЮЧ=значение".
    private val telemetry = linkedMapOf<String, String>()

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val scanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val scanResults = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // Чтобы discoverServices() вызывался ровно один раз на соединение
    // (после ответа на запрос MTU либо по таймауту, если самокат MTU не поддержал).
    @Volatile private var servicesRequested = false

    // ---- Очередь GATT-операций (и запись в характеристику, и запись дескриптора) ----
    // BluetoothGatt позволяет иметь только одну GATT-операцию "в полёте" на всё соединение.
    // Вторая, вызванная до колбэка на первую, молча теряется — поэтому ВСЕ операции
    // идут через эту очередь и ждут подтверждения предыдущей.
    private sealed class GattOp {
        data class CharWrite(
            val ch: BluetoothGattCharacteristic,
            val bytes: ByteArray,
            val text: String,
            val quiet: Boolean = false,
            val noResp: Boolean = false
        ) : GattOp()
        data class DescWrite(val desc: BluetoothGattDescriptor, val value: ByteArray, val label: String) : GattOp()
    }
    // Явно kotlin.collections.ArrayDeque: в файле есть "import java.util.*", который иначе
    // перекрыл бы простое имя "ArrayDeque" java-версией без addLast/removeFirstOrNull.
    private val gattQueue = kotlin.collections.ArrayDeque<GattOp>()
    private var gattBusy = false
    private var currentOpQuiet = false

    // Writable характеристики для ручного выбора
    private val writableChars = mutableListOf<BluetoothGattCharacteristic>()
    private var selectedWriteChar: BluetoothGattCharacteristic? = null

    // Заполняется, если среди сервисов самоката найден один из известных (KNOWN_SERVICES):
    // тогда mode для KugooCrypto берётся отсюда автоматически, а не из радио-кнопок.
    private var detectedMode: Int? = null
    private var detectedLabel: String? = null
    private var detectedWriteChar: BluetoothGattCharacteristic? = null
    private var detectedReadChar: BluetoothGattCharacteristic? = null

    // Полный протокол взаимного рукопожатия (+VER/+PM/+PA), см. KugooAuth.kt
    private val auth: KugooAuth by lazy {
        KugooAuth(
            mode = { selectedMode() },
            sender = { text -> sendAuthString(text) },
            log = { msg -> log(msg) }
        )
    }
    private var wasAuthenticated = false

    // ---- Стробоскоп ----
    private var strobeOn = false
    private var strobePhase = false
    private var strobeIntervalMs = 200L
    private var lightBeforeStrobe: Boolean? = null
    private var strobeSteps: List<Pair<Boolean, Int>> = listOf(true to 1, false to 1)
    private var strobeStep = 0
    private var strobeUltra = false
    private var strobePatternIdx = 0

    // ---- Гудок ----
    private var hornIdx = 0
    private var hornProbeRunning = false
    private var hornProbeIdx = -1
    private val hornProbeReplies = StringBuffer()

    // ---- Скрипт команд (тест блокировки/круиза) ----
    private var scriptRunning = false
    private val scriptReplies = StringBuffer()
    private var codeSentCount = 0

    // ---- Подбор пароля CODE= по списку через запятую ----
    private var codeProbeList: List<String> = emptyList()
    private var codeProbeRunning = false
    private var codeProbeIdx = -1
    private var codeProbeStepRunnable: Runnable? = null
    // 0 = перебор по введённому списку; N = перебор всех N-значных кодов (000..99..9) без ручного ввода
    private var codeProbeGenDigits = 0
    // С какого индекса начинать перебор (сохраняется, чтобы продолжить после остановки)
    private var codeProbeStart = 0
    private var codeProbeAwaiting = false

    // ---- Проверка поддержки команд ----
    private var supportChecking = false
    private val supportSeen = mutableSetOf<String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val permissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startScan()
        } else {
            log("Нет нужных разрешений (Bluetooth/Location).")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        // Метка сборки — чтобы по логу сразу видеть, актуальный ли APK установлен
        // (меняй число при следующих правках подбора пароля, если снова будет непонятно,
        // обновился ли APK после пересборки на GitHub Actions).
        log("Сборка: подбор пароля v4 (без паузы после NG, таймаут=50мс)")
        listDevices = findViewById(R.id.listDevices)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnTabControl = findViewById(R.id.btnTabControl)
        btnTabDiag = findViewById(R.id.btnTabDiag)
        tabControl = findViewById(R.id.tabControl)
        tabDiagnostics = findViewById(R.id.tabDiagnostics)

        btnSend = findViewById(R.id.btnSend)
        btnSendCode = findViewById(R.id.btnSendCode)
        btnHandshake = findViewById(R.id.btnHandshake)
        editManual = findViewById(R.id.editManual)
        radioMode = findViewById(R.id.radioMode)
        checkAutoReply = findViewById(R.id.checkAutoReply)
        tvTelemetry = findViewById(R.id.tvTelemetry)

        tvAuthState = findViewById(R.id.tvAuthState)
        checkBinaryLight = findViewById(R.id.checkBinaryLight)
        btnLightOn = findViewById(R.id.btnLightOn)
        btnLightOff = findViewById(R.id.btnLightOff)
        btnStrobe = findViewById(R.id.btnStrobe)
        spinnerStrobe = findViewById(R.id.spinnerStrobe)
        tvLockState = findViewById(R.id.tvLockState)
        tvCruiseState = findViewById(R.id.tvCruiseState)
        editCode = findViewById(R.id.editCode)
        btnCodeSend = findViewById(R.id.btnCodeSend)
        checkAutoCode = findViewById(R.id.checkAutoCode)
        tvCodeState = findViewById(R.id.tvCodeState)
        btnCodeList = findViewById(R.id.btnCodeList)
        btnCodeBrute = findViewById(R.id.btnCodeBrute)
        btnLockDiag = findViewById(R.id.btnLockDiag)
        tvLockDiag = findViewById(R.id.tvLockDiag)
        tvStrobeSpeed = findViewById(R.id.tvStrobeSpeed)
        seekStrobe = findViewById(R.id.seekStrobe)
        spinnerHorn = findViewById(R.id.spinnerHorn)
        btnHorn = findViewById(R.id.btnHorn)
        btnHornProbe = findViewById(R.id.btnHornProbe)
        btnHornFound = findViewById(R.id.btnHornFound)
        tvHornProbe = findViewById(R.id.tvHornProbe)
        btnLock = findViewById(R.id.btnLock)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnCruiseOn = findViewById(R.id.btnCruiseOn)
        btnCruiseOff = findViewById(R.id.btnCruiseOff)
        btnGearUp = findViewById(R.id.btnGearUp)
        btnGearDown = findViewById(R.id.btnGearDown)
        btnCheckSupport = findViewById(R.id.btnCheckSupport)
        tvSupport = findViewById(R.id.tvSupport)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listDevices.adapter = deviceAdapter

        btnScan.setOnClickListener { requestPermissionsAndScan() }
        btnDisconnect.setOnClickListener { disconnectGatt() }
        btnSend.setOnClickListener { sendManual() }
        btnSendCode.setOnClickListener { sendCode() }
        btnHandshake.setOnClickListener { startHandshake() }
        btnTabControl.setOnClickListener { showTab(control = true) }
        btnTabDiag.setOnClickListener { showTab(control = false) }

        // ---- Свет ----
        btnLightOn.setOnClickListener { stopStrobe(restore = false); setLight(true) }
        btnLightOff.setOnClickListener { stopStrobe(restore = false); setLight(false) }

        // ---- Стробоскоп ----
        seekStrobe.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                strobeIntervalMs = 20L + progress * 10L
                tvStrobeSpeed.text = "Интервал: $strobeIntervalMs мс"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        strobeIntervalMs = 20L + seekStrobe.progress * 10L
        tvStrobeSpeed.text = "Интервал: $strobeIntervalMs мс"
        val strobeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, STROBE_PATTERNS.map { it.name }
        )
        strobeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStrobe.adapter = strobeAdapter
        spinnerStrobe.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                strobePatternIdx = position
                // Если стробоскоп уже идёт — переключаем паттерн на лету.
                if (strobeOn) applyStrobePattern()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnStrobe.setOnClickListener { if (strobeOn) stopStrobe(restore = true) else startStrobe() }

        // ---- Гудок ----
        val prefs = getSharedPreferences("scooter", MODE_PRIVATE)
        hornIdx = prefs.getInt("horn_idx", 0).coerceIn(0, HORN_CANDIDATES.size - 1)
        val hornAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            HORN_CANDIDATES.map { "${it.first}  /  ${it.second}" }
        )
        hornAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerHorn.adapter = hornAdapter
        spinnerHorn.setSelection(hornIdx, false)
        spinnerHorn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                hornIdx = position
                prefs.edit().putInt("horn_idx", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Нажал — отправили "включить", отпустил — "выключить".
        btnHorn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    sendCommand(HORN_CANDIDATES[hornIdx].first)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    sendCommand(HORN_CANDIDATES[hornIdx].second)
                }
            }
            true
        }
        btnHornProbe.setOnClickListener { if (hornProbeRunning) stopHornProbe("Подбор остановлен.") else startHornProbe() }
        btnHornFound.setOnClickListener { hornFound() }

        // ---- Блокировка, круиз, передачи (строки из VehicleFragment.java оригинала) ----
        // После каждой команды сразу спрашиваем состояние ("=?"), как это делает оригинал,
        // чтобы по строке состояния было видно, принял ли самокат команду.
        btnLock.setOnClickListener { sendCommand("+LOCK=1"); sendCommand("+LOCK=?") }
        btnUnlock.setOnClickListener { sendCommand("+LOCK=0"); sendCommand("+LOCK=?") }
        btnCruiseOn.setOnClickListener { sendCommand("+CRZE=1"); sendCommand("+CRZE=?") }
        btnCruiseOff.setOnClickListener { sendCommand("+CRZE=0"); sendCommand("+CRZE=?") }
        editCode.setText(prefs.getString("code", "") ?: "")
        checkAutoCode.isChecked = prefs.getBoolean("auto_code", false)
        checkAutoCode.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("auto_code", checked).apply() }
        btnCodeSend.setOnClickListener { sendPasswordCode() }
        codeProbeList = parseCodeList(prefs.getString("code_list", "") ?: "")
        codeProbeGenDigits = prefs.getInt("code_gen_digits", 0)
        codeProbeStart = prefs.getInt("code_gen_next", 0)
        updateCodeBruteButtonLabel()
        btnCodeList.setOnClickListener { showCodeSourceDialog() }
        btnCodeBrute.setOnClickListener { if (codeProbeRunning) stopCodeProbe("Подбор остановлен вручную.") else startCodeProbe() }
        btnLockDiag.setOnClickListener {
            runCommandScript(
                "Тест блокировки и круиза (каждая команда — пауза 1.5 с, смотри ответ):",
                listOf("+LOCK=?", "+CRZE=?", "+LOCK=0", "+LOCK=?", "+CRZE=1", "+CRZE=?", "+CRZE=0", "+CRZE=?"),
                1500L, tvLockDiag
            )
        }
        btnGearUp.setOnClickListener {
            gearNum++
            sendCommand("+MODE=$gearNum")
        }
        btnGearDown.setOnClickListener {
            gearNum = (gearNum - 1).coerceAtLeast(1)
            sendCommand("+MODE=$gearNum")
        }
        btnCheckSupport.setOnClickListener { checkSupport() }

        listDevices.setOnItemClickListener { _, _, position, _ ->
            val name = deviceAdapter.getItem(position) ?: return@setOnItemClickListener
            val address = name.substringAfterLast("(").removeSuffix(")")
            val device = scanResults[address] ?: return@setOnItemClickListener
            stopScan()
            connect(device)
        }

        updateAuthState()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        strobeOn = false
        hornProbeRunning = false
        handler.removeCallbacksAndMessages(null)
        val g = gatt
        try { g?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null
        super.onDestroy()
    }

    // Если среди сервисов самоката нашёлся один из известных (KNOWN_SERVICES),
    // используем его mode автоматически; иначе — то, что выбрано вручную в радио-кнопках.
    private fun selectedMode(): Int = detectedMode ?: when (radioMode.checkedRadioButtonId) {
        R.id.radioMode2 -> 2
        R.id.radioMode3 -> 3
        else -> 1
    }

    // ---------- Разрешения и сканирование ----------

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScan()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            log("Bluetooth выключен. Включи его и попробуй снова.")
            return
        }
        showTab(control = false)
        scanResults.clear()
        deviceAdapter.clear()
        scanning = true
        tvStatus.text = "Статус: сканирование..."
        log("Сканирование запущено")
        scanner.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) {
            scanner.stopScan(scanCallback)
            scanning = false
            tvStatus.text = "Статус: сканирование остановлено (${scanResults.size} устройств)"
            log("Сканирование остановлено")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            if (!scanResults.containsKey(address)) {
                scanResults[address] = device
                val name = try { device.name ?: "(без имени)" } catch (e: SecurityException) { "(без имени)" }
                deviceAdapter.add("$name  rssi=${result.rssi}  ($address)")
                deviceAdapter.notifyDataSetChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Скан не удался, код $errorCode")
        }
    }

    // ---------- Подключение и GATT ----------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        log("Подключаюсь к ${device.address}...")
        tvStatus.text = "Статус: подключение к ${device.address}"
        resetSessionState()
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Сброс всего, что относится к конкретному соединению (без закрытия gatt). */
    private fun resetSessionState() {
        stopStrobe(restore = false)
        if (hornProbeRunning) stopHornProbe("Подбор прерван (нет соединения).")
        if (codeProbeRunning) stopCodeProbe("Подбор пароля прерван (нет соединения).")
        writableChars.clear()
        selectedWriteChar = null
        detectedMode = null
        detectedLabel = null
        detectedWriteChar = null
        detectedReadChar = null
        auth.reset()
        wasAuthenticated = false
        telemetry.clear()
        gearNum = 1
        lightOn = null
        gattQueue.clear()
        gattBusy = false
        currentOpQuiet = false
        servicesRequested = false
        supportChecking = false
        supportSeen.clear()
        scriptRunning = false
        codeSentCount = 0
        tvLockState.text = "Блокировка: неизвестно"
        tvCruiseState.text = "Круиз: неизвестно"
        tvCodeState.text = "Пароль: не отправлялся"
        tvTelemetry.text = "(пока нет данных — подключись и дождись рукопожатия)"
        tvSupport.text = ""
        for (b in listOf(btnLock, btnUnlock, btnCruiseOn, btnCruiseOff, btnGearUp, btnGearDown)) b.alpha = 1f
        updateAuthState()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        resetSessionState()
        btnDisconnect.isEnabled = false
        tvStatus.text = "Статус: отключено"
        log("Отключено вручную")
    }

    @SuppressLint("MissingPermission")
    private fun discoverOnce(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        log("Запрашиваю сервисы (discoverServices)...")
        g.discoverServices()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread {
                        tvStatus.text = "Статус: подключено, согласую MTU..."
                        btnDisconnect.isEnabled = true
                    }
                    servicesRequested = false
                    log("Connected (status=$status). Запрашиваю MTU=$REQUESTED_MTU...")
                    // ВАЖНО: сначала MTU, и только потом discoverServices(). С MTU по умолчанию
                    // (23 байта => 20 байт полезных данных) длинная запись, например "+PA<"+код,
                    // не помещается в один пакет и Android отвечает status=13
                    // (GATT_INVALID_ATTRIBUTE_LENGTH) — рукопожатие рвалось "через раз".
                    val asked = g.requestMtu(REQUESTED_MTU)
                    if (!asked) {
                        log("requestMtu отклонён системой — иду дальше без него.")
                        discoverOnce(g)
                    } else {
                        // Страховка: если самокат не ответит на запрос MTU, не зависаем.
                        handler.postDelayed({ if (gatt != null) discoverOnce(g) }, 2000)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected (status=$status)")
                    runOnUiThread {
                        stopStrobe(restore = false)
                        if (hornProbeRunning) stopHornProbe("Подбор прерван (соединение разорвано).")
                        if (codeProbeRunning) stopCodeProbe("Подбор пароля прерван (соединение разорвано).")
                        tvStatus.text = "Статус: соединение разорвано (status=$status)"
                        btnDisconnect.isEnabled = false
                        wasAuthenticated = false
                        updateAuthState()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU: $mtu (status=$status, полезных данных на запись: ${mtu - 3} байт)")
            discoverOnce(g)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("discoverServices failed: $status")
                return
            }
            // Высокий приоритет соединения = короткий интервал связи (~11-15 мс вместо 30-50):
            // нужен, чтобы стробоскоп мог мигать быстро.
            val prio = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            log("requestConnectionPriority(HIGH) -> $prio")
            writableChars.clear()
            detectedMode = null
            detectedLabel = null
            detectedWriteChar = null
            detectedReadChar = null
            log("Найдено сервисов: ${g.services.size}")
            for (service in g.services) {
                log("Service ${service.uuid}")
                for (ch in service.characteristics) {
                    val props = describeProps(ch.properties)
                    log("  Char ${ch.uuid}  [$props]")
                    if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        writableChars.add(ch)
                    }
                    if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        enableNotify(g, ch)
                    }

                    // Совпадение с известным протоколом kugookirin (см. LFBluetootService.java)
                    val known = KNOWN_SERVICES.firstOrNull { it.write == ch.uuid }
                    if (known != null) {
                        detectedMode = known.mode
                        detectedLabel = known.label
                        detectedWriteChar = ch
                        log("  -> Распознан известный протокол '${known.label}', mode=${known.mode} (write-характеристика)")
                    }
                    val knownRead = KNOWN_SERVICES.firstOrNull { it.read == ch.uuid }
                    if (knownRead != null) {
                        detectedReadChar = ch
                        log("  -> Распознана известная read/notify-характеристика '${knownRead.label}'")
                    }
                }
            }
            runOnUiThread {
                val modeInfo = if (detectedMode != null) ", распознан протокол '$detectedLabel' (mode=$detectedMode)" else ""
                tvStatus.text = "Статус: подключено, сервисов ${g.services.size}, writable=${writableChars.size}$modeInfo"
                // Приоритет: известная write-характеристика -> иначе первая попавшаяся writable.
                selectedWriteChar = detectedWriteChar ?: writableChars.firstOrNull()
                if (selectedWriteChar != null) {
                    log("Для записи выбрана характеристика: ${selectedWriteChar?.uuid}")
                }
                if (checkAutoReply.isChecked && selectedWriteChar != null) {
                    handler.postDelayed({ startHandshake() }, 500)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd == null) {
                log("  !! У ${ch.uuid} нет дескриптора 0x2902 — включить notify нельзя")
                return
            }
            val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            // Не пишем дескриптор напрямую — кладём в общую GATT-очередь (см. выше, почему).
            gattQueue.addLast(GattOp.DescWrite(cccd, value, "CCCD(notify) на ${ch.uuid}"))
            pumpGattQueue()
        }

        @Deprecated("Deprecated in API33, handled below for older APIs")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleIncoming(g, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(g, characteristic, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!currentOpQuiet || status != BluetoothGatt.GATT_SUCCESS) {
                log("Write to ${characteristic.uuid} -> status=$status")
            }
            gattBusy = false
            pumpGattQueue()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            log("Descriptor ${descriptor.uuid} на ${descriptor.characteristic?.uuid} -> status=$status ${if (ok) "(notify включён)" else "(ОШИБКА — notify НЕ включён на этой характеристике!)"}")
            gattBusy = false
            pumpGattQueue()
        }
    }

    private fun describeProps(props: Int): String {
        val parts = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return parts.joinToString(",")
    }

    private fun handleIncoming(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val hex = value.joinToString(" ") { String.format("%02X", it) }
        // Полный текст без фильтрации непечатных символов — у протокола фиксированная
        // длина строк (например "+PM>" + 12 hex = ровно 16 символов), это важно для сверки.
        val rawText = value.toString(Charsets.UTF_8).trimEnd('\u0000')
        val upper = rawText.trim().uppercase()

        // Во время стробоскопа самокат шлёт push "HLGT=0/1" на каждое мигание — не засоряем лог.
        val noisy = strobeOn && upper.startsWith("HLGT")
        // Во время подбора пароля ответы CODE_NG не пишем в лог (их будет миллионы).
        val probeNg = codeProbeRunning && upper == "CODE_NG"
        if (!noisy && !probeNg) log("<< ${characteristic.uuid}  hex=[$hex]  text=\"$rawText\"")

        if (hornProbeRunning) hornProbeReplies.append(rawText.trim()).append(' ')
        if (scriptRunning && !noisy) scriptReplies.append(rawText.trim()).append(' ')

        when (upper) {
            "CODE_OK" -> {
                log("Самокат принял пароль (CODE_OK).")
                if (codeProbeRunning) {
                    val found = codeProbeCandidate(codeProbeIdx)
                    runOnUiThread {
                        editCode.setText(found)
                        getSharedPreferences("scooter", MODE_PRIVATE).edit().putString("code", found).apply()
                        stopCodeProbe("ПАРОЛЬ НАЙДЕН: \"$found\" (CODE_OK) — вписан в поле выше.", resetProgress = true)
                    }
                } else {
                    runOnUiThread { tvCodeState.text = "Пароль: ПРИНЯТ (CODE_OK)" }
                }
            }
            "CODE_NG" -> {
                if (codeProbeRunning) {
                    if (codeProbeAwaiting) {
                        codeProbeAwaiting = false
                        runOnUiThread { nextCodeProbeStep() }
                    }
                } else {
                    log("Самокат отклонил пароль (CODE_NG). Попробуй другой код.")
                    runOnUiThread { tvCodeState.text = "Пароль: ОТКЛОНЁН (CODE_NG) — неверный" }
                }
            }
        }

        if (checkAutoReply.isChecked) {
            auth.onMessage(rawText)
            if (auth.isMutualAuthentication() && !wasAuthenticated) {
                wasAuthenticated = true
                runOnUiThread {
                    updateAuthState()
                    showTab(control = true) // авторизация пройдена — переходим к управлению
                    // Один раз, без повторов: неверный пароль 3 раза подряд оригинал считает ошибкой.
                    if (checkAutoCode.isChecked && editCode.text.isNotBlank()) {
                        handler.postDelayed({ sendPasswordCode() }, 700)
                    }
                }
            }
        }

        // Телеметрию/статусы разбираем всегда, вне зависимости от авто-рукопожатия.
        parseTelemetry(rawText)
    }

    private fun updateAuthState() {
        tvAuthState.text = if (auth.isMutualAuthentication())
            "Авторизация: ПРОЙДЕНА — команды принимаются"
        else
            "Авторизация: нет (подключись к самокату на вкладке «Диагностика»)"
    }

    // ---------- Разбор входящих полей ----------

    private val knownFieldNames = mapOf(
        "MODE" to "Передача/режим (+MODE)",
        "UNIT" to "Единицы (0=км/ч,1=mph)",
        "HLGT" to "Фара",
        "LOCK" to "Блокировка (1=заблок.)",
        "CRZE" to "Круиз-контроль",
        "SPDLT" to "Ограничение скорости",
        "SPLT" to "Speed limit flag",
        "DRIVMD" to "Режим привода",
        "MDMX" to "Макс. режим разблокирован",
        "ZSST" to "Старт с нуля",
        "STBY" to "Standby"
    )

    /** Разбор "+КЛЮЧ=значение" или "КЛЮЧ=значение" (HLGT шлётся без "+"). */
    private val telemetryRegex = Regex("^\\+?([A-Z0-9]{2,10})=(.*)$")

    private fun parseTelemetry(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val m = telemetryRegex.find(text.uppercase()) ?: return
        val key = m.groupValues[1]
        val value = m.groupValues[2]
        if (key in listOf("PM", "PA", "VER", "CODE_OK", "CODE_NG", "CODE")) return // это уже обработано auth-протоколом
        telemetry[key] = value
        if (supportChecking) supportSeen.add(key)

        // Запоминаем состояние, которое реально присылает самокат.
        if (key == "HLGT") {
            when (value.trim()) {
                "1" -> lightOn = true
                "0" -> lightOn = false
            }
        }
        if (key == "LOCK" || key == "CRZE") {
            val v = value.trim()
            val state = when (v) {
                "1" -> if (key == "LOCK") "ЗАБЛОКИРОВАН" else "ВКЛЮЧЁН"
                "0" -> if (key == "LOCK") "разблокирован" else "выключен"
                else -> "ответ \"$v\""
            }
            runOnUiThread {
                if (key == "LOCK") {
                    tvLockState.text = "Блокировка: $state"
                } else {
                    tvCruiseState.text = "Круиз: $state"
                }
            }
        }
        if (key == "MODE") value.trim().toIntOrNull()?.let { if (it in 1..9) gearNum = it }

        if (strobeOn && key == "HLGT") return // не перерисовываем UI на каждое мигание
        // Текст собираем здесь же, в потоке, который меняет telemetry (иначе возможен
        // ConcurrentModificationException при обходе карты из UI-потока).
        val sb = StringBuilder()
        for ((k, v) in telemetry.entries.toList()) {
            val label = knownFieldNames[k]
            if (label != null) sb.append("$label: $v\n") else sb.append("$k = $v\n")
        }
        val shown = sb.toString().ifEmpty { "(пока нет данных)" }
        runOnUiThread { tvTelemetry.text = shown }
    }

    // ---------- Вкладки ----------

    private fun showTab(control: Boolean) {
        tabControl.visibility = if (control) View.VISIBLE else View.GONE
        tabDiagnostics.visibility = if (control) View.GONE else View.VISIBLE
    }

    // ---------- Свет ----------

    /** Включает/выключает фару (текстом HLGT=1/0 либо бинарным кадром, если стоит галка). */
    private fun setLight(on: Boolean, quiet: Boolean = false, fast: Boolean = false) {
        lightOn = on
        if (checkBinaryLight.isChecked) {
            sendHex(if (on) "AA070601AABB" else "AA070600ABBB", quiet, fast)
        } else {
            sendCommand(if (on) "HLGT=1" else "HLGT=0", quiet, fast)
        }
    }

    // ---------- Стробоскоп ----------
    // В протоколе самоката отдельной команды "стробоскоп" нет. Единственный реально
    // доступный способ — быстро включать и выключать фару (HLGT=1/0) по заданному паттерну.
    // Для высокого темпа используется запись без подтверждения (WRITE_NO_RESPONSE) и
    // высокий приоритет соединения; если очередь не успевает, шаг ждёт, а не пропускается.

    private val strobeTick = object : Runnable {
        override fun run() {
            if (!strobeOn) return
            if (gatt == null) {
                stopStrobe(restore = false)
                return
            }
            // Очередь ещё не разгребла прошлые записи — ждём, шаг паттерна НЕ пропускаем,
            // иначе мигание "съедет" и паттерн исказится.
            if (gattBusy || gattQueue.size > 1) {
                handler.postDelayed(this, 4)
                return
            }
            val (on, ticks) = strobeSteps[strobeStep % strobeSteps.size]
            strobeStep++
            // "Быстрая" запись (WRITE_NO_RESPONSE, если характеристика её поддерживает) —
            // не ждёт подтверждения самоката, поэтому темп ограничен только радиоканалом.
            val fast = strobeUltra || strobeIntervalMs < 100
            setLight(on, quiet = true, fast = fast)
            val delay = if (strobeUltra) 8L else ticks * strobeIntervalMs
            handler.postDelayed(this, delay)
        }
    }

    /** Выбирает шаги по текущему паттерну (для "Случайного" — генерирует новую последовательность). */
    private fun applyStrobePattern() {
        val p = STROBE_PATTERNS[strobePatternIdx]
        strobeUltra = p.ultra
        strobeSteps = p.steps ?: run {
            val rnd = Random()
            (1..8).flatMap { listOf(true to (1 + rnd.nextInt(3)), false to (1 + rnd.nextInt(5))) }
        }
        strobeStep = 0
    }

    private fun startStrobe() {
        if (gatt == null) {
            log("Стробоскоп: нет подключения.")
            return
        }
        lightBeforeStrobe = lightOn
        applyStrobePattern()
        strobeOn = true
        btnStrobe.text = "Стробоскоп: СТОП"
        log("Стробоскоп включён: ${STROBE_PATTERNS[strobePatternIdx].name}, тик $strobeIntervalMs мс")
        handler.removeCallbacks(strobeTick)
        handler.post(strobeTick)
    }

    /** restore=true — вернуть фару в состояние, которое было до стробоскопа. */
    private fun stopStrobe(restore: Boolean) {
        if (!strobeOn) return
        strobeOn = false
        handler.removeCallbacks(strobeTick)
        btnStrobe.text = "Стробоскоп: СТАРТ"
        log("Стробоскоп выключен")
        if (restore && gatt != null) setLight(lightBeforeStrobe ?: false)
    }

    // ---------- Гудок: подбор команды ----------

    private fun startHornProbe() {
        if (gatt == null) {
            tvHornProbe.text = "Сначала подключись к самокату."
            return
        }
        hornProbeRunning = true
        hornProbeIdx = -1
        btnHornProbe.text = "Остановить подбор"
        btnHornFound.isEnabled = true
        log("Подбор команды гудка: старт")
        nextHornProbeStep()
    }

    private fun nextHornProbeStep() {
        if (!hornProbeRunning) return
        hornProbeIdx++
        if (hornProbeIdx >= HORN_CANDIDATES.size) {
            stopHornProbe("Перебрал все варианты — гудок не найден. Скорее всего, в этой прошивке гудка по Bluetooth нет.")
            return
        }
        val idx = hornProbeIdx
        val (on, off) = HORN_CANDIDATES[idx]
        hornProbeReplies.setLength(0)
        tvHornProbe.text = "Пробую ${idx + 1}/${HORN_CANDIDATES.size}: $on — СЛУШАЙ самокат. Услышал сигнал — жми «Это он!»"
        sendCommand(on)
        handler.postDelayed({
            if (hornProbeRunning && idx == hornProbeIdx) sendCommand(off)
        }, 800)
        handler.postDelayed({
            if (hornProbeRunning && idx == hornProbeIdx) {
                val replies = hornProbeReplies.toString().trim().ifEmpty { "тишина" }
                log("Подбор гудка: $on -> ответ самоката: $replies")
                nextHornProbeStep()
            }
        }, 3000)
    }

    private fun stopHornProbe(message: String) {
        hornProbeRunning = false
        btnHornProbe.text = "Подобрать команду"
        btnHornFound.isEnabled = false
        tvHornProbe.text = message
        log("Подбор команды гудка: $message")
    }

    private fun hornFound() {
        if (!hornProbeRunning || hornProbeIdx !in HORN_CANDIDATES.indices) return
        val idx = hornProbeIdx
        // Гасим текущую команду на всякий случай, останавливаем перебор и запоминаем выбор.
        sendCommand(HORN_CANDIDATES[idx].second)
        stopHornProbe("Выбрано: ${HORN_CANDIDATES[idx].first}. Проверь кнопкой «ГУДОК». Если сигнал был от предыдущего варианта — выбери его в списке.")
        spinnerHorn.setSelection(idx, false)
        hornIdx = idx
        getSharedPreferences("scooter", MODE_PRIVATE).edit().putInt("horn_idx", idx).apply()
    }

    // ---------- Пароль подключения и скрипт-диагностика ----------

    private fun sendPasswordCode() {
        if (gatt == null) {
            tvCodeState.text = "Пароль: нет подключения"
            return
        }
        val code = editCode.text.toString().trim()
        if (code.isEmpty()) {
            tvCodeState.text = "Пароль: введи код"
            return
        }
        getSharedPreferences("scooter", MODE_PRIVATE).edit().putString("code", code).apply()
        codeSentCount++
        tvCodeState.text = "Пароль: отправлен ($codeSentCount), жду ответ..."
        sendCommand("CODE=$code")
    }

    // ---------- Подбор пароля CODE= по списку (через запятую) ----------

    /** Режет строку по запятым, обрезает пробелы, выкидывает пустые, убирает дубликаты. */
    private fun parseCodeList(raw: String): List<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun updateCodeBruteButtonLabel() {
        btnCodeBrute.text = when {
            codeProbeRunning -> "Остановить подбор"
            codeProbeGenDigits > 0 -> "Начать подбор (${codeProbeTotal()} шт., с ${codeProbeCandidate(minOf(codeProbeStart, codeProbeTotal() - 1))})"
            else -> "Начать подбор (${codeProbeList.size})"
        }
    }

    /** Сколько всего кандидатов: для генератора 10^N, для списка — его размер. */
    private fun codeProbeTotal(): Int =
        if (codeProbeGenDigits > 0) {
            var t = 1
            repeat(codeProbeGenDigits) { t *= 10 }
            t
        } else codeProbeList.size

    /** Кандидат с индексом idx: для генератора — число с ведущими нулями, для списка — элемент. */
    private fun codeProbeCandidate(idx: Int): String =
        if (codeProbeGenDigits > 0) idx.toString().padStart(codeProbeGenDigits, '0')
        else codeProbeList[idx]

    /**
     * Кнопка «Список паролей...»: либо вручную ввести список, либо перебрать
     * все коды заданной длины (4, 5 или 6 цифр) без какого-либо ввода.
     */
    private fun showCodeSourceDialog() {
        val items = arrayOf(
            "Ввести список вручную",
            "Перебор: все 4-значные (0000–9999)",
            "Перебор: все 5-значные (00000–99999)",
            "Перебор: все 6-значные (000000–999999, 1 000 000 шт.)"
        )
        AlertDialog.Builder(this)
            .setTitle("Источник паролей")
            .setItems(items) { _, which ->
                val prefsEdit = getSharedPreferences("scooter", MODE_PRIVATE).edit()
                val newDigits = if (which == 0) 0 else which + 3
                if (newDigits != codeProbeGenDigits) {
                    // Сменили источник — начинаем с нуля
                    codeProbeStart = 0
                }
                codeProbeGenDigits = newDigits
                prefsEdit.putInt("code_gen_digits", codeProbeGenDigits)
                    .putInt("code_gen_next", codeProbeStart).apply()
                if (which == 0) {
                    showCodeListDialog()
                } else {
                    tvCodeState.text = "Перебор ${codeProbeGenDigits}-значных кодов: готов. Нажми «Начать подбор»."
                }
                updateCodeBruteButtonLabel()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Кнопка 1: диалог с большим многострочным полем — вставляешь сколько угодно
     * паролей через запятую ("1234,000000,112233,..."), список сохраняется в
     * настройках и используется кнопкой "Начать подбор".
     */
    private fun showCodeListDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            hint = "например: 000000, 123456, 1234, 112233, 888888"
            setText(codeProbeList.joinToString(", "))
            setSelection(text.length)
        }
        val padding = (16 * density).toInt()
        // Поле кладём в ScrollView с ЖЁСТКО заданной высотой (а не wrap_content) —
        // иначе при большом списке паролей EditText растягивается на весь текст и
        // выталкивает кнопки "Сохранить"/"Отмена" диалога за пределы экрана.
        // Внутри этой высоты текст сам прокручивается, сам диалог больше не растёт.
        val maxHeightPx = (320 * density).toInt()
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxHeightPx)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, padding)
            addView(scroll)
        }
        AlertDialog.Builder(this)
            .setTitle("Список паролей через запятую")
            .setMessage("Вставь сколько угодно вариантов пароля через запятую. Кнопка «Начать подбор» будет слать их по очереди как CODE=<пароль> и остановится, как только самокат ответит CODE_OK.")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val list = parseCodeList(input.text.toString())
                codeProbeList = list
                getSharedPreferences("scooter", MODE_PRIVATE).edit()
                    .putString("code_list", list.joinToString(",")).apply()
                updateCodeBruteButtonLabel()
                tvCodeState.text = "Список паролей сохранён: ${list.size} шт."
                log("Список паролей для подбора обновлён: ${list.size} шт.")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Кнопка 2: запускает перебор списка, пока не придёт CODE_OK или список не кончится. */
    private fun startCodeProbe() {
        if (gatt == null) {
            tvCodeState.text = "Подбор: сначала подключись к самокату."
            return
        }
        val total = codeProbeTotal()
        if (total == 0) {
            tvCodeState.text = "Подбор: сначала выбери источник («Список паролей...»)."
            return
        }
        if (codeProbeStart >= total) codeProbeStart = 0
        codeProbeRunning = true
        codeProbeIdx = codeProbeStart - 1
        updateCodeBruteButtonLabel()
        log("Подбор пароля CODE=: старт с ${codeProbeCandidate(codeProbeStart)}, всего кандидатов — $total")
        nextCodeProbeStep()
    }

    private fun nextCodeProbeStep() {
        if (!codeProbeRunning) return
        if (gatt == null) {
            stopCodeProbe("Подбор прерван (нет соединения).")
            return
        }
        codeProbeStepRunnable?.let { handler.removeCallbacks(it) }

        codeProbeIdx++
        val total = codeProbeTotal()
        if (codeProbeIdx >= total) {
            stopCodeProbe("Перебрал все $total кодов — совпадение не найдено.", resetProgress = true)
            return
        }
        val idx = codeProbeIdx
        val pwd = codeProbeCandidate(idx)
        tvCodeState.text = "Подбор ${idx + 1}/$total: пробую \"$pwd\"..."
        sendCommand("CODE=$pwd", quiet = true, fast = true)

        codeProbeAwaiting = true
        val stepRunnable = Runnable {
            if (codeProbeRunning && codeProbeAwaiting && idx == codeProbeIdx) {
                codeProbeAwaiting = false
                nextCodeProbeStep()
            }
        }
        codeProbeStepRunnable = stepRunnable
        handler.postDelayed(stepRunnable, CODE_PROBE_TIMEOUT_MS)
    }

    /**
     * Останавливает подбор. В режиме генератора запоминает, где остановились, чтобы
     * при следующем запуске продолжить с того же места (resetProgress — начать заново).
     * Последний кандидат пишем повторно: мог остаться без ответа.
     */
    private fun stopCodeProbe(message: String, resetProgress: Boolean = false) {
        codeProbeAwaiting = false
        codeProbeRunning = false
        codeProbeStepRunnable?.let { handler.removeCallbacks(it) }
        codeProbeStepRunnable = null
        if (codeProbeGenDigits > 0) {
            codeProbeStart = if (resetProgress) 0 else maxOf(codeProbeIdx, 0)
            getSharedPreferences("scooter", MODE_PRIVATE).edit()
                .putInt("code_gen_next", codeProbeStart).apply()
        }
        updateCodeBruteButtonLabel()
        tvCodeState.text = message
        log("Подбор пароля CODE=: $message")
    }

    /**
     * Шлёт список команд с паузой между ними и для каждой показывает, что самокат
     * ответил в течение паузы. Нужен, чтобы своими глазами увидеть реакцию самоката.
     */
    private fun runCommandScript(title: String, cmds: List<String>, gapMs: Long, out: TextView) {
        if (gatt == null) {
            out.text = "Сначала подключись к самокату."
            return
        }
        if (scriptRunning) return
        scriptRunning = true
        scriptReplies.setLength(0)
        val report = StringBuilder(title).append('\n')
        cmds.forEachIndexed { i, cmd ->
            handler.postDelayed({
                if (!scriptRunning) return@postDelayed
                if (i > 0) report.append("   <- ").append(scriptReplies.toString().trim().ifEmpty { "тишина" }).append('\n')
                scriptReplies.setLength(0)
                report.append("-> ").append(cmd).append('\n')
                out.text = report.toString()
                sendCommand(cmd)
            }, i * gapMs)
        }
        handler.postDelayed({
            if (!scriptRunning) return@postDelayed
            report.append("   <- ").append(scriptReplies.toString().trim().ifEmpty { "тишина" }).append('\n')
            report.append("Готово. Если везде «ERROR» или «тишина» — самокат не принимает эти команды по Bluetooth.")
            out.text = report.toString()
            log(report.toString())
            scriptRunning = false
        }, cmds.size * gapMs)
    }

    // ---------- Проверка поддержки команд ----------

    private fun checkSupport() {
        if (gatt == null) {
            tvSupport.text = "Сначала подключись к самокату."
            return
        }
        supportSeen.clear()
        supportChecking = true
        tvSupport.text = "Проверяю..."
        for ((_, cmd) in SUPPORT_QUERIES) sendCommand(cmd)
        handler.postDelayed({ finishSupportCheck() }, 4000)
    }

    private fun finishSupportCheck() {
        supportChecking = false
        val sb = StringBuilder()
        for ((key, cmd) in SUPPORT_QUERIES) {
            val ok = key in supportSeen
            val v = telemetry[key]
            sb.append(if (ok) "[+] " else "[-] ").append(cmd).append(" → ")
            sb.append(if (ok) "ответил: $v" else "нет ответа (ERROR или тишина)").append('\n')
        }
        sb.append("\nНе ответившие функции подсвечены бледнее. Кнопки остаются нажимаемыми:\nсамокат мог просто не отвечать на запрос, но выполнять команду.")
        tvSupport.text = sb.toString()
        fun dim(key: String, vararg buttons: Button) {
            val a = if (key in supportSeen || key in telemetry) 1f else 0.45f
            buttons.forEach { it.alpha = a }
        }
        dim("LOCK", btnLock, btnUnlock)
        dim("CRZE", btnCruiseOn, btnCruiseOff)
        dim("MODE", btnGearUp, btnGearDown)
    }

    // ---------- Отправка ----------

    @SuppressLint("MissingPermission")
    private fun sendCommand(text: String, quiet: Boolean = false, fast: Boolean = false) {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Не могу отправить \"$text\": нет подключения или writable-характеристики.")
            return
        }
        writeBytes(g, target, text.toByteArray(Charsets.ISO_8859_1), quiet, fast)
    }

    @SuppressLint("MissingPermission")
    private fun sendHex(hex: String, quiet: Boolean = false, fast: Boolean = false) {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Не могу отправить hex \"$hex\": нет подключения или writable-характеристики.")
            return
        }
        val clean = hex.replace(" ", "")
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        writeBytes(g, target, bytes, quiet, fast)
    }

    private fun sendAuthString(text: String) = sendCommand(text)

    /** Запускает полное рукопожатие +VER/+PM/+PA с нуля. */
    private fun startHandshake() {
        if (gatt == null) {
            log("Сначала подключись к устройству.")
            return
        }
        auth.reset()
        wasAuthenticated = false
        updateAuthState()
        auth.start()
    }

    /** Кладёт запись в общую GATT-очередь — см. pumpGattQueue(). */
    private fun writeBytes(
        g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray,
        quiet: Boolean = false, fast: Boolean = false
    ) {
        val noResp = fast && (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        gattQueue.addLast(GattOp.CharWrite(ch, bytes, bytes.toString(Charsets.ISO_8859_1), quiet, noResp))
        pumpGattQueue()
    }

    /**
     * Выполняет следующую операцию из очереди (запись характеристики ИЛИ запись
     * дескриптора/notify), только если предыдущая уже подтвердилась колбэком
     * (onCharacteristicWrite / onDescriptorWrite) или истёк страховочный таймаут
     * (на случай, если колбэк вообще не придёт).
     */
    @SuppressLint("MissingPermission")
    private fun pumpGattQueue() {
        if (gattBusy) return
        val g = gatt
        if (g == null) {
            gattQueue.clear()
            return
        }
        val op = gattQueue.removeFirstOrNull() ?: return
        gattBusy = true
        currentOpQuiet = (op as? GattOp.CharWrite)?.quiet ?: false

        val accepted: Boolean = when (op) {
            is GattOp.CharWrite -> {
                if (!op.quiet) {
                    log(">> ${op.ch.uuid}  \"${op.text}\"  hex=[${op.bytes.joinToString(" ") { String.format("%02X", it) }}]")
                }
                val wt = if (op.noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                         else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(op.ch, op.bytes, wt) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    op.ch.value = op.bytes
                    @Suppress("DEPRECATION")
                    op.ch.writeType = wt
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(op.ch)
                }
            }
            is GattOp.DescWrite -> {
                log(">> ${op.label}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(op.desc, op.value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    op.desc.value = op.value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(op.desc)
                }
            }
        }

        if (!accepted) {
            // GATT-очередь занята (обычно — предыдущая операция ещё не завершилась на
            // системном уровне) — не теряем операцию, а пробуем ещё раз чуть позже.
            if (!currentOpQuiet) log("   (GATT отклонил операцию, повтор через 150мс)")
            gattQueue.addFirst(op)
            gattBusy = false
            handler.postDelayed({ pumpGattQueue() }, 150)
            return
        }

        // Страховка: если колбэк почему-то не придёт вовсе, очередь не должна встать намертво.
        handler.postDelayed({
            if (gattBusy) {
                gattBusy = false
                pumpGattQueue()
            }
        }, 800)
    }

    // ---------- Ручная отправка ----------

    @SuppressLint("MissingPermission")
    private fun sendManual() {
        val g = gatt
        if (g == null) {
            log("Сначала подключись к устройству.")
            return
        }
        val target = selectedWriteChar ?: writableChars.firstOrNull()
        if (target == null) {
            log("Нет writable-характеристики.")
            return
        }
        val input = editManual.text.toString().trim()
        if (input.isEmpty()) return

        // Если ввели ровно 12 hex-символов — считаем это nonce и автоматически считаем ответ.
        val isHexNonce = input.length == 12 && input.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
        if (isHexNonce) {
            val mode = selectedMode()
            val reply = KugooCrypto.encryptToken(input.uppercase(), mode)
            log("Ручной nonce=$input, mode=$mode -> ответ=$reply")
            writeBytes(g, target, "+PM<$reply".toByteArray(Charsets.ISO_8859_1))
        } else {
            // иначе отправляем как есть (текстом)
            writeBytes(g, target, input.toByteArray(Charsets.ISO_8859_1))
        }
    }

    /**
     * Отдельный, независимый от +PM канал: оригинальное приложение шлёт текстом
     * "CODE=<пароль>" и ждёт в ответ "CODE_OK"/"CODE_NG". Пароль — из поля ручной отправки.
     */
    @SuppressLint("MissingPermission")
    private fun sendCode() {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Нет подключения или writable-характеристики.")
            return
        }
        val code = editManual.text.toString().trim().ifEmpty { "000000" }
        writeBytes(g, target, "CODE=$code".toByteArray(Charsets.ISO_8859_1))
    }

    // ---------- Логирование ----------

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        Log.d("ScooterAuth", line)
        runOnUiThread {
            tvLog.append(line + "\n")
        }
    }
}
