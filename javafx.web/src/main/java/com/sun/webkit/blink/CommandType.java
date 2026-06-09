/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package com.sun.webkit.blink;

/**
 * Java→engine command type IDs. Must match the engine's
 * {@code jux_command_types.h} exactly (Java is the canonical source of truth).
 *
 * <p>Commands are written to the command ring buffer and read by the engine's
 * dispatch loop. Only the subset needed by the current phase is exercised;
 * the full set is mirrored so the contract stays complete and verifiable.
 *
 * <p>Internal; never exported from {@code javafx.web}.
 */
final class CommandType {

    private CommandType() { }

    // Window lifecycle (0x0001–0x000F)
    static final int CREATE_WINDOW = 0x0001;
    static final int DESTROY_WINDOW = 0x0002;
    static final int SHOW = 0x0003;
    static final int HIDE = 0x0004;

    // Window properties (0x0010–0x001F)
    static final int SET_SIZE = 0x0011;
    static final int SET_TRANSPARENT = 0x001A;

    // Popup control (DOM/content block 0x0030–0x003F; 0x0012/0x0013 are taken by
    // the engine's window kSetPosition/kSetMinSize).
    /** [windowId:4][screenX:8(double)][screenY:8(double)][scale:8(double)] — move the
     *  hidden engine window's origin to the WebView node's on-screen position so Blink's
     *  native page-popups (select/color/datalist) land in the right place. */
    static final int SET_SCREEN_ORIGIN = 0x003D;
    /** [windowId:4][bits:4] — which popups the app overrides (bit0=select, bit1=color,
     *  bit2=contextMenu). Set ⇒ engine suppresses its native UI and surfaces the request
     *  to Java; clear ⇒ native. */
    static final int SET_POPUP_OVERRIDES = 0x003E;
    /** [windowId:4][cmd:4] — run a Blink editor command (copy/cut/paste/…). */
    static final int EXEC_EDITING_COMMAND = 0x003F;

    // DOM / content (0x0030–0x003F)
    static final int LOAD_HTML = 0x0030;
    static final int LOAD_URL = 0x0031;
    static final int EXECUTE_JS = 0x0032;
    /** Like {@link #EXECUTE_JS} but the payload string is a temp-file path whose
     *  contents are the script — for scripts larger than a ring slot. */
    static final int EXECUTE_JS_FILE = 0x003B;
    static final int OPEN_DEV_TOOLS = 0x0034;
    static final int CLOSE_DEV_TOOLS = 0x0035;
    /** Per-WebView User-Agent override; payload {@code [len:4][utf8]} (windowId
     *  prepended by the bridge). Empty string clears the override. */
    static final int SET_USER_AGENT = 0x003C;

    // Window actions (0x0060–0x006F)
    static final int REQUEST_FOCUS = 0x0060;
    static final int RELEASE_FOCUS = 0x0061;

    // Print (0x0090–0x009F)
    static final int PRINT = 0x0090;
    static final int PRINT_TO_PDF = 0x0091;
    static final int SHOW_PRINT_PREVIEW = 0x0092;
    static final int SAVE_PDF_RESPONSE = 0x0093;

    // DOM listener management (0x0080–0x008F)
    /** [windowId:4][nodeId:4][typeLen:2][utf8Type:N] — register a Blink event listener. */
    static final int ADD_EVENT_LISTENER = 0x0080;
    /** [windowId:4][nodeId:4][typeLen:2][utf8Type:N] — unregister a listener. */
    static final int REMOVE_EVENT_LISTENER = 0x0081;

