import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:alumni_connect_mobile/app/providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('restores the saved theme mode', () async {
    SharedPreferences.setMockInitialValues({'theme_mode': 'dark'});

    final notifier = ThemeModeNotifier();
    await notifier.ready;

    expect(notifier.state, ThemeMode.dark);
    notifier.dispose();
  });

  test('persists a theme mode selection', () async {
    SharedPreferences.setMockInitialValues({});

    final notifier = ThemeModeNotifier();
    await notifier.ready;
    await notifier.setThemeMode(ThemeMode.light);

    final preferences = await SharedPreferences.getInstance();
    expect(preferences.getString('theme_mode'), 'light');
    expect(notifier.state, ThemeMode.light);
    notifier.dispose();
  });

  test('ignores a late preference restore after a selection', () async {
    SharedPreferences.setMockInitialValues({'theme_mode': 'dark'});

    final notifier = ThemeModeNotifier();
    await notifier.setThemeMode(ThemeMode.light);
    await notifier.ready;

    expect(notifier.state, ThemeMode.light);
    notifier.dispose();
  });
}
