import 'dart:io';

import 'package:alumni_connect_mobile/app/api_endpoints.dart';

void main(List<String> args) {
  // Exercise the exact production fallback used by release configuration.
  ApiEndpoints.resolveSpringBaseUrl(debug: false);
  if (args.contains('--print-production-url')) {
    stdout.writeln(ApiEndpoints.springProductionBaseUrl);
  } else {
    stdout.writeln('Spring API URL configuration is valid.');
  }
}