    // DOM manipulation (0x00A0–0x00BF)
    // All carry the target node id (engine-assigned for existing nodes, Java-
    // allocated for created ones). windowId prefix is prepended by the encoder.
    /** [windowId:4][nodeId:4][tagLen:2][utf8Tag:N] */
    static final int CREATE_ELEMENT = 0x00A0;
    /** [windowId:4][nodeId:4] */
    static final int REMOVE_ELEMENT = 0x00A1;
    /** [windowId:4][nodeId:4][nameLen:2][utf8Name:N][valueLen:2][utf8Value:N] */
    static final int SET_ATTRIBUTE = 0x00A2;
    /** [windowId:4][nodeId:4][nameLen:2][utf8Name:N] */
    static final int REMOVE_ATTRIBUTE = 0x00A3;
    /** [windowId:4][parentId:4][childId:4] */
    static final int APPEND_CHILD = 0x00A4;
    /** [windowId:4][parentId:4][childId:4][refId:4] (refId=0 ⇒ append) */
    static final int INSERT_BEFORE = 0x00A5;
    /** [windowId:4][parentId:4][childId:4] */
    static final int REMOVE_CHILD = 0x00A6;
    /** [windowId:4][nodeId:4][textLen:4][utf8Text:N] */
    static final int SET_TEXT_CONTENT = 0x00A7;
    /** [windowId:4][nodeId:4][htmlLen:4][utf8Html:N] */
    static final int SET_INNER_HTML = 0x00A8;
    /** [windowId:4][nodeId:4][propLen:2][utf8Prop:N][valueLen:2][utf8Value:N] */
    static final int SET_STYLE_PROPERTY = 0x00A9;
    /** [windowId:4][nodeId:4][propLen:2][utf8Prop:N] */
    static final int REMOVE_STYLE_PROPERTY = 0x00AA;
    /** [windowId:4][nodeId:4][classLen:2][utf8Class:N] */
    static final int ADD_CLASS = 0x00AB;
    /** [windowId:4][nodeId:4][classLen:2][utf8Class:N] */
    static final int REMOVE_CLASS = 0x00AC;
    /** [windowId:4][nodeId:4] */
    static final int DOM_FOCUS = 0x00AD;
    /** [windowId:4][nodeId:4] */
    static final int DOM_BLUR = 0x00AE;
    /** [windowId:4][nodeId:4] */
    static final int DOM_CLICK = 0x00AF;

    // Off-screen input injection (0x00C0–0x00CF)
    /** [windowId:4][type:4][x:4(f32)][y:4(f32)][button:4][clickCount:4][modifiers:4] — type 0=move,1=down,2=up */
    static final int MOUSE_EVENT = 0x00C0;
    /** [windowId:4][x:4(f32)][y:4(f32)][deltaX:4(f32)][deltaY:4(f32)][modifiers:4] */
    static final int WHEEL_EVENT = 0x00C1;
    /** Mouse event routed to the open OSR popup instead of the main frame; same
     *  payload as {@link #MOUSE_EVENT}, (x,y) popup-local. */
    static final int POPUP_MOUSE_EVENT = 0x00C4;
    /** [windowId:4][type:4][windowsKeyCode:4][nativeKeyCode:4][modifiers:4][textLen:4][utf8:N] — type 0=keydown,1=keyup,2=char */
    static final int KEY_EVENT = 0x00C2;
    /** [windowId:4][focused:4] */
    static final int FOCUS_EVENT = 0x00C3;
    /** Wheel event routed to the open OSR popup (scroll a long &lt;select&gt;/datalist
     *  list); same payload as {@link #WHEEL_EVENT}, (x,y) popup-local. */
    static final int POPUP_WHEEL_EVENT = 0x00C5;
    /** Key event routed to the open OSR popup (arrow/Enter/Esc/type-ahead); same
     *  payload as {@link #KEY_EVENT}. */
    static final int POPUP_KEY_EVENT = 0x00C6;

    // Dialog / chooser / permission responses (0x00D0–0x00DF).
    // Sent on the FX thread when the app answers a request the engine surfaced.
    // Each runs the Chromium continuation the engine stashed under the matching
    // id, resuming the page. Mirrors jux_command_types.h.
    /** [windowId:4][dialogId:4][accepted:1][textLen:4][utf8Text:N] — alert/confirm/prompt/beforeunload. */
    static final int DIALOG_RESPONSE = 0x00D0;
    /** [windowId:4][chooserId:4][chosen:1][rgba:4] — &lt;input type=color&gt;. */
    static final int COLOR_CHOOSER_RESPONSE = 0x00D1;
    /** [windowId:4][popupId:4][accepted:1][count:4]{[index:4]}… — &lt;select&gt;. */
    static final int SELECT_POPUP_RESPONSE = 0x00D2;
    /** [windowId:4][chooserId:4][count:4]{[pathLen:4][utf8Path:N]}… (count=0 ⇒ cancel). */
    static final int FILE_CHOOSER_RESPONSE = 0x00D3;
    /** [windowId:4][permId:4][granted:1]. */
    static final int PERMISSION_RESPONSE = 0x00D4;
    /** [windowId:4][authId:4][supplied:1][userLen:4][utf8User:N][passLen:4][utf8Pass:N]. */
    static final int AUTH_RESPONSE = 0x00D5;
    /** [windowId:4][downloadId:4][accepted:1][pathLen:4][utf8Path:N]. */
    static final int DOWNLOAD_RESPONSE = 0x00D6;
    /** [windowId:4][downloadId:4]. */
    static final int DOWNLOAD_CANCEL = 0x00D7;
    /** [windowId:4][fsId:4][allowed:1]. */
    static final int FULLSCREEN_RESPONSE = 0x00D9;

