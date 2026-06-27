package com.example.nfc_reader

import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nfc_reader.ui.theme.NFC_ReaderTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// --- 数据模型 ---

data class Transaction(
    val date: String,
    val amount: Int,
    val balance: Int
)

data class SuicaCard(
    val idm: String,
    var balance: Int,
    var lastUpdated: Long,
    var history: List<Transaction>
)

enum class Screen {
    Cards, Scan, Detail
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    
    // 全局状态管理
    private val scannedCards = mutableStateListOf<SuicaCard>()
    private var currentScreen by mutableStateOf(Screen.Cards) // 默认改到卡包页
    private var selectedCard by mutableStateOf<SuicaCard?>(null)
    private var isScanning by mutableStateOf(false)
    private var showScanSheet by mutableStateOf(false) // 控制仿真弹窗显示

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        enableEdgeToEdge()
        loadCards() // 加载保存的卡片
        
        setContent {
            NFC_ReaderTheme {
                MainLayout()
            }
        }
    }

    private fun saveCards() {
        val sharedPreferences = getSharedPreferences("NFC_Reader_Prefs", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()
        val json = gson.toJson(scannedCards.toList())
        editor.putString("scanned_cards", json)
        editor.apply()
    }

    private fun loadCards() {
        val sharedPreferences = getSharedPreferences("NFC_Reader_Prefs", MODE_PRIVATE)
        val json = sharedPreferences.getString("scanned_cards", null)
        if (json != null) {
            val gson = Gson()
            val type = object : TypeToken<List<SuicaCard>>() {}.type
            val cards: List<SuicaCard> = gson.fromJson(json, type)
            scannedCards.clear()
            scannedCards.addAll(cards)
        }
    }

    @Composable
    fun MainLayout() {
        val sheetState = rememberModalBottomSheetState()
        
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentScreen == Screen.Cards || currentScreen == Screen.Detail,
                        onClick = { 
                            currentScreen = Screen.Cards
                            showScanSheet = false 
                        },
                        icon = { Icon(Icons.Default.CreditCard, contentDescription = "卡包") },
                        label = { Text("卡包") }
                    )
                    NavigationBarItem(
                        selected = showScanSheet,
                        onClick = { showScanSheet = true },
                        icon = { Icon(Icons.Default.Nfc, contentDescription = "扫描") },
                        label = { Text("扫描") }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    Screen.Cards -> CardsScreen(scannedCards) { card ->
                        selectedCard = card
                        currentScreen = Screen.Detail
                    }
                    Screen.Scan -> { /* Scan 现在由 BottomSheet 处理，背景显示卡包 */ }
                    Screen.Detail -> selectedCard?.let { 
                        CardDetailScreen(it) { currentScreen = Screen.Cards }
                    }
                }
                
                // --- 仿 iOS NFC 扫描弹窗 ---
                if (showScanSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showScanSheet = false },
                        sheetState = sheetState,
                        dragHandle = { BottomSheetDefaults.DragHandle() },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    ) {
                        ScanSheetContent(isScanning)
                    }
                }
            }
        }
        
        // 处理返回键
        if (currentScreen == Screen.Detail) {
            BackHandler { currentScreen = Screen.Cards }
        }
    }

    @Composable
    fun ScanSheetContent(scanning: Boolean) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp, top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "准备扫描",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            // 扫描动画/图标区域
            Surface(
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Nfc,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (scanning) "正在读取..." else "请将卡片靠近手机背面",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { showScanSheet = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.width(120.dp)
            ) {
                Text("取消")
            }
        }
    }

    // --- 生命周期与 NFC 处理 ---

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val nfcF = NfcF.get(tag) ?: return
        try {
            isScanning = true
            nfcF.connect()
            val idm = tag.id.joinToString("") { "%02X".format(it) }
            val serviceCode = byteArrayOf(0x0F.toByte(), 0x09.toByte())
            
            val command = createReadCommand(tag.id, serviceCode, 10)
            val response = nfcF.transceive(command)

            if (response.size >= 13) {
                val numBlocks = response[12].toInt() and 0xFF
                val history = mutableListOf<Transaction>()
                
                for (i in 0 until numBlocks) {
                    val offset = 13 + (i * 16)
                    if (offset + 16 > response.size) break
                    val block = response.copyOfRange(offset, offset + 16)
                    val balance = ((block[11].toInt() and 0xFF) shl 8) or (block[10].toInt() and 0xFF)
                    
                    val dateInt = ((block[4].toInt() and 0xFF) shl 8) or (block[5].toInt() and 0xFF)
                    val dateStr = "%02d/%02d".format((dateInt shr 5) and 0x0F, dateInt and 0x1F)
                    
                    var amount = 0
                    if (offset + 32 <= response.size) {
                        val prevBalance = ((response[offset + 16 + 11].toInt() and 0xFF) shl 8) or 
                                          (response[offset + 16 + 10].toInt() and 0xFF)
                        amount = balance - prevBalance
                    }
                    history.add(Transaction(dateStr, amount, balance))
                }

                val latestBalance = if (history.isNotEmpty()) history[0].balance else 0
                val newCard = SuicaCard(idm, latestBalance, System.currentTimeMillis(), history)

                runOnUiThread {
                    val existingIndex = scannedCards.indexOfFirst { it.idm == idm }
                    if (existingIndex != -1) {
                        scannedCards[existingIndex] = newCard
                    } else {
                        scannedCards.add(0, newCard)
                    }
                    saveCards() // 保存更新后的列表
                    selectedCard = newCard
                    showScanSheet = false // 成功后关闭弹窗
                    currentScreen = Screen.Detail // 跳转到详情
                    notifySuccess()
                }
            }
        } catch (e: Exception) {
            Log.e("NFC", "Read Error", e)
        } finally {
            isScanning = false
            try { nfcF.close() } catch (_: Exception) {}
        }
    }

    private fun notifySuccess() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }

    private fun createReadCommand(idm: ByteArray, service: ByteArray, count: Int): ByteArray {
        val cmd = mutableListOf<Byte>()
        cmd.add(0x00) // len
        cmd.add(0x06) // read
        cmd.addAll(idm.toList())
        cmd.add(0x01) // service count
        cmd.add(service[0]); cmd.add(service[1])
        cmd.add(count.toByte())
        for (i in 0 until count) { cmd.add(0x80.toByte()); cmd.add(i.toByte()) }
        val bytes = cmd.toByteArray()
        bytes[0] = bytes.size.toByte()
        return bytes
    }
}

