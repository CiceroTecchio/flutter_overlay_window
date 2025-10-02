import 'dart:async';
import 'dart:developer';

import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/src/models/overlay_position.dart';
import 'package:flutter_overlay_window/src/overlay_config.dart';

class FlutterOverlayWindow {
  FlutterOverlayWindow._();

  // Otimização: StreamController com tipo específico para melhor performance
  static final StreamController<dynamic> _controller =
      StreamController<dynamic>.broadcast();

  // Otimização: Cache de canais para evitar recriações
  static MethodChannel? _cachedChannel;
  static MethodChannel? _cachedOverlayChannel;
  static BasicMessageChannel? _cachedMessageChannel;

  static MethodChannel get _channel {
    _cachedChannel ??= const MethodChannel("x-slayer/overlay_channel");
    return _cachedChannel!;
  }

  static MethodChannel get _overlayChannel {
    _cachedOverlayChannel ??= const MethodChannel("x-slayer/overlay");
    return _cachedOverlayChannel!;
  }

  static BasicMessageChannel get _overlayMessageChannel {
    _cachedMessageChannel ??= const BasicMessageChannel(
      "x-slayer/overlay_messenger",
      JSONMessageCodec(),
    );
    return _cachedMessageChannel!;
  }

  /// Open overLay content
  ///
  /// - Optional arguments:
  ///
  /// `height` the overlay height and default is [WindowSize.fullCover]
  ///
  /// `width` the overlay width and default is [WindowSize.matchParent]
  ///
  /// `alignment` the alignment postion on screen and default is [OverlayAlignment.center]
  ///
  /// `visibilitySecret` the detail displayed in notifications on the lock screen and default is [NotificationVisibility.visibilitySecret]
  ///
  /// `OverlayFlag` the overlay flag and default is [OverlayFlag.defaultFlag]
  ///
  /// `overlayTitle` the notification message and default is "overlay activated"
  ///
  /// `overlayContent` the notification message
  ///
  /// `enableDrag` to enable/disable dragging the overlay over the screen and default is "false"
  ///
  /// `positionGravity` the overlay postion after drag and default is [PositionGravity.none]
  ///
  /// `startPosition` the overlay start position and default is null

  static Future<void> showOverlay({
    int height = WindowSize.fullCover,
    int width = WindowSize.matchParent,
    OverlayAlignment alignment = OverlayAlignment.center,
    NotificationVisibility visibility = NotificationVisibility.visibilitySecret,
    OverlayFlag flag = OverlayFlag.defaultFlag,
    String overlayTitle = "overlay activated",
    String? overlayContent,
    bool enableDrag = false,
    PositionGravity positionGravity = PositionGravity.none,
    OverlayPosition? startPosition,
  }) async {
    // Otimização: Preparar parâmetros uma única vez
    final Map<String, dynamic> params = {
      "height": height,
      "width": width,
      "alignment": alignment.name,
      "flag": flag.name,
      "overlayTitle": overlayTitle,
      "overlayContent": overlayContent,
      "enableDrag": enableDrag,
      "notificationVisibility": visibility.name,
      "positionGravity": positionGravity.name,
      "startPosition": startPosition?.toMap(),
    };

    // Sempre chamar o método, sem cache de parâmetros
    await _channel.invokeMethod('showOverlay', params);
  }

  /// Check if overlay permission is granted
  static Future<bool> isPermissionGranted() async {
    try {
      return await _channel.invokeMethod<bool>('checkPermission') ?? false;
    } on PlatformException catch (error) {
      log("$error");
      return Future.value(false);
    }
  }

  /// Check if Device is LockedOrScreenOff
  static Future<bool> isDeviceLockedOrScreenOff() async {
    try {
      return await _channel.invokeMethod<bool>('isDeviceLockedOrScreenOff') ??
          false;
    } on PlatformException catch (error) {
      log("$error");
      return Future.value(false);
    }
  }

  /// Request overlay permission
  /// it will open the overlay settings page and return `true` once the permission granted.
  static Future<bool?> requestPermission() async {
    try {
      return await _channel.invokeMethod<bool?>('requestPermission');
    } on PlatformException catch (error) {
      log("Error requestPermession: $error");
      rethrow;
    }
  }

  /// Closes overlay if open
  static Future<bool?> closeOverlay() async {
    final bool? _res = await _channel.invokeMethod('closeOverlay');
    return _res;
  }

  /// Broadcast data to and from overlay app
  static Future shareData(dynamic data) async {
    return await _overlayMessageChannel.send(data);
  }

  // Otimização: Cache do stream para evitar recriações
  static Stream<dynamic>? _cachedStream;

  /// Streams message shared between overlay and main app
  static Stream<dynamic> get overlayListener {
    // Otimização: Reutilizar stream existente se possível
    if (_cachedStream == null) {
      _overlayMessageChannel.setMessageHandler((message) async {
        if (!_controller.isClosed) {
          _controller.add(message);
        }
        return message;
      });
      _cachedStream = _controller.stream;
    }
    return _cachedStream!;
  }

  /// Update the overlay flag while the overlay in action
  static Future<bool?> updateFlag(OverlayFlag flag) async {
    final bool? _res = await _overlayChannel.invokeMethod<bool?>('updateFlag', {
      'flag': flag.name,
    });
    return _res;
  }

  /// Update the overlay size in the screen
  static Future<bool?> resizeOverlay(
    int width,
    int height,
    bool enableDrag,
  ) async {
    final bool? _res = await _overlayChannel.invokeMethod<bool?>(
      'resizeOverlay',
      {'width': width, 'height': height, 'enableDrag': enableDrag},
    );
    return _res;
  }

  /// Update the overlay position in the screen
  ///
  /// `position` the new position of the overlay
  ///
  /// `return` true if the position updated successfully
  static Future<bool?> moveOverlay(OverlayPosition position) async {
    final bool? _res = await _channel.invokeMethod<bool?>(
      'moveOverlay',
      position.toMap(),
    );
    return _res;
  }

  /// Get the current overlay position
  ///
  /// `return` the current overlay position
  static Future<OverlayPosition> getOverlayPosition() async {
    final Map<Object?, Object?>? _res = await _channel.invokeMethod(
      'getOverlayPosition',
    );
    return OverlayPosition.fromMap(_res);
  }

  /// Check if the current overlay is active
  static Future<bool> isActive() async {
    final bool? _res = await _channel.invokeMethod<bool?>('isOverlayActive');
    return _res ?? false;
  }

  /// Dispose overlay stream
  static void disposeOverlayListener() {
    if (!_controller.isClosed) {
      _controller.close();
    }
    // Otimização: Limpar cache ao dispor
    _cachedStream = null;
    _cachedChannel = null;
    _cachedOverlayChannel = null;
    _cachedMessageChannel = null;
  }

  /// Check if the lock screen permission is granted
  static Future<bool> isLockScreenPermissionGranted() async {
    final bool? _res = await _channel.invokeMethod<bool?>(
      'isLockScreenPermissionGranted',
    );
    return _res ?? true;
  }

  /// Open the lock screen permission settings page
  static Future<void> openLockScreenPermissionSettings() async {
    await _channel.invokeMethod<void>('openLockScreenPermissionSettings');
  }
}
