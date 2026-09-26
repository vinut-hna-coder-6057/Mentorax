import 'dart:async';
import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../../app/env.dart';
import '../../shared/models/models.dart';

/// STOMP client matching the Spring Boot SockJS `/chat` endpoint.
class RealtimeService {
  final _state = StreamController<RealtimeState>.broadcast();
  final _messages = StreamController<ChatMessage>.broadcast();
  Stream<RealtimeState> get states => _state.stream;
  Stream<ChatMessage> get messages => _messages.stream;
  RealtimeState _current = RealtimeState.disconnected;
  RealtimeState get current => _current;
  StompClient? _client;
  bool _subscribed = false;
  String? _token;

  void connect(String token) {
    if (token.isEmpty) return;
    if (_client != null &&
        _token == token &&
        (_current == RealtimeState.connected ||
            _current == RealtimeState.connecting ||
            _current == RealtimeState.reconnecting)) {
      return;
    }
    disconnect();
    _token = token;
    _set(RealtimeState.connecting);
    final sockJsUrl = '${AppEnvironment.baseUrl}/chat';
    late final StompConfig config;
    config = StompConfig.sockJS(
      url: sockJsUrl,
      stompConnectHeaders: {
        'Authorization': ['Bearer ', token].join()
      },
      reconnectDelay: const Duration(seconds: 5),
      beforeConnect: () async => config.resetSession(),
      onConnect: _onConnect,
      onWebSocketDone: () {
        _subscribed = false;
        _set(_token == null
            ? RealtimeState.disconnected
            : RealtimeState.reconnecting);
      },
      onStompError: (_) {
        _subscribed = false;
        _set(RealtimeState.reconnecting);
      },
      onWebSocketError: (_) {
        _subscribed = false;
        _set(RealtimeState.reconnecting);
      },
    );
    _client = StompClient(config: config)..activate();
  }

  void _onConnect(StompFrame _) {
    _set(RealtimeState.connected);
    if (_subscribed || _client == null) return;
    _subscribed = true;
    _client!.subscribe(
      destination: '/user/queue/messages',
      callback: (frame) {
        final body = frame.body;
        if (body == null || body.isEmpty) return;
        try {
          final json = jsonDecode(body);
          if (json is Map) {
            _messages
                .add(ChatMessage.fromJson(Map<String, dynamic>.from(json)));
          }
        } catch (_) {
          // Ignore malformed broker frames without terminating the connection.
        }
      },
    );
  }

  bool send(ChatMessage message) {
    final client = _client;
    if (client == null || !client.connected) return false;
    client.send(
      destination: '/app/chat',
      body: jsonEncode({
        'receiverEmail': message.receiverEmail,
        'content': message.content,
      }),
    );
    return true;
  }

  void disconnect() {
    _token = null;
    _subscribed = false;
    final client = _client;
    _client = null;
    try {
      client?.deactivate();
    } catch (_) {}
    _set(RealtimeState.disconnected);
  }

  void _set(RealtimeState value) {
    _current = value;
    if (!_state.isClosed) _state.add(value);
  }

  Future<void> dispose() async {
    disconnect();
    await _state.close();
    await _messages.close();
  }
}

enum RealtimeState { disconnected, connecting, connected, reconnecting }
