import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:dio/dio.dart';
import '../../app/env.dart';
import '../errors/error_handler.dart';
import '../storage/secure_storage_service.dart';
import '../errors/api_exception.dart';

class ApiClient {
  ApiClient(
    this._storage, {
    Dio? dio,
    VoidCallback? onUnauthorized,
  })  : _onUnauthorized = onUnauthorized,
        _dio = dio ??
            Dio(BaseOptions(
                baseUrl: AppEnvironment.baseUrl,
                connectTimeout: const Duration(seconds: 15),
                receiveTimeout: const Duration(seconds: 20),
                responseType: ResponseType.plain,
                headers: const {'Accept': '*/*'})) {
    _dio.interceptors
        .add(InterceptorsWrapper(onRequest: (options, handler) async {
      final token = await _storage.readToken();
      if (token != null && token.isNotEmpty) {
        options.headers['Authorization'] = ['Bearer ', token].join();
      }
      handler.next(options);
    }));
  }

  final SecureStorageService _storage;
  final Dio _dio;
  final VoidCallback? _onUnauthorized;
  Future<T> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(dynamic) decode,
  }) =>
      _run(
        () => _dio.get(path, queryParameters: query),
        decode,
        path,
      );
  Future<T> post<T>(String path,
          {dynamic data,
          Map<String, dynamic>? query,
          required T Function(dynamic) decode}) =>
      _run(
        () => _dio.post(path, data: data, queryParameters: query),
        decode,
        path,
      );

  Future<T> put<T>(String path,
          {dynamic data,
          Map<String, dynamic>? query,
          required T Function(dynamic) decode}) =>
      _run(
        () => _dio.put(path, data: data, queryParameters: query),
        decode,
        path,
      );

  Future<T> delete<T>(String path,
          {Map<String, dynamic>? query, required T Function(dynamic) decode}) =>
      _run(
        () => _dio.delete(path, queryParameters: query),
        decode,
        path,
      );
  Future<T> _run<T>(
    Future<Response<dynamic>> Function() request,
    T Function(dynamic) decode,
    String path,
  ) async {
    try {
      return decode(_decodeBody((await request()).data));
    } catch (error) {
      final apiError = toApiException(error, requestPath: path);

      const publicAuthPaths = {
        '/login',
        '/signup',
        '/forgot-password',
        '/verify-otp',
        '/reset-password',
      };

      if (apiError.kind == ApiErrorKind.unauthorized &&
          !publicAuthPaths.contains(path)) {
        _onUnauthorized?.call();
      }

      throw apiError;
    }
  }

  dynamic _decodeBody(dynamic raw) {
    if (raw == null) return null;
    if (raw is! String) return raw;
    final text = raw.trim();
    if (text.isEmpty) return '';
    if (text.startsWith('{') ||
        text.startsWith('[') ||
        (text.startsWith('"') && text.endsWith('"')) ||
        text == 'true' ||
        text == 'false' ||
        text == 'null' ||
        RegExp(r'^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$')
            .hasMatch(text)) {
      return jsonDecode(text);
    }
    return text;
  }
}
