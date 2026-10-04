import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../../app/env.dart';
import '../../shared/models/models.dart';

/// STOMP client matching the Spring Boot SockJS `/chat` endpoint.
class RealtimeService {
  static const messagesDestination = '/user/queue/messages';
  static const notificationsDestination = '/user/queue/notifications';
  static const sendDestination = '/app/chat';

  static String sockJsEndpointFor(String apiBaseUrl) {
    final uri = Uri.tryParse(apiBaseUrl.trim());
    if (uri == null ||
        !uri.hasAuthority ||
        (uri.scheme != 'http' && uri.scheme != 'https')) {
      throw ArgumentError.value(
          apiBaseUrl, 'apiBaseUrl', 'Expected an HTTP(S) URL.');
    }
    final basePath = uri.path.replaceFirst(RegExp(r'/+$'), '');
    return uri
        .replace(
          path: '$basePath/chat',
          query: null,
          fragment: null,
        )
        .toString();
  }

  static Map<String, String> stompHeadersForToken(String token) => {
        'Authorization': 'Bearer $token',
      };

  final _state = StreamController<RealtimeState>.broadcast();
  final _messages = StreamController<ChatMessage>.broadcast();
  final _notifications = StreamController<NotificationItem>.broadcast();
  final _errors = StreamController<RealtimeFailure>.broadcast();
  Stream<RealtimeState> get states => _state.stream;
  Stream<ChatMessage> get messages => _messages.stream;
  Stream<NotificationItem> get notifications => _notifications.stream;
  Stream<RealtimeFailure> get errors => _errors.stream;
  RealtimeState _current = RealtimeState.disconnected;
  RealtimeState get current => _current;
  StompClient? _client;
  bool _subscribed = false;
  bool _awaitingConnect = false;
  bool _terminalFailure = false;
  String? _token;
  int _receiptSequence = 0;
  final Map<String, RealtimeFailure> _pendingReceipts = {};

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
    _terminalFailure = false;
    _token = token;
    _set(RealtimeState.connecting);
    final sockJsUrl = sockJsEndpointFor(AppEnvironment.baseUrl);
    late final StompConfig config;
    config = StompConfig.sockJS(
      url: sockJsUrl,
      stompConnectHeaders: stompHeadersForToken(token),
      reconnectDelay: const Duration(seconds: 5),
      beforeConnect: () async {
        _awaitingConnect = true;
        _pendingReceipts.clear();
        config.resetSession();
      },
      onConnect: _onConnect,
      onWebSocketDone: () {
        if (_terminalFailure) return;
        _awaitingConnect = false;
        _subscribed = false;
        _pendingReceipts.clear();
        _set(_token == null
            ? RealtimeState.disconnected
            : RealtimeState.reconnecting);
      },
      onStompError: _onStompError,
      onUnhandledReceipt: _onReceipt,
      onWebSocketError: _onWebSocketError,
    );
    _client = StompClient(config: config)..activate();
  }

  void _onConnect(StompFrame _) {
    _awaitingConnect = false;
    _set(RealtimeState.connected);
    final client = _client;
    if (_subscribed || client == null) return;
    _subscribed = true;
    _subscribe(
      client,
      messagesDestination,
      RealtimeFailure.subscription,
      _decodeMessage,
    );
    if (_terminalFailure) return;
    _subscribe(
      client,
      notificationsDestination,
      RealtimeFailure.subscription,
      _decodeNotification,
    );
  }

  void _subscribe(
    StompClient client,
    String destination,
    RealtimeFailure failure,
    void Function(String body) onBody,
  ) {
    final receipt = _nextReceipt('subscribe');
    _pendingReceipts[receipt] = failure;
    try {
      client.subscribe(
        destination: destination,
        headers: {'receipt': receipt},
        callback: (frame) {
          final body = frame.body;
          if (body == null || body.isEmpty) return;
          onBody(body);
        },
      );
    } catch (error) {
      _pendingReceipts.remove(receipt);
      _reportFailure(
        failure,
        'Unable to subscribe to $destination (${error.runtimeType}).',
        terminal: true,
      );
    }
  }

  void _decodeMessage(String body) {
    try {
      final json = jsonDecode(body);
      if (json is Map) {
        _messages.add(ChatMessage.fromJson(Map<String, dynamic>.from(json)));
      }
    } catch (error) {
      debugPrint(
          'Ignoring malformed STOMP message frame (${error.runtimeType}).');
    }
  }

  void _decodeNotification(String body) {
    try {
      final json = jsonDecode(body);
      if (json is Map) {
        _notifications.add(
          NotificationItem.fromJson(Map<String, dynamic>.from(json)),
        );
      }
    } catch (error) {
      debugPrint(
        'Ignoring malformed STOMP notification frame (${error.runtimeType}).',
      );
    }
  }

  void _onReceipt(StompFrame frame) {
    final receipt = frame.headers['receipt-id'];
    if (receipt == null) return;
    _pendingReceipts.remove(receipt);
  }

  void _onStompError(StompFrame frame) {
    final failure = _awaitingConnect
        ? RealtimeFailure.authentication
        : _pendingReceipts.values.isEmpty
            ? RealtimeFailure.connection
            : _pendingReceipts.values.first;
    final phase = _awaitingConnect
        ? 'CONNECT'
        : failure == RealtimeFailure.subscription
            ? 'SUBSCRIBE'
            : failure == RealtimeFailure.send
                ? 'SEND'
                : 'connected session';
    _reportFailure(
      failure,
      'STOMP ERROR during $phase (command ${frame.command}).',
      terminal: failure == RealtimeFailure.authentication ||
          failure == RealtimeFailure.subscription,
    );
  }

  void _onWebSocketError(dynamic error) {
    final details = error.toString().replaceAll(
          RegExp(r'Bearer\s+\S+', caseSensitive: false),
          'Bearer [REDACTED]',
        );
    final statusCode = _httpStatusCode(details);
    debugPrint(
      'Realtime WebSocket transport failed'
      '${statusCode == null ? '' : ' with HTTP $statusCode'}: $details',
    );
    if (statusCode == 401 || statusCode == 403) {
      _reportFailure(
        RealtimeFailure.authentication,
        'WebSocket handshake was rejected with HTTP $statusCode.',
        terminal: true,
      );
      return;
    }
    _emitFailure(RealtimeFailure.connection);
    if (!_terminalFailure) _set(RealtimeState.reconnecting);
  }

  int? _httpStatusCode(String details) {
    final match = RegExp(
      r'(?:statusCode:\s*|status code\s+)(\d{3})',
      caseSensitive: false,
    ).firstMatch(details);
    return match == null ? null : int.tryParse(match.group(1)!);
  }

  String _nextReceipt(String operation) =>
      'mentorax-$operation-${_receiptSequence++}';

  bool send(ChatMessage message) {
    final client = _client;
    if (client == null || !client.connected) return false;
    final receipt = _nextReceipt('send');
    _pendingReceipts[receipt] = RealtimeFailure.send;
    try {
      client.send(
        destination: sendDestination,
        headers: {'receipt': receipt},
        body: jsonEncode({
          'receiverEmail': message.receiverEmail,
          'content': message.content,
        }),
      );
      return true;
    } catch (error) {
      _pendingReceipts.remove(receipt);
      _reportFailure(
        RealtimeFailure.send,
        'Unable to send STOMP message (${error.runtimeType}).',
      );
      return false;
    }
  }

  void _reportFailure(
    RealtimeFailure failure,
    String diagnostic, {
    bool terminal = false,
  }) {
    debugPrint('Realtime ${failure.name} failure: $diagnostic');
    _emitFailure(failure);
    if (terminal) {
      _terminalFailure = true;
      _awaitingConnect = false;
      _token = null;
      _subscribed = false;
      _pendingReceipts.clear();
      final client = _client;
      _client = null;
      try {
        client?.deactivate();
      } catch (error) {
        debugPrint(
            'Unable to stop failed STOMP client (${error.runtimeType}).');
      }
      _set(failure == RealtimeFailure.authentication
          ? RealtimeState.authenticationRejected
          : RealtimeState.subscriptionFailed);
    }
  }

  void _emitFailure(RealtimeFailure failure) {
    if (!_errors.isClosed) _errors.add(failure);
  }

  void disconnect() {
    _token = null;
    _subscribed = false;
    _awaitingConnect = false;
    _terminalFailure = false;
    _pendingReceipts.clear();
    final client = _client;
    _client = null;
    try {
      client?.deactivate();
    } catch (error) {
      debugPrint('Unable to deactivate STOMP client (${error.runtimeType}).');
    }
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
    await _notifications.close();
    await _errors.close();
  }
}

enum RealtimeFailure { connection, authentication, subscription, send }

enum RealtimeState {
  disconnected,
  connecting,
  connected,
  reconnecting,
  authenticationRejected,
  subscriptionFailed,
}
