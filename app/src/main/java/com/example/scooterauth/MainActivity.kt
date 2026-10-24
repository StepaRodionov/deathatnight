package com.example.scooterauth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Приложение только для теста: сканирует BLE, подключается к выбранному
 * устройству, показывает все сервисы/характеристики, подписывается на
 * уведомления и умеет как автоматически, так и вручную отвечать тем
 * хэшем, который считает KugooCrypto (твой алгоритм, портированный 1:1).
 *
 * UUID сервисов/характеристик конкретно твоей модели самоката неизвестны,
 * поэтому приложение не "зашивает" их, а показывает всё, что отдаёт GATT,
 * и даёт тебе самому выбрать нужную характеристику для записи, либо делает
 * это эвристически (первая writable-характеристика в том же сервисе, что
 * и notify, из которой пришёл "+PM>").
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
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var listDevices: ListView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSend: Button
    private lateinit var btnSendCode: Button
    private lateinit var btnHandshake: Button
    private lateinit var editManual: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var checkAutoReply: CheckBox
    private lateinit var tvTelemetry: TextView
    private lateinit var checkBinaryLight: CheckBox
    private lateinit var btnLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnCruiseOn: Button
    private lateinit var btnCruiseOff: Button
    private lateinit var btnLightOn: Button
    private lateinit var btnLightOff: Button
    private lateinit var btnGearUp: Button
    private lateinit var btnGearDown: Button
    private lateinit var btnQueryAll: Button
    private lateinit var btnTabDiag: Button
    private lateinit var btnTabDash: Button
    private lateinit var tabDiagnostics: View
    private lateinit var tabDashboard: View
    private lateinit var tvDashSpeed: TextView
    private lateinit var tvDashBattery: TextView
    private lateinit var tvDashRange: TextView
    private lateinit var tvDashRaw: TextView
    private lateinit var spinnerSpeedKey: Spinner
    private lateinit var spinnerBatteryKey: Spinner
    private lateinit var spinnerRangeKey: Spinner

    // Текущая раскодированная передача (для +MODE=<n>, см. gearUp/gearDown в VehicleFragment.java
    // оригинального приложения: шлют +MODE=<текущая+-1>, сама железка ограничивает диапазон).
    private var gearNum = 1

    // Разобранные известные текстовые поля протокола (см. parseStrData() в декомпилированном
    // VehicleFragment.java): MODE (передача), UNIT (единицы км/ч или mph), HLGT (свет),
    // LOCK (блокировка), CRZE (круиз-контроль), SPDLT (ограничение скорости), DRIVMD (режим привода).
    // Важно: сама строка со скоростью/батареей/температурой/ошибками в оригинале шлётся
    // периодическим "push"-сообщением, разбор которого не восстановился из декомпиляции
    // (сломанный метод onReceive), поэтому ниже — универсальный парсер "+КЛЮЧ=значение",
    // который подхватит и эти поля, если конкретно твой самокат использует текстовый, а не
    // бинарный (AA..BB) вариант протокола. Если нет — их формат придётся вычислять по сырым
    // hex-кадрам в логе ниже (см. README, раздел "Телеметрия").
    private val telemetry = linkedMapOf<String, String>()

    // Ручное сопоставление "какое поле телеметрии — это скорость/батарея/остаток хода"
    // (см. вкладку "Приборная панель"). null, пока пользователь не выбрал.
    private var speedKey: String? = null
    private var batteryKey: String? = null
    private var rangeKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val scanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val scanResults = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // ---- Очередь GATT-операций (и запись в характеристику, и запись дескриптора) ----
    // БАГ, который чинит эта очередь: BluetoothGatt позволяет иметь только одну
    // GATT-операцию "в полёте" одновременно для ВСЕГО соединения — будь то запись
    // характеристики (writeCharacteristic) или запись дескриптора (writeDescriptor,
    // которым включаются уведомления/notify). Если вызвать вторую операцию до того,
    // как пришёл колбэк на первую, Android тихо отклоняет вторую — без исключения,
    // просто ничего не происходит. Раньше это ломало "Опросить статус" (7 команд
    // подряд), а ЕЩЁ раньше — саму подписку на уведомления: при обнаружении сервисов
    // enableNotify() вызывался в цикле для каждой характеристики с NOTIFY/INDICATE
    // синхронно, без очереди, так что если у самоката больше одной такой
    // характеристики (а так обычно и есть — помимо нужной, почти всегда есть ещё
    // системные вроде Device Information/Battery Service), включалась только первая,
    // а остальные (возможно, как раз та, что реально шлёт телеметрию) — молча нет.
    // Теперь вообще любая GATT-операция идёт через эту очередь.
    private sealed class GattOp {
        data class CharWrite(val ch: BluetoothGattCharacteristic, val bytes: ByteArray, val text: String) : GattOp()
        data class DescWrite(val desc: BluetoothGattDescriptor, val value: ByteArray, val label: String) : GattOp()
    }
    // Явно kotlin.collections.ArrayDeque: в файле есть "import java.util.*", который иначе
    // перекрыл бы простое имя "ArrayDeque" java-версией без addLast/removeFirstOrNull.
    private val gattQueue = kotlin.collections.ArrayDeque<GattOp>()
    private var gattBusy = false

    // Writable характеристики по serviceUuid -> список характеристик, для ручного выбора
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        listDevices = findViewById(R.id.listDevices)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSend = findViewById(R.id.btnSend)
        btnSendCode = findViewById(R.id.btnSendCode)
        btnHandshake = findViewById(R.id.btnHandshake)
        editManual = findViewById(R.id.editManual)
        radioMode = findViewById(R.id.radioMode)
        checkAutoReply = findViewById(R.id.checkAutoReply)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        checkBinaryLight = findViewById(R.id.checkBinaryLight)
        btnLock = findViewById(R.id.btnLock)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnCruiseOn = findViewById(R.id.btnCruiseOn)
        btnCruiseOff = findViewById(R.id.btnCruiseOff)
        btnLightOn = findViewById(R.id.btnLightOn)
        btnLightOff = findViewById(R.id.btnLightOff)
        btnGearUp = findViewById(R.id.btnGearUp)
        btnGearDown = findViewById(R.id.btnGearDown)
        btnQueryAll = findViewById(R.id.btnQueryAll)
        btnTabDiag = findViewById(R.id.btnTabDiag)
        btnTabDash = findViewById(R.id.btnTabDash)
        tabDiagnostics = findViewById(R.id.tabDiagnostics)
        tabDashboard = findViewById(R.id.tabDashboard)
        tvDashSpeed = findViewById(R.id.tvDashSpeed)
        tvDashBattery = findViewById(R.id.tvDashBattery)
        tvDashRange = findViewById(R.id.tvDashRange)
        tvDashRaw = findViewById(R.id.tvDashRaw)
        spinnerSpeedKey = findViewById(R.id.spinnerSpeedKey)
        spinnerBatteryKey = findViewById(R.id.spinnerBatteryKey)
        spinnerRangeKey = findViewById(R.id.spinnerRangeKey)

        setupKeySpinner(spinnerSpeedKey) { speedKey = it; refreshDashboard() }
        setupKeySpinner(spinnerBatteryKey) { batteryKey = it; refreshDashboard() }
        setupKeySpinner(spinnerRangeKey) { rangeKey = it; refreshDashboard() }

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listDevices.adapter = deviceAdapter

        btnScan.setOnClickListener { requestPermissionsAndScan() }
        btnDisconnect.setOnClickListener { disconnectGatt() }
        btnSend.setOnClickListener { sendManual() }
        btnSendCode.setOnClickListener { sendCode() }
        btnHandshake.setOnClickListener { startHandshake() }

        // ---- Команды управления (строки/hex-кадры взяты из декомпиляции
        // VehicleFragment.java оригинального приложения Kugoo Kirin) ----
        btnLock.setOnClickListener { sendCommand("+LOCK=1") }
        btnUnlock.setOnClickListener { sendCommand("+LOCK=0") }
        btnCruiseOn.setOnClickListener { sendCommand("+CRZE=1") }
        btnCruiseOff.setOnClickListener { sendCommand("+CRZE=0") }
        btnLightOn.setOnClickListener {
            if (checkBinaryLight.isChecked) sendHex("AA070601AABB") else sendCommand("HLGT=1")
        }
        btnLightOff.setOnClickListener {
            if (checkBinaryLight.isChecked) sendHex("AA070600ABBB") else sendCommand("HLGT=0")
        }
        btnGearUp.setOnClickListener {
            gearNum++
            sendCommand("+MODE=$gearNum")
        }
        btnGearDown.setOnClickListener {
            gearNum = (gearNum - 1).coerceAtLeast(1)
            sendCommand("+MODE=$gearNum")
        }
        btnQueryAll.setOnClickListener {
            // Запросы статуса "=?" — самокат должен ответить тем же ключом со значением
            // (см. parseStrData: MODE/UNIT/LOCK/CRZE/SPLT/MDMX реально обрабатываются).
            // Порядок и паузы между записями теперь гарантирует сама очередь (pumpGattQueue),
            // поэтому просто кладём все команды в неё подряд.
            listOf("+MODE=?", "+LOCK=?", "+UNIT=?", "+CRZE=?", "+SPDLT=?", "HLGT=?", "+MDMX=?")
                .forEach { cmd -> sendCommand(cmd) }
        }

        btnTabDiag.setOnClickListener { showTab(diagnostics = true) }
        btnTabDash.setOnClickListener { showTab(diagnostics = false) }

        listDevices.setOnItemClickListener { _, _, position, _ ->
            val name = deviceAdapter.getItem(position) ?: return@setOnItemClickListener
            val address = name.substringAfterLast("(").removeSuffix(")")
            val device = scanResults[address] ?: return@setOnItemClickListener
            stopScan()
            connect(device)
        }
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
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writableChars.clear()
        selectedWriteChar = null
        detectedMode = null
        detectedLabel = null
        detectedWriteChar = null
        detectedReadChar = null
        auth.reset()
        telemetry.clear()
        gearNum = 1
        gattQueue.clear()
        gattBusy = false
        speedKey = null
        batteryKey = null
        rangeKey = null
        tvTelemetry.text = "(пока нет данных — подключись и дождись рукопожатия)"
        updateKeySpinners()
        refreshDashboard()
        btnDisconnect.isEnabled = false
        tvStatus.text = "Статус: отключено"
        log("Отключено вручную")
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread {
                        tvStatus.text = "Статус: подключено, ищу сервисы..."
                        btnDisconnect.isEnabled = true
                    }
                    log("Connected (status=$status). Discovering services...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread {
                        tvStatus.text = "Статус: соединение разорвано (status=$status)"
                        btnDisconnect.isEnabled = false
                    }
                    log("Disconnected (status=$status)")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("discoverServices failed: $status")
                return
            }
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
            log("Write to ${characteristic.uuid} -> status=$status")
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
        log("<< ${characteristic.uuid}  hex=[$hex]  text=\"$rawText\"")

        when (rawText.trim().uppercase()) {
            "CODE_OK" -> log("Самокат принял пароль (CODE_OK). Можно слать команды, например GETDEVID.")
            "CODE_NG" -> log("Самокат отклонил пароль (CODE_NG). Попробуй другой код в поле ниже.")
        }

        if (checkAutoReply.isChecked) {
            auth.onMessage(rawText)
        }

        // Телеметрию/статусы разбираем всегда, вне зависимости от авто-рукопожатия.
        parseTelemetry(rawText)
    }

    // ---------- Телеметрия ----------

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

    /**
     * Универсальный разбор входящих строк вида "+КЛЮЧ=значение" или "КЛЮЧ=значение"
     * (HLGT шлётся без "+"). Скорость/батарея/пробег/температура/ошибки у части моделей
     * Kugoo идут именно так периодическим push-сообщением — если это твой случай, они
     * появятся здесь автоматически под своим именем. Если твой самокат использует бинарный
     * протокол (кадры "AA ... BB"), сюда ничего не попадёт и поля нужно будет вычислять
     * по сырым hex-кадрам в логе (см. README).
     */
    private val telemetryRegex = Regex("^\\+?([A-Z0-9]{2,10})=(.*)$")

    private fun parseTelemetry(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val m = telemetryRegex.find(text.uppercase()) ?: return
        val key = m.groupValues[1]
        val value = m.groupValues[2]
        if (key in listOf("PM", "PA", "VER", "CODE_OK", "CODE_NG", "CODE")) return // это уже обработано auth-протоколом
        val isNewKey = key !in telemetry
        telemetry[key] = value
        runOnUiThread {
            val sb = StringBuilder()
            for ((k, v) in telemetry) {
                val label = knownFieldNames[k]
                if (label != null) sb.append("$label: $v\n") else sb.append("$k = $v   (неизвестное поле — похоже на скорость/батарею/температуру/ошибку?)\n")
            }
            tvTelemetry.text = sb.toString().ifEmpty { "(пока нет данных)" }

            if (isNewKey) {
                updateKeySpinners()
                // Первая попытка автоугадать по названию поля (можно переназначить вручную
                // на вкладке "Приборная панель", если угадалось не то).
                if (speedKey == null && Regex("SPD|SPEED|^WS$|VEL").containsMatchIn(key)) speedKey = key
                if (batteryKey == null && Regex("BAT|SOC|PWR").containsMatchIn(key)) batteryKey = key
                if (rangeKey == null && Regex("RANGE|MIL|ODO|DIS|KM").containsMatchIn(key)) rangeKey = key
                selectSpinnerValue(spinnerSpeedKey, speedKey)
                selectSpinnerValue(spinnerBatteryKey, batteryKey)
                selectSpinnerValue(spinnerRangeKey, rangeKey)
            }
            refreshDashboard()
        }
    }

    // ---------- Вкладки и приборная панель ----------

    private fun showTab(diagnostics: Boolean) {
        tabDiagnostics.visibility = if (diagnostics) View.VISIBLE else View.GONE
        tabDashboard.visibility = if (diagnostics) View.GONE else View.VISIBLE
    }

    private val NONE_OPTION = "(не выбрано)"

    private fun setupKeySpinner(spinner: Spinner, onSelected: (String?) -> Unit) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf(NONE_OPTION))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val item = adapter.getItem(position)
                onSelected(if (item == null || item == NONE_OPTION) null else item)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Пересобирает список доступных ключей во всех трёх спиннерах, сохраняя текущий выбор. */
    private fun updateKeySpinners() {
        val options = mutableListOf(NONE_OPTION) + telemetry.keys
        for (spinner in listOf(spinnerSpeedKey, spinnerBatteryKey, spinnerRangeKey)) {
            @Suppress("UNCHECKED_CAST")
            val adapter = spinner.adapter as ArrayAdapter<String>
            val current = adapter.getItem(spinner.selectedItemPosition)
            adapter.clear()
            adapter.addAll(options)
            adapter.notifyDataSetChanged()
            if (current != null) {
                val idx = options.indexOf(current)
                if (idx >= 0) spinner.setSelection(idx, false)
            }
        }
    }

    private fun selectSpinnerValue(spinner: Spinner, value: String?) {
        @Suppress("UNCHECKED_CAST")
        val adapter = spinner.adapter as? ArrayAdapter<String> ?: return
        val target = value ?: NONE_OPTION
        val idx = (0 until adapter.count).firstOrNull { adapter.getItem(it) == target } ?: return
        spinner.setSelection(idx, false)
    }

    private fun refreshDashboard() {
        tvDashSpeed.text = speedKey?.let { telemetry[it] } ?: "—"
        tvDashBattery.text = batteryKey?.let { telemetry[it] } ?: "—"
        tvDashRange.text = rangeKey?.let { telemetry[it] } ?: "—"
        val sb = StringBuilder()
        for ((k, v) in telemetry) sb.append("$k = $v\n")
        tvDashRaw.text = sb.toString().ifEmpty { "(пока нет данных)" }
    }

    // ---------- Команды ----------

    @SuppressLint("MissingPermission")
    private fun sendCommand(text: String) {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Не могу отправить \"$text\": нет подключения или writable-характеристики.")
            return
        }
        writeBytes(g, target, text.toByteArray(Charsets.ISO_8859_1))
    }

    @SuppressLint("MissingPermission")
    private fun sendHex(hex: String) {
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
        writeBytes(g, target, bytes)
    }

    @SuppressLint("MissingPermission")
    private fun sendAuthString(text: String) {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Не могу отправить \"$text\": нет подключения или writable-характеристики.")
            return
        }
        writeBytes(g, target, text.toByteArray(Charsets.ISO_8859_1))
    }

    /** Запускает полное рукопожатие +VER/+PM/+PA с нуля. */
    private fun startHandshake() {
        if (gatt == null) {
            log("Сначала подключись к устройству.")
            return
        }
        auth.reset()
        auth.start()
    }

    /** Теперь это просто кладёт запись в общую GATT-очередь — см. pumpGattQueue(). */
    private fun writeBytes(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray) {
        gattQueue.addLast(GattOp.CharWrite(ch, bytes, bytes.toString(Charsets.ISO_8859_1)))
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

        val accepted: Boolean = when (op) {
            is GattOp.CharWrite -> {
                log(">> ${op.ch.uuid}  \"${op.text}\"  hex=[${op.bytes.joinToString(" ") { String.format("%02X", it) }}]")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(op.ch, op.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    op.ch.value = op.bytes
                    @Suppress("DEPRECATION")
                    op.ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
            log("   (GATT отклонил операцию, повтор через 150мс)")
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
     * "CODE=<пароль>" (или "CODE=000000" для одного из вариантов протокола) и ждёт
     * в ответ "CODE_OK"/"CODE_NG". Бери пароль из поля ручной отправки.
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
