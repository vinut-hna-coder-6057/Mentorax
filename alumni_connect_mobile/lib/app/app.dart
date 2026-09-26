import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'router.dart';
import '../shared/widgets/ui_components.dart';

class AlumniConnectApp extends ConsumerWidget {
  const AlumniConnectApp({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
        title: 'Alumni Connect',
        debugShowCheckedModeBanner: false,
        routerConfig: ref.watch(routerProvider),
        theme: ThemeData(
            useMaterial3: true,
            colorScheme: ColorScheme.fromSeed(
                seedColor: AppColors.blue,
                brightness: Brightness.light,
                surface: Colors.white),
            scaffoldBackgroundColor: AppColors.background,
            appBarTheme: const AppBarTheme(
                backgroundColor: Colors.transparent,
                foregroundColor: AppColors.ink,
                elevation: 0,
                centerTitle: false),
            navigationBarTheme: NavigationBarThemeData(
                height: 70,
                indicatorColor: AppColors.blue.withValues(alpha: .12),
                labelTextStyle: const WidgetStatePropertyAll(
                    TextStyle(fontWeight: FontWeight.w600))),
            cardTheme: const CardThemeData(
                margin: EdgeInsets.zero,
                elevation: 0,
                color: Colors.white,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.all(Radius.circular(18)),
                    side: BorderSide(color: Color(0xffe5ebf3)))),
            inputDecorationTheme:
                InputDecorationTheme(
                    filled: true,
                    fillColor: Colors.white,
                    border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: const BorderSide(color: Color(0xffd9e2ec))),
                    enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: const BorderSide(color: Color(0xffd9e2ec))),
                    focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: const BorderSide(
                            color: AppColors.blue, width: 1.5))),
            filledButtonTheme: FilledButtonThemeData(
                style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14)),
                    textStyle: const TextStyle(fontWeight: FontWeight.w700))),
            textTheme: const TextTheme(
                headlineMedium: TextStyle(
                    color: AppColors.ink, fontWeight: FontWeight.w800),
                titleLarge: TextStyle(
                    color: AppColors.ink, fontWeight: FontWeight.w800),
                titleMedium: TextStyle(
                    color: AppColors.ink, fontWeight: FontWeight.w700),
                bodyMedium: TextStyle(color: AppColors.muted))),
        darkTheme: ThemeData(
            useMaterial3: true,
            brightness: Brightness.dark,
            colorSchemeSeed: AppColors.blue,
            cardTheme: const CardThemeData(
                margin: EdgeInsets.zero,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.all(Radius.circular(18))))),
        themeMode: ThemeMode.system,
      );
}
