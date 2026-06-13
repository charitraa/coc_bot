import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Lock to portrait
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const CocBotApp());
}

// ─── Platform Channels ────────────────────────────────────────────────────────

const _methodCh = MethodChannel('com.cocbot/bot');
const _eventCh  = EventChannel('com.cocbot/logs');

class BotBridge {
  static Future<bool> isAccessibilityEnabled() async =>
      await _methodCh.invokeMethod('isAccessibilityEnabled') ?? false;

  static Future<void> openAccessibilitySettings() =>
      _methodCh.invokeMethod('openAccessibilitySettings');

  static Future<bool> isCocInstalled() async =>
      await _methodCh.invokeMethod('isCocInstalled') ?? false;

  static Future<bool> startBot() async =>
      await _methodCh.invokeMethod('startBot') ?? false;

  static Future<bool> stopBot() async =>
      await _methodCh.invokeMethod('stopBot') ?? false;

  static Future<void> scheduleBot(int hour, int minute) =>
      _methodCh.invokeMethod('scheduleBot', {'hour': hour, 'minute': minute});

  static Future<void> cancelSchedule() =>
      _methodCh.invokeMethod('cancelSchedule');

  static Future<Map<String, dynamic>> getBotStatus() async {
    final r = await _methodCh.invokeMethod('getBotStatus');
    return Map<String, dynamic>.from(r ?? {});
  }

  static Stream<String> logStream() => _eventCh
      .receiveBroadcastStream()
      .map((e) => e.toString());
}

// ─── App ─────────────────────────────────────────────────────────────────────

class CocBotApp extends StatelessWidget {
  const CocBotApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CoC Bot',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFE8A020),
          brightness: Brightness.dark,
        ).copyWith(
          surface: const Color(0xFF12121E),
          background: const Color(0xFF0C0C18),
        ),
        scaffoldBackgroundColor: const Color(0xFF0C0C18),
        cardColor: const Color(0xFF1A1A2E),
      ),
      home: const BotHomePage(),
    );
  }
}

// ─── Home Page ────────────────────────────────────────────────────────────────

class BotHomePage extends StatefulWidget {
  const BotHomePage({super.key});
  @override State<BotHomePage> createState() => _BotHomePageState();
}

class _BotHomePageState extends State<BotHomePage> with WidgetsBindingObserver {

  // State
  bool _accessibilityOn = false;
  bool _cocInstalled    = false;
  bool _botRunning      = false;
  bool _scheduled       = false;
  TimeOfDay _schedTime  = const TimeOfDay(hour: 0, minute: 0);
  int _freeBuilders     = 0;
  int _upgradesStarted  = 0;