    // Network interception control (0x00E0–0x00EF). Mirrors jux_command_types.h.
    /** [windowId:4][filterLen:4][filterBlob:N] — arm interception with a serialized NetworkFilter. */
    static final int ARM_INTERCEPTION = 0x00E0;
    /** [windowId:4][pathLen:4][utf8TempFilePath:N] — oversize filter blob via temp file. */
    static final int ARM_INTERCEPTION_FILE = 0x00E1;
    /** [windowId:4] — disarm interception. */
    static final int DISARM_INTERCEPTION = 0x00E2;
    /** [windowId:4][interceptId:4][phase:1][action:1][tailLen:4][tail:N] — per-exchange decision. */
    static final int INTERCEPT_DECISION = 0x00E3;
    /** [windowId:4][interceptId:4][phase:1][action:1][pathLen:4][utf8TempFilePath:N] — oversize decision tail. */
    static final int INTERCEPT_DECISION_FILE = 0x00E4;
    /** [windowId:4][interceptId:4][chunkSeq:4][edit:1][tailLen:4][tail:N] — per-chunk body edit. */
    static final int INTERCEPT_BODY_EDIT = 0x00E5;

    // Navigation / session history (0x00F0–0x00FF)
    /**
     * Navigate the engine's session history by a signed offset relative to the
     * current entry (-1 = back, +1 = forward). The engine validates the offset
     * (CanGoToOffset) and, on commit, echoes a fresh HISTORY_STATE event.
     * Payload: [windowId:4][offset:4(int32)].
     */
    static final int GO_TO_OFFSET = 0x00F0;

    /**
     * Restores a serialized session (URL + scroll + form values + history) into
     * a freshly-respawned engine after a crash, so the user's last-good state is
     * recovered. The blob is engine-opaque (see {@link NativeEventType#SESSION_STATE})
     * and staged to a temp file (it can be large with form data):
     * Payload: [windowId:4][pathLen:4][utf8TempFilePath:N]. The engine reads the
     * file, restores via NavigationController::Restore, then deletes it.
     */
    static final int RESTORE_SESSION = 0x00F1;

    // JavaScript object interop (0x0100–0x011F). Each sync op carries a 4-byte
    // requestId (correlated like EXECUTE_JS) and a target objId (0 = the global
    // window object). Values are tagged (see JSValueCodec). Results come back as
    // the JS_VALUE event (or JS_ERROR). See docs/WEBVIEW_JS_BRIDGE.md.
    /** [windowId:4][reqId:4][objId:4][nameLen:4][utf8Name:N] */
    static final int JS_GET_MEMBER = 0x0100;
    /** [windowId:4][reqId:4][objId:4][nameLen:4][utf8Name:N][value:tagged] */
    static final int JS_SET_MEMBER = 0x0101;
    /** [windowId:4][reqId:4][objId:4][nameLen:4][utf8Name:N] */
    static final int JS_REMOVE_MEMBER = 0x0102;
    /** [windowId:4][reqId:4][objId:4][index:4] */
    static final int JS_GET_SLOT = 0x0103;
    /** [windowId:4][reqId:4][objId:4][index:4][value:tagged] */
    static final int JS_SET_SLOT = 0x0104;
    /** [windowId:4][reqId:4][objId:4][nameLen:4][utf8Name:N][argc:4]{value:tagged}… */
    static final int JS_CALL = 0x0105;
    /** [windowId:4][reqId:4][objId:4][scriptLen:4][utf8Script:N] (objId scope) */
    static final int JS_EVAL = 0x0106;
    /** [windowId:4][objId:4] — fire-and-forget release from the Cleaner; no reqId. */
    static final int JS_RELEASE = 0x0107;
    /**
     * Result of a Java method invoked by a host-proxy call — settles the JS
     * promise. [windowId:4][callId:4][status:1][payload] where status 0 =
     * success (payload = tagged value) and 1 = error (payload = [len:4][utf8]).
     */
    static final int JS_CALLBACK_RESULT = 0x0108;
}