// --- UI 界面组件 ---

@Composable
fun ScanScreen(isScanning: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(200.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = if (isScanning) "正在读取..." else "准备扫描",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "请将 Suica 卡靠近手机背面 NFC 区域",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun CardsScreen(cards: List<SuicaCard>, onCardClick: (SuicaCard) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "我的卡包",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        if (cards.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无已扫描的卡片", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cards) { card ->
                    CardItem(card, onCardClick)
                }
            }
        }
    }
}

@Composable
fun CardItem(card: SuicaCard, onClick: (SuicaCard) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick(card) },
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(Color(0xFF2E7D32), Color(0xFF81C784))))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Suica", color = Color.White, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.CreditCard, contentDescription = null, tint = Color.White)
                }
                Text(
                    text = "ID: ${card.idm.take(8)}...",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "¥${card.balance}",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun CardDetailScreen(card: SuicaCard, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(start = 8.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)
        ) {
            Column {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text("余额详情", style = MaterialTheme.typography.labelLarge)
                    Text("¥${card.balance}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                    Text("卡号: ${card.idm}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        Text(
            text = "消费记录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
            fontWeight = FontWeight.Bold
        )
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(card.history) { trans ->
                ListItem(
                    headlineContent = { 
                        Text(if (trans.amount >= 0) "充值" else "支出", fontWeight = FontWeight.Bold) 
                    },
                    supportingContent = { Text(trans.date) },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (trans.amount >= 0) "+¥${trans.amount}" else "-¥${Math.abs(trans.amount)}",
                                color = if (trans.amount >= 0) Color(0xFF2E7D32) else Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                            Text("余¥${trans.balance}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}