  final List<String> _logs = [];
  final ScrollController _scroll = ScrollController();
  StreamSubscription<String>? _logSub;
  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _init();
  }

  Future<void> _init() async {
    await _checkStatus();
    _loadPrefs();

    // Listen to log stream from Kotlin
    _logSub = BotBridge.logStream().listen((msg) {
      setState(() => _logs.add(msg));
      if (msg.startsWith('BOT_DONE')) {
        setState(() => _botRunning = false);
      }
      _scrollToBottom();
    });

    // Poll status every 3 seconds
    _statusTimer = Timer.periodic(const Duration(seconds: 3), (_) => _checkStatus());
  }

  Future<void> _checkStatus() async {
    final a = await BotBridge.isAccessibilityEnabled();
    final c = await BotBridge.isCocInstalled();
    final s = await BotBridge.getBotStatus();
    if (mounted) setState(() {
      _accessibilityOn  = a;
      _cocInstalled     = c;
      _botRunning       = s['running'] ?? false;
      _freeBuilders     = s['freeBuilders'] ?? 0;
      _upgradesStarted  = s['upgradesStarted'] ?? 0;
    });
  }

  void _loadPrefs() async {
    final p = await SharedPreferences.getInstance();
    setState(() {
      _scheduled  = p.getBool('scheduled') ?? false;
      final h = p.getInt('sched_hour') ?? 0;
      final m = p.getInt('sched_min') ?? 0;
      _schedTime  = TimeOfDay(hour: h, minute: m);
    });
  }

  void _savePrefs() async {
    final p = await SharedPreferences.getInstance();
    await p.setBool('scheduled', _scheduled);
    await p.setInt('sched_hour', _schedTime.hour);
    await p.setInt('sched_min', _schedTime.minute);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _logSub?.cancel();
    _statusTimer?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  void _addLog(String msg) {
    setState(() => _logs.add(msg));
    _scrollToBottom();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
      }
    });
  }

  // ─── Actions ────────────────────────────────────────────────────────────────

  Future<void> _pickTime() async {
    final picked = await showTimePicker(
      context: context, initialTime: _schedTime,
      builder: (ctx, child) => MediaQuery(
        data: MediaQuery.of(ctx).copyWith(alwaysUse24HourFormat: true),
        child: child!,
      ),
    );
    if (picked != null) setState(() => _schedTime = picked);
  }

  Future<void> _toggleSchedule() async {
    if (_scheduled) {
      await BotBridge.cancelSchedule();
      _addLog('⛔ Schedule cancelled');
      setState(() => _scheduled = false);
    } else {
      await BotBridge.scheduleBot(_schedTime.hour, _schedTime.minute);
      _addLog('⏰ Scheduled daily at ${_timeStr(_schedTime)}');
      setState(() => _scheduled = true);
    }
    _savePrefs();
  }

  Future<void> _runNow() async {
    if (!_accessibilityOn) {
      _showAccessibilityDialog();
      return;
    }
    _addLog('🚀 Running bot now...');
    setState(() => _botRunning = true);
    await BotBridge.startBot();
  }

  Future<void> _stopBot() async {
    await BotBridge.stopBot();
    setState(() => _botRunning = false);
    _addLog('⛔ Bot stopped by user');
  }

  void _showAccessibilityDialog() {
    showDialog(context: context, builder: (ctx) => AlertDialog(
      backgroundColor: const Color(0xFF1A1A2E),
      title: const Text('Enable Accessibility', style: TextStyle(color: Colors.white)),
      content: const Text(
        'CoC Bot needs Accessibility Service permission to tap the game UI.\n\n'
        '1. Tap "Open Settings"\n'
        '2. Find "CoC Bot" in the list\n'
        '3. Toggle it ON\n'
        '4. Tap "Allow"',
        style: TextStyle(color: Colors.white70),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel')),
        ElevatedButton(
          style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFFE8A020)),
          onPressed: () {
            Navigator.pop(ctx);
            BotBridge.openAccessibilitySettings();
          },
          child: const Text('Open Settings', style: TextStyle(color: Colors.black)),
        ),
      ],
    ));
  }

  String _timeStr(TimeOfDay t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  // ─── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            _buildHeader(),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    _buildStatusCards(),
                    const SizedBox(height: 16),
                    if (!_accessibilityOn) _buildAccessibilityBanner(),
                    if (!_accessibilityOn) const SizedBox(height: 16),
                    _buildSchedulerCard(),
                    const SizedBox(height: 16),
                    _buildActionButtons(),
                    const SizedBox(height: 16),
                    _buildLogCard(),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
      decoration: const BoxDecoration(
        color: Color(0xFF1A1A2E),
        border: Border(bottom: BorderSide(color: Color(0xFF2A2A4E))),
      ),
      child: Row(
        children: [
          const Text('🏰', style: TextStyle(fontSize: 24)),
          const SizedBox(width: 10),
          const Text('CoC Upgrade Bot',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold,
                  color: Color(0xFFE8A020))),
          const Spacer(),
          _dot('ACC', _accessibilityOn),
          const SizedBox(width: 12),
          _dot('BOT', _botRunning),
        ],
      ),
    );
  }

  Widget _dot(String label, bool active) => Row(
    children: [
      Container(width: 8, height: 8,
          decoration: BoxDecoration(
              color: active ? const Color(0xFF4CAF50) : Colors.red,
              shape: BoxShape.circle)),
      const SizedBox(width: 4),
      Text(label, style: const TextStyle(fontSize: 11, color: Colors.white54)),
    ],
  );

  Widget _buildStatusCards() {
    return Row(children: [
      _statCard('🔨', 'Free Builders', '$_freeBuilders', const Color(0xFFE8A020)),
      const SizedBox(width: 10),
      _statCard('⬆️', 'Upgrades Done', '$_upgradesStarted', const Color(0xFF4CAF50)),
      const SizedBox(width: 10),
      _statCard('📱', 'CoC', _cocInstalled ? 'Installed' : 'Not Found',
          _cocInstalled ? Colors.white70 : Colors.red),
    ]);
  }

  Widget _statCard(String icon, String label, String value, Color valueColor) =>
      Expanded(child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
            color: const Color(0xFF1A1A2E),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: const Color(0xFF2A2A4E))),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(icon, style: const TextStyle(fontSize: 18)),
          const SizedBox(height: 6),
          Text(label, style: const TextStyle(fontSize: 10, color: Colors.white38, letterSpacing: 0.5)),
          const SizedBox(height: 2),
          Text(value, style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold, color: valueColor)),
        ]),
      ));

  Widget _buildAccessibilityBanner() {
    return GestureDetector(
      onTap: _showAccessibilityDialog,
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: Colors.orange.withOpacity(0.12),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: Colors.orange.withOpacity(0.4)),
        ),
        child: Row(children: [
          const Icon(Icons.warning_amber_rounded, color: Colors.orange),
          const SizedBox(width: 12),
          const Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Accessibility not enabled',
                  style: TextStyle(color: Colors.orange, fontWeight: FontWeight.bold)),
              Text('Tap here to enable it — required for bot to work',
                  style: TextStyle(color: Colors.orange, fontSize: 12)),
            ]),
          ),
          const Icon(Icons.arrow_forward_ios, color: Colors.orange, size: 14),
        ]),
      ),
    );
  }

  Widget _buildSchedulerCard() {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: const Color(0xFF1A1A2E),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFF2A2A4E)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('DAILY SCHEDULE',
            style: TextStyle(fontSize: 10, letterSpacing: 2,
                color: Colors.white38, fontWeight: FontWeight.w600)),
        const SizedBox(height: 14),
        Row(children: [
          // Time picker button
          GestureDetector(
            onTap: _pickTime,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
              decoration: BoxDecoration(
                  color: const Color(0xFF2A2A4E),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFFE8A020), width: 1.5)),
              child: Row(children: [
                const Icon(Icons.access_time, color: Color(0xFFE8A020), size: 18),
                const SizedBox(width: 8),
                Text(_timeStr(_schedTime),
                    style: const TextStyle(
                        fontSize: 26, fontWeight: FontWeight.bold,
                        color: Color(0xFFE8A020))),
              ]),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(child: GestureDetector(
            onTap: _toggleSchedule,
            child: Container(
              padding: const EdgeInsets.symmetric(vertical: 14),
              decoration: BoxDecoration(
                  color: _scheduled
                      ? Colors.red.withOpacity(0.15)
                      : const Color(0xFF4CAF50).withOpacity(0.15),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                      color: _scheduled ? Colors.redAccent : const Color(0xFF4CAF50),
                      width: 1.5)),
              child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Icon(_scheduled ? Icons.stop : Icons.schedule,
                    color: _scheduled ? Colors.redAccent : const Color(0xFF4CAF50),
                    size: 18),
                const SizedBox(width: 8),
                Text(_scheduled ? 'Cancel' : 'Schedule',
                    style: TextStyle(
                        color: _scheduled ? Colors.redAccent : const Color(0xFF4CAF50),
                        fontWeight: FontWeight.bold)),
              ]),
            ),
          )),
        ]),
        if (_scheduled) ...[
          const SizedBox(height: 10),
          Row(children: [
            const Icon(Icons.circle, size: 7, color: Color(0xFF4CAF50)),
            const SizedBox(width: 8),
            Text('Active — upgrades every day at ${_timeStr(_schedTime)}',
                style: const TextStyle(color: Colors.white54, fontSize: 12)),
          ]),
        ],
      ]),
    );
  }

  Widget _buildActionButtons() {
    return Row(children: [
      Expanded(child: _botRunning
          ? _bigBtn('⛔  Stop Bot', Colors.redAccent, _stopBot)
          : _bigBtn('▶  Run Now', const Color(0xFFE8A020), _runNow)),
    ]);
  }

  Widget _bigBtn(String label, Color color, VoidCallback onTap) =>
      GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 16),
          decoration: BoxDecoration(
              color: color.withOpacity(0.15),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: color, width: 1.5)),
          child: Center(child: Text(label,
              style: TextStyle(color: color, fontSize: 16, fontWeight: FontWeight.bold))),
        ),
      );

  Widget _buildLogCard() {
    return Container(
      height: 280,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF08080F),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFF2A2A4E)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Text('LOG', style: TextStyle(fontSize: 10, letterSpacing: 2,
              color: Colors.white38, fontWeight: FontWeight.w600)),
          const Spacer(),
          GestureDetector(
            onTap: () => setState(() => _logs.clear()),
            child: const Text('Clear', style: TextStyle(fontSize: 11, color: Colors.white24)),
          ),
        ]),
        const SizedBox(height: 10),
        Expanded(child: _logs.isEmpty
            ? const Center(child: Text('Bot logs will appear here',
                style: TextStyle(color: Colors.white24, fontSize: 13)))
            : ListView.builder(
                controller: _scroll,
                itemCount: _logs.length,
                itemBuilder: (_, i) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 1.5),
                  child: Text(_logs[i],
                      style: TextStyle(
                          fontFamily: 'monospace', fontSize: 11,
                          color: _logColor(_logs[i]))),
                ),
              )),
      ]),
    );
  }

  Color _logColor(String e) {
    if (e.contains('❌') || e.contains('💥')) return Colors.redAccent;
    if (e.contains('✅') || e.contains('🎉')) return const Color(0xFF4CAF50);
    if (e.contains('⏰') || e.contains('🚀')) return const Color(0xFFE8A020);
    if (e.contains('👆')) return Colors.lightBlueAccent;
    return Colors.white54;
  }
}